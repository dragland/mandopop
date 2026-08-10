/**
 * Traverse sync orchestration — port of android TraverseSync.kt +
 * CardVocabulary.kt, minus notifications.
 *
 * Reads bill to Traverse's project, so the gating mirrors the phone
 * exactly: every sync reads one events/{today} heartbeat document, and the
 * deck is pulled only when the review count moved, the local day rolled
 * over, the mirror is 6h stale, or the user forced it. Card content drains
 * via batchGet — 150 per request, strictly sequential, 3s apart — and
 * cached negatives are versioned by PARSER_VERSION so a parse failure is
 * stale, never done.
 */

import { account, idToken, TraverseError } from './auth.js';
import {
  reviewCountOn, allScheduleRows, fetchCards,
  CARD_BATCH_SIZE, CARD_BATCH_PAUSE_MS,
} from './firestore.js';
import { parseHanzi, PARSER_VERSION } from './card_parser.js';
import { deriveKnownWords, indexBySimplified } from './known_words.js';
import { buildReplacementMap } from '../chinglish.js';

const MAX_STALENESS_MS = 6 * 60 * 60 * 1000;
const CONTENT_LIMIT = 1500;
const MIN_SAMPLE = 20;
const MIN_COLLAPSE = 3;

export const SCHEDULES_KEY = 'traverseSchedules';
export const CARDS_KEY = 'traverseCards';
export const SYNC_STATE_KEY = 'traverseSyncState';
export const KNOWN_WORDS_KEY = 'knownWords';
export const REPLACEMENT_MAP_KEY = 'replacementMap';

/**
 * ACTOR, SET and Minimal Pairs teach a pinyin sound and carry no word —
 * extracting from them scrapes incidental hanzi out of mnemonics. Suffix
 * match, because Traverse prefixes templates with the course in some
 * places and not others.
 */
export function isSoundOnly(template) {
  return template.endsWith('ACTOR REVIEW')
    || template.endsWith('SET REVIEW')
    || template.endsWith('Minimal Pairs');
}

/**
 * Collapse schedule rows (one per prompt) into per-card summaries.
 * Exclusion and started-ness are per CARD: a card with one ACTOR prompt
 * and one other is sound-only, and a card with any unsuspended row is
 * started.
 */
export function summarizeCards(rows) {
  const cards = new Map();
  for (const row of rows) {
    if (!row.cardId) continue;
    let card = cards.get(row.cardId);
    if (!card) {
      card = { cardId: row.cardId, template: row.template, author: row.author, started: false, soundOnly: false };
      cards.set(row.cardId, card);
    }
    if (row.template < card.template) card.template = row.template;
    if (row.author && (!card.author || row.author < card.author)) card.author = row.author;
    if (!row.suspended) card.started = true;
    if (isSoundOnly(row.template)) card.soundOnly = true;
  }
  return cards;
}

/**
 * A template is broken when it collapses wholesale — every card unreadable
 * on a small sample, or under half readable on a large one. One loud
 * error per parser version beats a quiet gap in the index.
 */
export function brokenTemplate(outcomes) {
  const byTemplate = new Map();
  for (const { template, read } of outcomes) {
    const entry = byTemplate.get(template) ?? { total: 0, read: 0 };
    entry.total++;
    if (read) entry.read++;
    byTemplate.set(template, entry);
  }
  for (const [template, { total, read }] of byTemplate) {
    if ((total >= MIN_COLLAPSE && read === 0) || (total >= MIN_SAMPLE && read * 2 < total)) {
      return { template, total, read };
    }
  }
  return null;
}

