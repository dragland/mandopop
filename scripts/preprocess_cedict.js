#!/usr/bin/env node
/**
 * Preprocess CC-CEDICT dictionary into JSON format keyed by English words
 *
 * Input format: Traditional Simplified [pinyin] /definition1/definition2/
 * Output: { "english_key": [{ s, p, d }], ... }
 */

import fs from 'node:fs';
import { numberedToToneMarks, extractEnglishWords, extractPhrases } from '../lib/pinyin.js';

// Main processing
const processDict = () => {
  const inputPath = new URL('../cedict_ts.u8', import.meta.url);
  const outputPath = new URL('../cedict.json', import.meta.url);

  const content = fs.readFileSync(inputPath, 'utf8');
  const lines = content.split('\n');

  // Dictionary keyed by English words
  const dict = {};
  let entryCount = 0;

  for (const line of lines) {
    // Skip comments and empty lines
    if (line.startsWith('#') || !line.trim()) continue;

    // Parse line: Traditional Simplified [pinyin] /definitions/
    const match = line.match(/^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+\/(.+)\/\s*$/);
    if (!match) continue;

    const [, , simplified, pinyinRaw, definitionsRaw] = match;
    const pinyin = numberedToToneMarks(pinyinRaw);
    const definitions = definitionsRaw.split('/').filter(d => d.trim());

    // Create entry object
    const entry = {
      s: simplified,      // simplified Chinese
      p: pinyin,          // pinyin with tone marks
      d: definitions      // definitions array
    };

    // Index by each meaningful English word in definitions
    for (const def of definitions) {
      const words = extractEnglishWords(def);
      for (const word of words) {
        if (!dict[word]) {
          dict[word] = [];
        }
        // Avoid duplicates
        const exists = dict[word].some(e => e.s === entry.s && e.p === entry.p);
        if (!exists) {
          dict[word].push(entry);
        }
      }

      // Index by multi-word phrases
      const phrases = extractPhrases(def);
      for (const phrase of phrases) {
        if (!dict[phrase]) {
          dict[phrase] = [];
        }
        const exists = dict[phrase].some(e => e.s === entry.s && e.p === entry.p);
        if (!exists) {
          dict[phrase].push(entry);
        }
      }
    }

    entryCount++;
  }

  // Common words that should be prioritized in lookups
  const commonWords = new Set([
    '的', '是', '不', '了', '在', '有', '人', '这', '我', '他',
    '你好', '谢谢', '再见', '对不起', '没关系', '请', '好', '是的', '不是',
    '银行', '电脑', '手机', '汽车', '飞机', '火车', '地铁', '公共汽车',
    '学校', '医院', '餐厅', '商店', '超市', '机场', '车站',
    '吃', '喝', '看', '听', '说', '读', '写', '走', '跑', '来', '去',
    '大', '小', '多', '少', '高', '低', '长', '短', '新', '旧',
    '钱', '时间', '工作', '学习', '朋友', '家', '书', '水', '茶', '咖啡'
  ]);

  // Sort entries by relevance
  for (const word in dict) {
    dict[word].sort((a, b) => {
      // Prioritize common words
      const aCommon = commonWords.has(a.s) ? 0 : 1;
      const bCommon = commonWords.has(b.s) ? 0 : 1;
      if (aCommon !== bCommon) return aCommon - bCommon;

      // Shorter definitions often mean primary meaning
      const aDefLen = a.d.reduce((sum, d) => sum + d.length, 0);
      const bDefLen = b.d.reduce((sum, d) => sum + d.length, 0);

      // Prefer 2-character words (most common in Chinese)
      const aLen = a.s.length === 2 ? 0 : a.s.length === 1 ? 1 : 2;
      const bLen = b.s.length === 2 ? 0 : b.s.length === 1 ? 1 : 2;
      if (aLen !== bLen) return aLen - bLen;

      return aDefLen - bDefLen;
    });
    // Limit to top 10 entries per word to reduce file size
    if (dict[word].length > 10) {
      dict[word] = dict[word].slice(0, 10);
    }
  }

  // Write output
  fs.writeFileSync(outputPath, JSON.stringify(dict));

  const allKeys = Object.keys(dict);
  const phraseKeys = allKeys.filter(k => k.includes(' ')).length;
  const stats = {
    entries: entryCount,
    words: allKeys.length,
    phrases: phraseKeys,
    sizeKB: Math.round(fs.statSync(outputPath).size / 1024)
  };

  console.log(`Processed ${stats.entries} dictionary entries`);
  console.log(`Created index with ${stats.words} keys (${stats.phrases} phrase keys)`);
  console.log(`Output file size: ${stats.sizeKB} KB`);
};

processDict();
