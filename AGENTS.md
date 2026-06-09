# AGENTS.md

## Entry Points

- Chrome: `background.js` owns dictionary load/cache/lookup; `content.js` owns selection UI; `content/cedict_formatter.js` is a classic no-build helper loaded first.
- Shared JS: `lib/normalize.js` handles lookup variants; `lib/pinyin.js` handles pinyin and CEDICT English key extraction.
- Dictionary: `npm run dict:build` (= `scripts/preprocess_cedict.js`) reads the committed `cedict_ts.u8` source and regenerates `cedict.json` plus the generated `dict_version.js` (a content hash the service worker uses as its cache key — generated but committed, like `cedict.json`). To pull a newer CC-CEDICT, re-download per README first. Android uses `cd android && ./gradlew buildDictionary`.
- Android: `TextSelectionService` -> `DictionaryRepository` -> overlay/TTS.
- Parity tests: browser and Android share cases from `testdata/`.

## Checks

- JS: `npm test && npm run lint`.
- Android: `cd android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew testDebugUnitTest buildDictionary`.

## Constraints

- Offline only at runtime. Do not add network lookup behavior in the extension. Dictionary regeneration (downloading CC-CEDICT) is a dev-time build step only.
- Do not load the ~21MB dictionary in `content.js`; use the service worker.
- Do not use `innerHTML` in content-script UI; use DOM APIs/`textContent`.
- Keep one dictionary source: CC-CEDICT -> `cedict.json` -> Android SQLite.
- Preserve no-build-step Chrome extension assumptions.
- Do not commit host-specific `org.gradle.java.home`; use inline `JAVA_HOME` when needed.
- Settings listener uses `'key' in changes`, not `changes.key !== undefined`.

## UX Gotchas

- Speech prefers `zh-TW`/Meijia.
- Popup role is `status`, not `tooltip`, because it has buttons.
- Keep pinyin inline with characters; show definitions only when multiple matches.
- Do not darken contrast below current values: defs `#888` on `#0d0d0d`, icons `#999` on `#1a1a1a`.