function localDate(now = new Date()) {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function readStorage(keys) {
  return chrome.storage.local.get(keys);
}

async function recordError(message) {
  const stored = await readStorage(SYNC_STATE_KEY);
  const state = stored[SYNC_STATE_KEY] ?? {};
  await chrome.storage.local.set({
    [SYNC_STATE_KEY]: { ...state, lastSyncAtMs: Date.now(), lastError: message },
  });
}

/**
 * Drain card content for stale cards, paced and persisted per chunk so an
 * evicted worker keeps what it read. Failure is RETURNED, not thrown —
 * the caller still rebuilds the index from partial data before surfacing it.
 */
async function drainCardContent(token, cards, content) {
  const pending = [...cards.values()]
    .filter((c) => !c.soundOnly)
    .filter((c) => content[c.cardId] === undefined || content[c.cardId].v < PARSER_VERSION)
    .slice(0, CONTENT_LIMIT);
  if (pending.length === 0) return { changed: false, failure: null };

  const byAuthor = new Map();
  for (const card of pending) {
    const list = byAuthor.get(card.author) ?? [];
    list.push(card);
    byAuthor.set(card.author, list);
  }

  const outcomes = [];
  let changed = false;
  let first = true;
  try {
    for (const [author, authorCards] of byAuthor) {
      for (let at = 0; at < authorCards.length; at += CARD_BATCH_SIZE) {
        const chunk = authorCards.slice(at, at + CARD_BATCH_SIZE);
        if (!first) await sleep(CARD_BATCH_PAUSE_MS);
        first = false;

        const found = await fetchCards(token, author, chunk.map((c) => c.cardId));
        // A systemic miss (renamed collection, wrong author) must abort
        // BEFORE anything is written, or ~940 cards get cached as
        // permanent negatives.
        const missing = chunk.length - found.size;
        if (missing * 2 > chunk.length && chunk.length >= MIN_SAMPLE) {
          throw new TraverseError(`Traverse returned no document for ${missing} of ${chunk.length} cards`);
        }

        const now = Date.now();
        for (const card of chunk) {
          const doc = found.get(card.cardId);
          const hanzi = doc ? parseHanzi(doc) : null;
          content[card.cardId] = { hanzi, v: PARSER_VERSION, at: now };
          outcomes.push({ template: card.template, read: hanzi !== null });
        }
        await chrome.storage.local.set({ [CARDS_KEY]: content });
        changed = true;
      }
    }
  } catch (error) {
    return { changed, failure: error };
  }

  const broken = brokenTemplate(outcomes);
  return {
    changed,
    failure: broken
      ? new TraverseError(
          `Template ${broken.template}: read ${broken.read} of ${broken.total} cards — extraction broken?`,
        )
      : null,
  };
}

let inFlight = null;

/** Single-flight sync. Resolves to a status object; never rejects. */
export function sync(dictionary, { force = false } = {}) {
  inFlight ??= syncInternal(dictionary, force).finally(() => {
    inFlight = null;
  });
  return inFlight;
}

async function syncInternal(dictionary, force) {
  const acct = await account();
  if (!acct) return { status: 'not-signed-in' };

  try {
    const token = await idToken();
    const today = localDate();
    const stored = await readStorage([SCHEDULES_KEY, CARDS_KEY, SYNC_STATE_KEY, KNOWN_WORDS_KEY]);
    const state = stored[SYNC_STATE_KEY] ?? {};
    let rows = stored[SCHEDULES_KEY] ?? [];
    const content = stored[CARDS_KEY] ?? {};

    const reviewCount = await reviewCountOn(token, acct.uid, today);
    const stale = state.lastPullAtMs === undefined || Date.now() - state.lastPullAtMs > MAX_STALENESS_MS;
    const pull = force
      || stale
      || rows.length === 0
      || state.lastEventDate !== today
      || state.lastEventCount !== reviewCount;

    if (pull) {
      rows = await allScheduleRows(token, acct.uid);
      if (rows.length === 0 && state.lastSuccessAtMs !== undefined) {
        throw new TraverseError('Traverse returned no schedules — refusing to wipe local data');
      }
      const live = new Set(rows.map((r) => r.cardId));
      for (const cardId of Object.keys(content)) {
        if (!live.has(cardId)) delete content[cardId];
      }
      // Bank the heartbeat before the drain: a content failure must not
      // buy a fresh ~1,000-row pull on every retry.
      Object.assign(state, { lastPullAtMs: Date.now(), lastEventDate: today, lastEventCount: reviewCount });
      await chrome.storage.local.set({
        [SCHEDULES_KEY]: rows,
        [CARDS_KEY]: content,
        [SYNC_STATE_KEY]: state,
      });
    }

    const cards = summarizeCards(rows);
    // Sound-only cards never keep content; the fetch filter and this
    // cleanup share one predicate so they cannot disagree.
    for (const cardId of Object.keys(content)) {
      if (cards.get(cardId)?.soundOnly) delete content[cardId];
    }

    const backfill = await drainCardContent(token, cards, content);

    let knownWords = stored[KNOWN_WORDS_KEY] ?? [];
    if (backfill.changed || pull || knownWords.length === 0) {
      const startedHanzi = [...cards.values()]
        .filter((c) => c.started && content[c.cardId] !== undefined && content[c.cardId].hanzi !== null)
        .map((c) => content[c.cardId].hanzi);
      knownWords = deriveKnownWords(startedHanzi, indexBySimplified(dictionary));
      await chrome.storage.local.set({
        [KNOWN_WORDS_KEY]: knownWords,
        [REPLACEMENT_MAP_KEY]: buildReplacementMap(knownWords, dictionary),
      });
    }

    const eligible = [...cards.values()].filter((c) => !c.soundOnly);
    const fetched = eligible.filter((c) => (content[c.cardId]?.v ?? -1) >= PARSER_VERSION);
    const coverage = {
      words: knownWords.length,
      cards: eligible.length,
      fetched: fetched.length,
      readable: fetched.filter((c) => content[c.cardId].hanzi !== null).length,
    };

    await chrome.storage.local.set({
      [SYNC_STATE_KEY]: {
        ...state,
        ...coverage,
        lastSyncAtMs: Date.now(),
        lastSuccessAtMs: backfill.failure ? state.lastSuccessAtMs : Date.now(),
        lastError: backfill.failure ? backfill.failure.message : null,
      },
    });

    if (backfill.failure) {
      return { status: 'failure', error: backfill.failure.message, ...coverage };
    }
    return { status: 'success', ...coverage };
  } catch (error) {
    console.error('[Mandopop] Traverse sync failed:', error);
    await recordError(error.message);
    return { status: 'failure', error: error.message, statusCode: error.statusCode ?? null };
  }
}
