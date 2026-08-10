# AGENTS.md

## Entry Points

- Chrome: `background.js` owns dictionary load/cache/lookup; `content.js` owns selection UI; `content/cedict_formatter.js` is a classic no-build helper loaded first.
- Chrome Traverse sync: `lib/traverse/` (auth, REST, sync orchestration, hanzi-only parser) -> `chrome.storage.local` mirror -> `lib/diglot.js` replacement table -> `content/diglot.js` page pass. Popup owns sign-in/status; a 6h alarm plus browser startup drive unforced syncs.
- Shared JS: `lib/normalize.js` handles lookup variants; `lib/pinyin.js` handles pinyin and CEDICT English key extraction.
- Dictionary: `npm run dict:build` (= `scripts/preprocess_cedict.js`) reads committed `cedict_ts.u8` + `subtlex_ch.tsv` and regenerates `cedict.json` + `dict_version.js`. Both are generated *and* committed. Android: `cd android && ./gradlew buildDictionary`.
- Android lookup: `TextSelectionService` -> `DictionaryRepository` -> overlay/TTS.
- Android Traverse sync: `traverse/` (auth, REST, orchestration) -> `data/` (Room mirror) -> `notification/DueNotifier`; driven by `work/SyncWorker`, `TraverseExitWatcher`, `NotificationRefreshReceiver`.
- Parity tests: browser and Android share cases from `testdata/` (`segmentation_cases.tsv` has no Android reader yet).

## Checks

- JS: `npm test && npm run lint`.
- Android: `cd android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew testDebugUnitTest lintDebug buildDictionary compileDebugAndroidTestKotlin`. `lintDebug` is green; keep it that way. It fails the build on errors, so a permission check has to be inline where lint can follow it rather than extracted into a helper. The instrumentation sources only compile as part of the last task — they are easy to break without noticing.
- `adb shell am force-stop com.mandopop` **revokes the accessibility grant** — the app comes back showing "Finish setup" and lookups are dead until it is re-enabled by hand. To restart for a test, launch the activity again or reinstall; never force-stop.
- Room DAO queries and migrations: `./gradlew connectedDebugAndroidTest` (needs a device). It **uninstalls the app afterwards**, taking the accessibility grant, the Traverse session and the local mirror with it. Follow it with `installDebug`, then re-enable the service and sign in again — never run it last against a device in use, and back the database up first (`adb exec-out run-as com.mandopop cat databases/mandopop.db > backup.db`) now that refilling it costs ~940 remote reads.

## Constraints

- **No content egress.** Text the user reads, types, or selects must never leave the device. No runtime lookup or translation may call a remote model or service.
- Both platforms may sync the user's own learning state (Traverse/Firebase). State sync, not content lookup — the only permitted runtime network use, on Android and in the extension (`lib/traverse/`). Lookups and Chinglish replacement are fully offline; downloading CC-CEDICT is a dev-time build step.
- One dictionary source, one parser: CC-CEDICT -> `preprocess_cedict.js` -> `cedict.json` -> Android SQLite. `build_dictionary.py` is a loader; nothing else may read `cedict_ts.u8`. Both platforms consume the same `cedict.json`, which is what stops English->Chinese drifting between them — `validate_lookup_content` fails the Android build if the SQLite projection disagrees.
- `subtlex_ch.tsv` is a ranking signal, not a dictionary. Removing it degrades ordering; it cannot break a lookup.
- Do not load the dictionary in `content.js`; use the service worker.
- Do not use `innerHTML` in content-script UI; use DOM APIs/`textContent`.
- Preserve no-build-step Chrome extension assumptions.
- Do not commit host-specific `org.gradle.java.home`; use inline `JAVA_HOME`.
- Settings listener uses `'key' in changes`, not `changes.key !== undefined`.

## Dictionary

- `cedict.json` (v2): `{ v, entries: [{s,p,d}], index: { "english key": [entryId] } }`. `entries` is complete — including entries no English key reaches, because Android looks up by hanzi. `index` holds ids, not repeated objects. Three modules know this shape — `lib/normalize.js#lookup`, `lib/diglot.js`, `lib/traverse/known_words.js` — change it in all three or not at all.
- `rankForKey` orders English->Chinese candidates: primary-sense gloss match, then SUBTLEX-CH frequency, then gloss length, then id. Sense *position* is load-bearing — accepting any matching sense promotes 门 for "school" and 牢 for "fast". Frequency replaced a hand-curated common-word list and a prefer-2-characters rule; do not reintroduce either.
- Reverse lookup is `lookupBySimplified`, backed by the `entries_simplified` index. It is ordered by row id, and CC-CEDICT puts surnames and cross-references first, so 花 resolves to `Huā` and 和 to "old variant of 和" unless a caller breaks the tie — `KnownWordIndex.preferredEntry` does, using CC-CEDICT's own labels. Sense *order within* an entry is unfixable from here: 号 lists "to call out" ahead of "day of the month".

