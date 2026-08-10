#!/usr/bin/env node
/**
 * Preprocess CC-CEDICT into the single dictionary artifact both platforms load.
 *
 * Input format:  Traditional Simplified [pinyin] /definition1/definition2/
 * Output format: { v, entries: [{ s, p, d }], index: { "english key": [entryId] } }
 *
 * Two properties matter, and they are why this file is the *only* CC-CEDICT parser:
 *
 *  - **Complete.** `entries` holds every entry in the source, including ones no English key
 *    reaches. The Android app looks words up by hanzi (Traverse identifies cards that way), and
 *    the English index alone drops ~17k entries — disproportionately the single characters a
 *    learner meets first (个, 二, 十, 日 …), because they lose the ranking tiebreak below and then
 *    fall off the per-key cap.
 *  - **Normalized.** `index` stores entry ids rather than repeated entry objects. Entries were
 *    previously duplicated ~2x across keys, so this is smaller than the old format *and* lossless.
 *
 * The extension and the Android SQLite build both consume this one file, so English->Chinese
 * behaviour cannot drift between platforms.
 */

import fs from 'node:fs';
import crypto from 'node:crypto';
import { numberedToToneMarks, extractEnglishWords, extractPhrases } from '../lib/pinyin.js';
import { exactGlossRank } from '../lib/normalize.js';

export const FORMAT_VERSION = 2;
export { exactGlossRank };

/** Cap per English key. Bounds ranking noise; does not affect `entries` completeness. */
const MAX_ENTRIES_PER_KEY = 40;

/**
 * Loads SUBTLEX-CH occurrences per million, keyed by word.
 *
 * This contributes no headwords, definitions or pinyin — only an ordering signal. Absent words
 * score 0, which is the desired behaviour: an entry nobody says should not outrank one they do.
 */
export function readFrequencies(tsv) {
  const freq = new Map();
  for (const line of tsv.split('\n')) {
    const tab = line.indexOf('\t');
    if (tab <= 0) continue;
    const value = Number.parseFloat(line.slice(tab + 1));
    if (Number.isFinite(value)) freq.set(line.slice(0, tab), value);
  }
  return freq;
}

/** Primary meaning, secondary meaning, or merely mentions the key. */
function glossTier(entry, key) {
  const rank = exactGlossRank(entry, key);
  if (rank <= PRIMARY_SENSE_LIMIT) return 0;
  return Number.isFinite(rank) ? 1 : 2;
}

/** Senses this far in still count as what the word primarily means. */
const PRIMARY_SENSE_LIMIT = 1;

const LINE = /^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+\/(.+)\/\s*$/;

export function buildDictionary(content, frequencies = new Map()) {
  const entries = [];
  const idBySignature = new Map();
  /** @type {Map<string, number[]>} english key -> entry ids */
  const index = new Map();
  let lineCount = 0;

  for (const line of content.split('\n')) {
    if (line.startsWith('#') || !line.trim()) continue;

    const match = line.match(LINE);
    if (!match) continue;

    const [, , simplified, pinyinRaw, definitionsRaw] = match;
    // Normalize to NFC so tone-marked vowels are single code points, matching the precomposed
    // forms used downstream (JSON + Android SQLite).
    const pinyin = numberedToToneMarks(pinyinRaw).normalize('NFC');
    const definitions = definitionsRaw.normalize('NFC').split('/').filter(d => d.trim());

    const signature = JSON.stringify([simplified, pinyin, definitions]);
    let id = idBySignature.get(signature);
    if (id === undefined) {
      id = entries.length;
      idBySignature.set(signature, id);
      entries.push({ s: simplified, p: pinyin, d: definitions });
    }
    lineCount++;

    for (const def of definitions) {
      for (const key of [...extractEnglishWords(def), ...extractPhrases(def)]) {
        let ids = index.get(key);
        if (!ids) {
          ids = [];
          index.set(key, ids);
        }
        // Dedupe per key on (simplified, pinyin) rather than on the full entry, so a headword
        // does not appear twice under one key just because it has two sense groupings.
        const duplicate = ids.some(
          other => entries[other].s === simplified && entries[other].p === pinyin
        );
        if (!duplicate) ids.push(id);
      }
    }
  }

  for (const [key, ids] of index) {
    index.set(key, rankForKey(key, ids, entries, frequencies));
  }

  return { version: FORMAT_VERSION, entries, index, lineCount };
}

