# AGENTS.md

## Entry Points

- Chrome: `background.js` owns dictionary load/cache/lookup; `content.js` owns selection UI; `content/cedict_formatter.js` is a classic no-build helper loaded first.
- Shared JS: `lib/normalize.js` handles lookup variants; `lib/pinyin.js` handles pinyin and CEDICT English key extraction.
- Dictionary: `npm run dict:build` (= `scripts/preprocess_cedict.js`) reads committed `cedict_ts.u8` + `subtlex_ch.tsv`, regenerates `cedict.json` and `dict_version.js` (content hash the service worker caches on; generated but committed). Android: `cd android && ./gradlew buildDictionary`.
- `cedict.json` (v2): `{ v, entries: [{s,p,d}], index: { "english key": [entryId] } }`. `entries` is complete (every CC-CEDICT entry, including ones no English key reaches — Android looks up by hanzi); `index` holds ids, not repeated objects. Only `lib/normalize.js#lookup` knows this shape.
- English->Chinese ranking: `rankForKey` sorts by primary-sense gloss match, then SUBTLEX-CH frequency, then gloss length, then id. Sense *position* is load-bearing — matching any sense promotes 门 for "school", 牢 for "fast".
- Android lookup: `TextSelectionService` -> `DictionaryRepository` -> overlay/TTS. `lookupBySimplified` is the hanzi->English path.
- Android Traverse sync: `traverse/` (auth, Firestore REST, orchestration) -> `data/` (Room mirror) -> `notification/DueNotifier`; scheduled by `work/SyncWorker`, nudged by `TraverseExitWatcher` and `NotificationRefreshReceiver`.
- Parity tests: browser and Android share cases from `testdata/`.

## Checks

- JS: `npm test && npm run lint`.
- Android: `cd android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew testDebugUnitTest buildDictionary`.

## Constraints

- **No content egress.** Text the user reads, types, or selects must never leave the device. No runtime lookup or translation may call a remote model or service — dictionary and translation are on-device, always.
- The Chrome extension is fully offline at runtime; do not add network behavior to it. Dictionary regeneration (downloading CC-CEDICT) is a dev-time build step only.
- The Android app may sync the user's own learning state (Traverse/Firebase) over the network. State sync, not content lookup — the only permitted runtime network use.
- Do not load the dictionary in `content.js`; use the service worker.
- Do not use `innerHTML` in content-script UI; use DOM APIs/`textContent`.
- One dictionary source, one parser: CC-CEDICT -> `preprocess_cedict.js` -> `cedict.json` -> Android SQLite. `build_dictionary.py` is a loader; nothing but `preprocess_cedict.js` may read `cedict_ts.u8`. Both platforms consume the same `cedict.json` so English->Chinese cannot drift; `validate_lookup_content` fails the Android build if the SQLite projection disagrees with it.
- `subtlex_ch.tsv` is a ranking signal, not a dictionary: no headwords, definitions or pinyin. Removing it degrades ordering, it does not break lookups.
- Preserve no-build-step Chrome extension assumptions.
- Do not commit host-specific `org.gradle.java.home`; use inline `JAVA_HOME` when needed.
- Settings listener uses `'key' in changes`, not `changes.key !== undefined`.

## UX Gotchas

- Speech prefers `zh-TW`/Meijia.
- Popup role is `status`, not `tooltip`, because it has buttons.
- Keep pinyin inline with characters; show definitions only when multiple matches.
- Do not darken contrast below current values: defs `#888` on `#0d0d0d`, icons `#999` on `#1a1a1a`.
