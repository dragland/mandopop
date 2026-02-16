# CLAUDE.md

## Gotchas

- **Dictionary in service worker only** - Don't load in content.js (17MB × every tab). Cached in IndexedDB, versioned by extension version.
- **No innerHTML** - Use DOM APIs (XSS vector in content scripts)
- **Settings listener**: `'key' in changes` not `changes.key !== undefined`
- **Speech**: `zh-TW`, Meijia voice preferred
- **ARIA**: `role="status"` not `role="tooltip"` — popup has interactive buttons
- **WCAG**: Definition text `#888` on `#0d0d0d`, icon fill `#999` on `#1a1a1a` — don't go darker

## Non-obvious decisions

- Definitions only shown when multiple matches (user already knows the English word they selected)
- Pinyin inline with characters (not below) to save vertical space

## Module architecture

- `background.js` (ESM, `"type": "module"` in manifest) → imports `lib/normalize.js`
- `content.js` (IIFE) → can't import from `lib/` without a build step
- `scripts/preprocess_cedict.cjs` (CJS) → duplicates pinyin logic from `lib/pinyin.js` (CJS can't import ESM)
- `tests/` → imports from `lib/`
