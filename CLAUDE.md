# CLAUDE.md

## Gotchas

- **Dictionary in service worker** - Don't load in content.js (17MB × every tab = bad)
- **No innerHTML** - Use DOM APIs to avoid XSS
- **Settings listener**: Use `'key' in changes` not `changes.key !== undefined`
- **Speech lang**: `zh-TW` (Taiwan Mandarin, user preference)
- **Voice**: Meijia preferred (Apple Taiwan female)

## Non-obvious decisions

- Definitions only shown when multiple matches (user already knows the English word they selected)
- Pinyin inline with characters (not below) to save vertical space
- 100ms debounce on selection to prevent flicker

## Regenerating assets

```bash
# Dictionary (after CC-CEDICT update)
node scripts/preprocess_cedict.js

# Icons (requires ImageMagick)
bash scripts/generate_icons.sh
```