/**
 * Orders the candidates for one English key: which hanzi should appear first.
 *
 * Scores are precomputed rather than derived inside the comparator, because the exact-gloss test
 * walks every sense of an entry and would otherwise run on each comparison.
 *
 * The order puts *primary* meaning before usage, then usage before secondary meaning. An entry
 * whose leading sense is the key beats a commoner word that merely mentions it, so "two" cannot be
 * won by a frequent word glossed "...two...". Frequency then separates the rest, which is what
 * stops obscure literary synonyms from leading.
 */
export function rankForKey(key, ids, entries, frequencies) {
  const scored = ids.map(id => {
    const entry = entries[id];
    return {
      id,
      exact: glossTier(entry, key),
      freq: frequencies.get(entry.s) ?? 0,
      defLength: entry.d.reduce((sum, definition) => sum + definition.length, 0),
    };
  });

  scored.sort((a, b) =>
    a.exact - b.exact ||
    b.freq - a.freq ||
    a.defLength - b.defLength ||
    // Stable final tiebreak so regenerating never reshuffles equal candidates.
    a.id - b.id
  );

  return scored.slice(0, MAX_ENTRIES_PER_KEY).map(entry => entry.id);
}

function main() {
  const inputPath = new URL('../cedict_ts.u8', import.meta.url);
  const frequencyPath = new URL('../subtlex_ch.tsv', import.meta.url);
  const outputPath = new URL('../cedict.json', import.meta.url);

  const frequencies = readFrequencies(fs.readFileSync(frequencyPath, 'utf8'));
  const { entries, index, lineCount } = buildDictionary(
    fs.readFileSync(inputPath, 'utf8'),
    frequencies
  );

  const json = JSON.stringify({
    v: FORMAT_VERSION,
    entries,
    index: Object.fromEntries(index)
  });
  fs.writeFileSync(outputPath, json);

  // Emit a content hash so the service worker can key its IndexedDB cache on the dictionary's
  // contents rather than the extension version. Regenerating then invalidates stale caches
  // automatically, with no manifest version bump. Android invalidates via its own sha256.
  const hash = crypto.createHash('sha256').update(json).digest('hex').slice(0, 16);
  fs.writeFileSync(
    new URL('../dict_version.js', import.meta.url),
    '// Auto-generated by scripts/preprocess_cedict.js. Do not edit by hand.\n' +
    `export const DICT_HASH = '${hash}';\n`
  );

  const keys = [...index.keys()];
  const linked = new Set([...index.values()].flat()).size;
  const withFrequency = entries.filter(entry => frequencies.has(entry.s)).length;
  console.log(`Loaded ${frequencies.size} SUBTLEX-CH frequencies`);
  console.log(`Parsed ${lineCount} CC-CEDICT lines into ${entries.length} unique entries`);
  console.log(`  ranked by frequency: ${withFrequency} (rest fall back to gloss length)`);
  console.log(`  reachable from an English key: ${linked} (${entries.length - linked} hanzi-only)`);
  console.log(`Created index with ${keys.length} keys (${keys.filter(k => k.includes(' ')).length} phrase keys)`);
  console.log(`Output file size: ${Math.round(fs.statSync(outputPath).size / 1024)} KB`);
  console.log(`Dictionary hash: ${hash}`);
}

if (import.meta.url === `file://${process.argv[1]}`) main();
