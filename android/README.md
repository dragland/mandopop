# Mandopop Android

Sideload-only Android version of Mandopop. It uses an `AccessibilityService` to
watch text selection events, shows a bottom overlay with dictionary results, and
plays Mandarin pronunciation through Android TextToSpeech.

## Build

Requires:

- Java 17
- Python 3
- Android SDK with accepted licenses

```bash
cd android
./gradlew assembleDebug
```

The dictionary asset is generated automatically from `../cedict.json` by:

```bash
./gradlew buildDictionary
```

Gradle writes the generated SQLite asset and checksum under
`app/build/generated/assets/dictionary/`; they are build outputs, not checked-in
source assets.

## Test

```bash
cd android
./gradlew testDebugUnitTest lintDebug
```

Device-only behavior is covered by the manual smoke test below.

## Install

Connect an Android device over USB with USB debugging enabled and authorized,
then run:

```bash
cd android
./gradlew installDebug
```

After install, enable the service manually:

1. Open Mandopop.
2. Tap `Turn on Mandopop` on the setup card.
3. Enable `Mandopop` in the list.

The setup card is replaced by a `Ready` line once the service is on.

On recent Android versions, sideloaded accessibility services may be
blocked by Restricted Settings. If Mandopop is disabled or cannot be toggled on,
open Android Settings -> Apps -> Mandopop, then allow restricted settings from
the app details menu before returning to Accessibility Settings.

No `Display over other apps` permission is needed because the overlay is an
accessibility overlay owned by the service.

The service observes selected text across apps while enabled. Selected text is
never stored and never leaves the device.

The app does request `INTERNET`, used solely by Traverse sync (below). No text the
user reads or types is ever sent anywhere.

## Traverse sync

Optional. Signs in to the user's own [Traverse](https://traverse.link) account and
mirrors their spaced-repetition state locally, driving an ongoing "cards due today"
notification and supplying the vocabulary the user actually knows.

Sign in from the app's settings screen. The password is exchanged once for a refresh
token, which Tink encrypts under an Android Keystore master key before it is written to
DataStore; the password itself is never persisted. Sign Out clears the token, the local mirror, and the notification.

- Syncs on a periodic worker, on leaving the Traverse app, on app open, on swiping the
  due notification away, and after an app update. A routine sync is one Firestore read — the full deck is pulled only when
  the day rolls over, the day's review count changes, or the mirror is over 6h stale.
- The notification shows the bare hanzi of a due card, with a `Reveal` action for the
  reading and meaning. It is silent and cannot be swiped away while cards are due. Once
  the queue is empty it disappears — unless a daily briefing is available, in which case
  a dismissable 复习完了 ✓ line with the briefing sentence takes its place.
- Local state lives in `mandopop.db` and is a cache of remote state, so a schema change
  with no written migration drops it and the next sync refills it. `card_content` is the
  exception and now has a real migration: refilling it costs ~940 reads on Traverse's
  project, so it is no longer cheap to throw away.

mandopop is an unofficial client using the user's own credentials. A schema change on
Traverse's side breaks sync, which is why the integration is kept thin and confined to
`traverse/`.

The Firebase web API key in `TraverseAuth` is a project identifier, not a credential — Google
serves it in the clear to every web client and it authorises nothing by itself. No secret is
needed to build or run this repo.

## Daily briefing

One short Chinese sentence about the day, shown in the due notification's expanded view
and regenerated when the notification shade is pulled down.

- Inputs: today's remaining calendar events (`READ_CALENDAR`), the notifications
  currently in the shade (notification access, granted in system settings via the
  Daily briefing panel), and a rolling snapshot of the foreground app's text from the
  accessibility tree. All three are read at generation time and stored nowhere.
- Composed on-device with slot-filled templates as fallback; every candidate sentence is
  segmented and checked against the known-words index before it is shown. Nothing read
  from the calendar, the shade, or the screen ever leaves the phone. Two runtimes, picked
  by the model file in the app's `models/` dir: a `.gguf` runs through llama.cpp
  (current daily driver: Qwen3.5-2B, 1.34GB, ~3s a sentence) and wins over a `.litertlm`
  (Gemma via LiteRT-LM). Models are pushed once at dev time — the Daily briefing panel
  prints the exact `adb push` target when none is installed.
- The settings panel doubles as a test bench: model status, a "Generate now" button,
  "Bench ×8" (seeded fixture briefings scored by the verifier — pass rate and latency),
  and the raw model output with every verifier rejection.

Lookups run whenever the accessibility service is on. There is deliberately no
in-app switch for them — the service is the one control, and a second one would
only let the app claim to be running while doing nothing.

Settings defaults:

- `Pronunciation`: on. Shows the pronunciation button in result cards.
- `Playful misses`: on. Unknown selections show a random Mandarin miss card;
  turning this off makes unknown selections dismiss silently.
- `Hanzi size`: 24sp by default.

## Manual Smoke Test

After each install on a test device:

1. Enable the accessibility service and confirm Android does not ask for
   `Display over other apps`.
2. Select `hello`, `ice cream`, `cats.`, and `running.` in Chrome or another
   selectable text app; confirm the overlay appears after the debounce and shows
   the expected dictionary result.
3. Select an unknown English word; confirm the playful miss card appears when
   enabled and no card appears when disabled in Mandopop settings.
4. Select Chinese text, numbers-only text, and a long paragraph over 100
   characters; confirm the overlay dismisses or stays hidden.
5. Tap outside the card, switch apps, rotate the phone, and disable the service;
   confirm no stale overlay remains.
6. Toggle `Pronunciation`, `Playful misses`, and hanzi size; reselect text and
   confirm the overlay follows the saved setting.
7. Tap pronunciation repeatedly and confirm speech starts, restarts, and stops
   cleanly when the service is disabled.
8. Grant Calendar and Notification access in the Daily briefing panel, tap
   `Generate now`, and confirm a sentence appears with its debug readout; pull
   down the shade and confirm the notification's expanded view carries it. Clear
   all due cards and confirm the dismissable 复习完了 ✓ line replaces the
   due notification. Grant Usage access and confirm 今天学了 N 分钟 joins the stats
   line; tap Speak and confirm the sentence is spoken; tap Bench ×8 and confirm a
   pass-rate readout.
