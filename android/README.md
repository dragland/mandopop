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
2. Tap `Open Accessibility Settings`.
3. Enable `Mandopop`.

On recent Android versions, sideloaded accessibility services may be
blocked by Restricted Settings. If Mandopop is disabled or cannot be toggled on,
open Android Settings -> Apps -> Mandopop, then allow restricted settings from
the app details menu before returning to Accessibility Settings.

No `Display over other apps` permission is needed because the overlay is an
accessibility overlay owned by the service.

The service observes selected text across apps while enabled. It does not request
network access and does not store selected text.

Settings defaults:

- `Enable Lookups`: on. Soft-disables selection handling without disabling the
  Android accessibility service.
- `Audio Button`: on. Shows the pronunciation button in result cards.
- `Playful Misses`: on. Unknown selections show a random Mandarin miss card;
  turning this off makes unknown selections dismiss silently.
- `Chinese Size`: 24sp by default.

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
6. Toggle `Enable Lookups`, `Audio Button`, `Playful Misses`, and Chinese font
   size; reselect text and confirm the overlay follows the saved setting.
7. Tap pronunciation repeatedly and confirm speech starts, restarts, and stops
   cleanly when the service is disabled.
