# AGENTS.md

## Entry Points

- Chrome: `background.js` owns dictionary load/cache/lookup; `content.js` owns selection UI; `content/cedict_formatter.js` is a classic no-build helper loaded first.
- Shared JS: `lib/normalize.js` handles lookup variants; `lib/pinyin.js` handles pinyin and CEDICT English key extraction.
- Dictionary: `npm run dict:build` (= `scripts/preprocess_cedict.js`) reads committed `cedict_ts.u8` + `subtlex_ch.tsv` and regenerates `cedict.json` + `dict_version.js`. Both are generated *and* committed. Android: `cd android && ./gradlew buildDictionary`.
- Android lookup: `TextSelectionService` -> `DictionaryRepository` -> overlay/TTS.
- Android Traverse sync: `traverse/` (auth, REST, orchestration) -> `data/` (Room mirror) -> `notification/DueNotifier`; driven by `work/SyncWorker`, `TraverseExitWatcher`, `NotificationRefreshReceiver`.
- Parity tests: browser and Android share cases from `testdata/`.

## Checks

- JS: `npm test && npm run lint`.
- Android: `cd android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew testDebugUnitTest buildDictionary`.
- Room DAO queries: `./gradlew connectedDebugAndroidTest` (needs a device).

## Constraints

- **No content egress.** Text the user reads, types, or selects must never leave the device. No runtime lookup or translation may call a remote model or service.
- The Chrome extension is fully offline at runtime. Downloading CC-CEDICT is a dev-time build step.
- The Android app may sync the user's own learning state (Traverse/Firebase). State sync, not content lookup — the only permitted runtime network use.
- One dictionary source, one parser: CC-CEDICT -> `preprocess_cedict.js` -> `cedict.json` -> Android SQLite. `build_dictionary.py` is a loader; nothing else may read `cedict_ts.u8`. Both platforms consume the same `cedict.json`, which is what stops English->Chinese drifting between them — `validate_lookup_content` fails the Android build if the SQLite projection disagrees.
- `subtlex_ch.tsv` is a ranking signal, not a dictionary. Removing it degrades ordering; it cannot break a lookup.
- Do not load the dictionary in `content.js`; use the service worker.
- Do not use `innerHTML` in content-script UI; use DOM APIs/`textContent`.
- Preserve no-build-step Chrome extension assumptions.
- Do not commit host-specific `org.gradle.java.home`; use inline `JAVA_HOME`.
- Settings listener uses `'key' in changes`, not `changes.key !== undefined`.

## Dictionary

- `cedict.json` (v2): `{ v, entries: [{s,p,d}], index: { "english key": [entryId] } }`. `entries` is complete — including entries no English key reaches, because Android looks up by hanzi. `index` holds ids, not repeated objects. Only `lib/normalize.js#lookup` knows this shape.
- `rankForKey` orders English->Chinese candidates: primary-sense gloss match, then SUBTLEX-CH frequency, then gloss length, then id. Sense *position* is load-bearing — accepting any matching sense promotes 门 for "school" and 牢 for "fast". Frequency replaced a hand-curated common-word list and a prefer-2-characters rule; do not reintroduce either.
- Reverse lookup is `lookupBySimplified`, backed by the `entries_simplified` index.

## Traverse Sync

- Firestore reads bill to *Traverse's* project. A sync reads one document (`events/{today}`); the full deck is pulled only when that day's review count moves, the local day rolls over, or the mirror is 6h stale. Do not add unconditional full pulls.
- Day boundaries use `ZoneId.systemDefault()` deliberately: Traverse keys its daily doc by the client's local date, and its client is a WebView on the same phone. A fixed zone would break on travel.
- Most cards hide their hanzi behind an opaque id in a `fields` map whose keys vary by template, so `HanziExtractor` scans all strings for CJK and lets CC-CEDICT arbitrate. Do not hardcode field names.
- ACTOR and SET cards teach a pinyin sound and have no headword — extracting from them scrapes incidental hanzi out of mnemonics. PROP cards are *not* excluded: 一 and 十 are components and real words.
- Card content resolves only for cards that are due, bounded per sync. Immersion features will need a wider sweep.
- Room is a cache of remote state; destructive migration is intentional and the next sync refills.
- Token storage is Tink + DataStore. `EncryptedSharedPreferences` is deprecated — do not "simplify" back to it.

## UX Gotchas

- Speech prefers `zh-TW`/Meijia.
- Popup role is `status`, not `tooltip`, because it has buttons.
- Keep pinyin inline with characters; show definitions only when multiple matches.
- Do not darken contrast below current values: defs `#888` on `#0d0d0d`, icons `#999` on `#1a1a1a`.
- The due notification shows characters only; the reading and meaning sit behind Reveal. Showing them upfront defeats recall practice.
- `setOngoing(true)` does not prevent dismissal on Android 14+. Persistence is the `deleteIntent` re-post, which stops at zero due.
- A posted notification stores its icon as a bare resource id, and adding drawables shifts ids — hence the re-post on `MY_PACKAGE_REPLACED`.
- Changing `android:icon` needs a device **reboot** to show up: `system_server` caches parsed manifest data per package. Restarting SystemUI does not clear it, and force-stopping SystemUI can destroy the user's wallpaper.