## Traverse Sync

- Firestore reads bill to *Traverse's* project. A sync reads one document (`events/{today}`); the full deck is pulled only when that day's review count moves, the local day rolls over, or the mirror is 6h stale. Do not add unconditional full pulls.
- Day boundaries use `ZoneId.systemDefault()` deliberately: Traverse keys its daily doc by the client's local date, and its client is a WebView on the same phone. A fixed zone would break on travel.
- Card *ids* are opaque; card **field names are not**. Content sits in a nested `fields` map keyed by name, matched case-insensitively. `CardParser` picks a layout by **which fields a card has, never by its template name** — the course has 21 templates over 55,460 cards and three are named by meaningless slug (`cFEA3bL9RCnkfp8nSu9x`) while being ordinary movie or vocabulary cards. Signature matching reads all 21, and anything sharing a shape in future, without enumerating them. Verified against every card: 52,963 of 55,460 read, and the rest are Trash Bin, ACTOR, SET and Minimal Pairs, which carry no word by design.
- The whole course is one `cards` list query away (`userNames/Mandarin_Blueprint/cards?pageSize=300`, ~186 pages). Re-export it before guessing at a template — it is how the 30,000 Language Islands and TPV cards this account has not reached were mapped.
- **Names locate the pair; shape decides which is which.** On MSLK cards the course has `Chinese` and `Pinyin` swapped, and inconsistently — 130 of 160 sampled cards hold the hanzi under `Pinyin`. Whichever field carries Han characters is the Chinese. Never trust those two names.
- The card document carries its own `template`; prefer it over the schedule row's, since a card with two prompts has two rows and picking one is arbitrary.
- MSLK and MB Sentence cards store the **whole sentence**, segmented later — for MB Sentence that beats taking the `Word` it highlights by 1,203 distinct words across the course, because the word is inside the sentence anyway. MSLK's title is English and the Chinese is in the body. Not all of them are sentences though — 知道 is a one-word MSLK card, so `is_sentence` is decided by CC-CEDICT membership, never by template or by length. That flag is what lets the notification prompt with a card and Reveal look it up.
- ACTOR, SET and Minimal Pairs teach a pinyin sound and have no headword — extracting from them scrapes incidental hanzi out of mnemonics. PROP cards are *not* excluded: 一 and 十 are components and real words.
- Card content is backfilled for the **whole deck**, not just due cards, via `documents:batchGet` — 150 per request, sequential, 3 s apart. Immersion features need every word the user has met.
- `CardParser.VERSION` is what makes cached negatives safe: rows below it are stale, not done. Bump it on any extraction change and the next sync re-reads the deck. Without it a parse failure is permanent, which is how 55 of 211 rows ended up recorded as having no content.
- `known_words` is derived from `card_content` and rebuilt wholesale, never diffed — a word has to be able to *leave* when its lesson is suspended. It excludes anything CC-CEDICT knows only as a radical or stroke, and where cards disagree on a reading (个 is `ge` on thirty and `gè` on three) the majority wins, tie-broken by spelling: last-write-wins over an unordered query made twelve rows change value between runs.
- **Segmentation invents, it does not omit.** Longest match cuts 二十/个人 out of "twenty people" — a real CC-CEDICT entry across an intended boundary. The alternatives are measured and worse: backward and minimise-singles matching each fix ~5 cases and break ~60, mostly by destroying the number system (二十/三 → 二/十三); frequency-weighted is worse again. `Segmenter.NEVER_A_WORD_HERE` handles the fifteen that actually occur; nothing is taught as a headword anywhere in the course, so the list cannot remove a real word. Do not swap the algorithm without re-measuring.
- **Unused, and worth knowing before building the next feature.** MB Sentence (11,953 cards) marks the word it teaches inside its sentence as `==祭奠==` — a better target signal than segmenting. Every sentence template carries native audio URLs (`Chinese Audio`, `English Audio`, `AUDIO`) while lookups speak through TTS. `Tags` carries lesson and level (`MBMLEVEL44`, grammar patterns), which is the ordering any what-to-learn-next feature needs.
- A window event naming Traverse is **not** evidence the user returned to it — Traverse re-announces its window about a second after being backgrounded, every time. `TraverseExitWatcher` therefore only reports *leaving*; whether the user is back is decided by checking `rootInActiveWindow` when the settle timer fires. Cancelling on the event instead silently killed every on-exit sync.
- Swiping the due notification away forces a full pull, and is the only refresh gesture outside the settings screen. Unforced syncs are gated on the events heartbeat, which cannot see rescheduling or unsuspended lessons.
- Room is a cache of remote state, but a wipe is no longer free: refilling `card_content` is ~940 document reads on Traverse's project, not one. Every version bump from 3 on needs a real migration — the destructive fallback is scoped to version 1 only — v2 has a real migration — so a missing one throws at open instead of wiping (as does flashing an older build). It cannot be `(1, 2)`: naming a version a registered migration starts *from* makes Room reject the builder and the app dies on launch. Room exports its expected schema to committed `android/app/schemas/*.json`; paste migration DDL from there, because a mismatch throws at first DB access rather than at build time, and `MigrationTest` is what catches it.
- Any query with an opinion about ACTOR/SET cards must use the shared `SOUND_ONLY` predicate and apply it per *card*. When the fetch filter and the cleanup delete disagreed, a card with one ACTOR prompt and one other was fetched and deleted on every sync, forever.
- Cards write pinyin grouped by word (`zhōuwǔ`, not `zhōu wǔ`), so `Pinyin.align` splits groups into syllables against an initials×finals inventory, using the character count to pick the split. Counting whitespace tokens instead rejected 60% of the deck's readings. Build the inventory structurally, never by CC-CEDICT frequency — that drops rare-but-real syllables like `zhei`.
- One syllable per character, except contracted erhua (这儿 is `zhèr`, two characters and one syllable — the 儿 gets a blank slot). That count check is also what proves the right field was read, so do not relax it: a mismatch anywhere in a sentence discards the reading for every word in it.
- Android's regex engine is ICU, not the JVM's, and rejects unescaped `}}` and `]` that unit tests accept. A pattern that passes `testDebugUnitTest` can still throw `PatternSyntaxException` on a device.
- CC-CEDICT's own labels decide senses, not case or position. Lowercasing every sentence-initial capital fixed 马 and 花 but broke 周日, which CC-CEDICT itself capitalises as "Sunday". `KnownWordIndex.preferredEntry` skips a `surname `-labelled or cross-reference gloss (`variant of…`, `see …`) when a same-reading definition exists, and `canonicalise` makes the stored capital follow the dictionary rather than the card.
- Token storage is Tink + DataStore. `EncryptedSharedPreferences` is deprecated — do not "simplify" back to it.
- The extension is a second Traverse client and mirrors the phone's politeness exactly: heartbeat-gated pulls, 150-document sequential `batchGet` 3 s apart, the wipe/missing-document/broken-template guards, and the banked heartbeat. Do not let the two clients' gating drift, and do not add unconditional pulls here either.
- The extension carries a second card parser (`lib/traverse/card_parser.js`), hanzi-only: pinyin and glosses come from `cedict.json` at display time, so the pinyin-alignment and English halves are deliberately absent. Its `PARSER_VERSION` versions the extension's own cache, independent of android `CardParser.VERSION` — bump it on any JS extraction change. Segmentation parity is pinned by shared `testdata/segmentation_cases.tsv` (the Android reader for it does not exist yet). Any extraction change lands in both parsers or in neither.
- Replacement ("Diglot weave" in the UI, `diglot` in code) is decided entirely at sync time, offline: `buildReplacementMap` maps an English key only to a known word with an exact gloss for it (`exactGlossRank`) — the same sense-position risk `rankForKey` guards against. The content script is a dumb match against the finished table; page text has no path to the network.
- Extension tokens live plaintext in `chrome.storage.local` — MV3 has no keystore, and an in-extension encryption key would sit beside the data. Do not round-trip on "hardening" it.
- A dead refresh token signs the extension out but **keeps** the mirror and the weave running — expiry is involuntary, and the mirror costs ~940 billed reads to refill. Deliberate asymmetry with explicit sign-out, which wipes everything. The weave toggle sits outside the account panel for exactly this state.
- Every sync storage write is fenced on the account that started it (`guardedSet`); the empty-pull wipe guard compares against the *previous rows*, not success bookkeeping. Both close mid-drain windows — do not "simplify" them away.

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
- Diglot-weave spans show hanzi only; pinyin and the original word appear in the extension's own instant tooltip on hover — same recall philosophy as the notification. Never a native `title`: its fixed ~1s delay was tried and is deadly in the recall loop. All-caps tokens are never replaced (CAT scans stay CAT scans), and editable text, code, and `pre` are never rewritten.
- Rewriting text nodes can break framework-managed DOM (React's `removeChild` NotFoundError — the Google Translate failure). Isolated worlds cannot see framework internals, so there is no reliable detection; the accepted mitigation is the weave toggle. A crashed page is fixed by toggling off and reloading.
