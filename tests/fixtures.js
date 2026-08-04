import fs from 'node:fs';

const readTsvRows = (fileName, columns) => {
  const fixtureUrl = new URL(`../testdata/${fileName}`, import.meta.url);
  return fs.readFileSync(fixtureUrl, 'utf8')
    .replace(/\r?\n$/, '')
    .split(/\r?\n/)
    .filter(line => line && !line.startsWith('#'))
    .map((line) => {
      const values = line.split('\t');
      if (values.length !== columns.length) {
        throw new Error(`Invalid fixture row in ${fileName}: ${line}`);
      }
      return Object.fromEntries(columns.map((column, index) => [column, values[index]]));
    });
};

export const readNormalizationCases = () =>
  readTsvRows('normalization_cases.tsv', ['name', 'input', 'expectedRaw'])
    .map(({ name, input, expectedRaw }) => ({
      name,
      input,
      expected: expectedRaw === '<null>' ? null : expectedRaw.split('|')
    }));

export const readGlossRankCases = () =>
  readTsvRows('gloss_rank_cases.tsv', ['name', 'definitionsRaw', 'key', 'expectedRaw'])
    .map(({ name, definitionsRaw, key, expectedRaw }) => ({
      name,
      definitions: definitionsRaw.split('||'),
      key,
      expected: expectedRaw === '<none>' ? Infinity : Number(expectedRaw)
    }));

export const readDefinitionFormattingCases = () =>
  readTsvRows('definition_formatting_cases.tsv', ['name', 'input', 'expected']);
