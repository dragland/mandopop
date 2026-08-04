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
- Android: `cd android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew testDebugUnitTest lintDebug buildDictionary compileDebugAndroidTestKotlin`. `lintDebug` is green; keep it that way. It fails the build on errors, so a permission check has to be inline where lint can follow it rather than extracted into a helper. The instrumentation sources only compile as part of the last task — they are easy to break without noticing.
- `adb shell am force-stop com.mandopop` **revokes the accessibility grant** — the app comes back showing "Finish setup" and lookups are dead until it is re-enabled by hand. To restart for a test, launch the activity again or reinstall; never force-stop.
- Room DAO queries and migrations: `./gradlew connectedDebugAndroidTest` (needs a device). It **uninstalls the app afterwards**, taking the accessibility grant, the Traverse session and the local mirror with it. Follow it with `installDebug`, then re-enable the service and sign in again — never run it last against a device in use, and back the database up first (`adb exec-out run-as com.mandopop cat databases/mandopop.db > backup.db`) now that refilling it costs ~940 remote reads.

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
- Card *ids* are opaque; card **field names are not**. Content sits in a nested `fields` map keyed by name (`Chinese`, `Pinyin`, `English Translation`, `HANZI`, `KEYWORD`, `WORD`, `MEANING`, `COMPONENT`), matched case-insensitively. `CardParser` addresses them by name, so a renamed field yields nothing for that template and the parse-rate guard makes it visible.
- **Names locate the pair; shape decides which is which.** On MSLK cards the course has `Chinese` and `Pinyin` swapped, and inconsistently — 130 of 160 sampled cards hold the hanzi under `Pinyin`. Whichever field carries Han characters is the Chinese. Never trust those two names.
- The card document carries its own `template`; prefer it over the schedule row's, since a card with two prompts has two rows and picking one is arbitrary.
- MSLK cards are English→Chinese *sentences*: title is English, Chinese is in the body, stored whole and segmented later. Not all of them are sentences though — 知道 is a one-word MSLK card, so `is_sentence` is decided by CC-CEDICT membership, never by template or by length. That flag is what lets the notification prompt with a card and Reveal look it up.
- ACTOR and SET cards teach a pinyin sound and have no headword — extracting from them scrapes incidental hanzi out of mnemonics. PROP cards are *not* excluded: 一 and 十 are components and real words.
- Card content is backfilled for the **whole deck**, not just due cards, via `documents:batchGet` — 150 per request, sequential, 3 s apart. Immersion features need every word the user has met.
- `CardParser.VERSION` is what makes cached negatives safe: rows below it are stale, not done. Bump it on any extraction change and the next sync re-reads the deck. Without it a parse failure is permanent, which is how 55 of 211 rows ended up recorded as having no content.
- `known_words` is derived from `card_content` and rebuilt wholesale, never diffed — a word has to be able to *leave* when its lesson is suspended.
- A window event naming Traverse is **not** evidence the user returned to it — Traverse re-announces its window about a second after being backgrounded, every time. `TraverseExitWatcher` therefore only reports *leaving*; whether the user is back is decided by checking `rootInActiveWindow` when the settle timer fires. Cancelling on the event instead silently killed every on-exit sync.
- Swiping the due notification away forces a full pull, and is the only refresh gesture outside the settings screen. Unforced syncs are gated on the events heartbeat, which cannot see rescheduling or unsuspended lessons.
- Room is a cache of remote state, but a wipe is no longer free: refilling `card_content` is ~940 document reads on Traverse's project, not one. Every version bump from 3 on needs a real migration — the destructive fallback is scoped to version 1 only — v2 has a real migration — so a missing one throws at open instead of wiping (as does flashing an older build). It cannot be `(1, 2)`: naming a version a registered migration starts *from* makes Room reject the builder and the app dies on launch. Room exports its expected schema to committed `android/app/schemas/*.json`; paste migration DDL from there, because a mismatch throws at first DB access rather than at build time, and `MigrationTest` is what catches it.
- Any query with an opinion about ACTOR/SET cards must use the shared `SOUND_ONLY` predicate and apply it per *card*. When the fetch filter and the cleanup delete disagreed, a card with one ACTOR prompt and one other was fetched and deleted on every sync, forever.
- Cards write pinyin grouped by word (`zhōuwǔ`, not `zhōu wǔ`), so `Pinyin.align` splits groups into syllables against an initials×finals inventory, using the character count to pick the split. Counting whitespace tokens instead rejected 60% of the deck's readings. Build the inventory structurally, never by CC-CEDICT frequency — that drops rare-but-real syllables like `zhei`.
- One syllable per character, except contracted erhua (这儿 is `zhèr`, two characters and one syllable — the 儿 gets a blank slot). That count check is also what proves the right field was read, so do not relax it: a mismatch anywhere in a sentence discards the reading for every word in it.
- Android's regex engine is ICU, not the JVM's, and rejects unescaped `}}` and `]` that unit tests accept. A pattern that passes `testDebugUnitTest` can still throw `PatternSyntaxException` on a device.
- A capital on the *first* word of a sentence is punctuation, not a proper noun, and it decides lookups: `Mǎ` matches CC-CEDICT's surname row exactly, so 马 resolved to "surname Ma" instead of "horse". `KnownWordIndex` lowercases it at `hanIndex == 0` only — mid-sentence capitals are real names.
- Token storage is Tink + DataStore. `EncryptedSharedPreferences` is deprecated — do not "simplify" back to it.

## UX Gotchas

- Speech prefers `zh-TW`/Meijia.
- Popup role is `status`, not `tooltip`, because it has buttons.
- Keep pinyin inline with characters; show definitions only when multiple matches.
- Do not darken contrast below current values: defs `#888` on `#0d0d0d`, icons `#999` on `#1a1a1a`.
- The settings screen leads with whether lookups actually work, read from `ENABLED_ACCESSIBILITY_SERVICES` on each resume (no callback exists). Without it the app looks identical whether or not it is functional — do not reduce this back to a plain "open settings" button.
- Every toggle carries supporting text. Labels alone cannot explain "Playful misses".
- The due notification shows characters only; the reading and meaning sit behind Reveal. Showing them upfront defeats recall practice.
- `setOngoing(true)` does not prevent dismissal on Android 14+. Persistence is the `deleteIntent` re-post, which stops at zero due.
- A posted notification stores its icon as a bare resource id, and adding drawables shifts ids — hence the re-post on `MY_PACKAGE_REPLACED`.
- Changing `android:icon` needs a device **reboot** to show up: `system_server` caches parsed manifest data per package. Restarting SystemUI does not clear it, and force-stopping SystemUI can destroy the user's wallpaper.
