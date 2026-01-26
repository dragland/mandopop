# Mandarin Learning Chrome Extension - Specification

## Problem Statement

A complete beginner learning Mandarin wants to seamlessly learn vocabulary while browsing the web. When selecting English text, they want to instantly see the Chinese translation with proper pinyin (tone marks) and optionally hear the pronunciation.

## Core Requirements

### Functional Requirements

1. **Text Selection Trigger**
   - Popup appears automatically when user selects/highlights text on any webpage
   - Works on all websites (no blacklist/whitelist)
   - Popup dismisses when clicking outside or selecting new text

2. **Translation Display**
   - Show simplified Chinese characters
   - Show pinyin with tone marks (mā má mǎ mà format)
   - For words with multiple meanings, list all translation options
   - Show "No translation found" for untranslatable terms (proper nouns, slang, etc.)
   - Translate phrases as natural units (not word-by-word)

3. **Audio Pronunciation**
   - Button to play audio pronunciation
   - Use best available free TTS (browser speech synthesis with Chinese voice)
   - Requires internet connectivity

4. **Popup Positioning**
   - Appears near the selected text
   - Does not show the English source word (visible in selection already)

5. **Settings Panel** (via toolbar icon)
   - Toggle extension on/off
   - Toggle audio button on/off
   - Adjust Chinese character font size

### Non-Functional Requirements

- **Platform**: Chrome browser, Manifest V3
- **Distribution**: Personal use (unpacked extension)
- **Data Source**: CC-CEDICT (free, accurate, bundled with extension)
- **Offline Capability**: Translation works offline (bundled dictionary), audio requires internet
- **Cost**: $0 (no paid APIs or services)

## Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Manifest version | V3 | Future-proof, Chrome's current standard |
| Dictionary | CC-CEDICT (bundled) | Free, comprehensive, accurate, no API limits |
| Audio | Web Speech API | Free, built into browser, good Chinese support |
| Character set | Simplified only | User's learning target |
| Pinyin format | Tone marks | Academic standard, visually intuitive |

## Architecture Overview

```
mandopop/
├── manifest.json          # Extension config (MV3)
├── content.js             # Injected into pages, handles selection & popup
├── background.js          # Service worker for extension lifecycle
├── popup.html/js          # Settings panel UI
├── styles.css             # Popup styling
├── cedict.json            # CC-CEDICT dictionary (preprocessed)
└── icons/                 # Extension icons
```

### Key Components

1. **Content Script** (`content.js`)
   - Listens for `mouseup` events to detect text selection
   - Looks up selection in dictionary
   - Renders popup near selection
   - Handles audio playback via Web Speech API

2. **Service Worker** (`background.js`)
   - Manages extension state (enabled/disabled)
   - Handles settings persistence via `chrome.storage`

3. **Settings Popup** (`popup.html`)
   - Simple UI for toggle, audio toggle, font size slider
   - Saves to `chrome.storage.sync`

4. **Dictionary Data** (`cedict.json`)
   - Pre-processed CC-CEDICT in JSON format
   - Keyed by English words for fast lookup
   - ~5-10MB size (acceptable for personal use)

## UI Mockup

```
┌─────────────────────────┐
│ 你好                     │  ← Simplified Chinese (configurable font size)
│ nǐ hǎo                  │  ← Pinyin with tone marks
│ [🔊]                    │  ← Audio button (optional)
│─────────────────────────│
│ hello; hi               │  ← English definitions (for multiple meanings)
└─────────────────────────┘
```

For words with multiple meanings:
```
┌─────────────────────────┐
│ 银行 yínháng            │  ← First meaning
│ bank (financial)        │
│─────────────────────────│
│ 河岸 hé'àn              │  ← Second meaning
│ bank (of river)         │
│─────────────────────────│
│ [🔊]                    │
└─────────────────────────┘
```

## Edge Cases & Error Handling

| Scenario | Behavior |
|----------|----------|
| No translation found | Show "No translation found" message |
| Selection is empty/whitespace | No popup appears |
| Selection is very long (>100 chars) | Truncate or skip (likely not a lookup intent) |
| Audio fails to play | Silently fail, button does nothing (no error popup) |
| Extension disabled via settings | Selection does nothing |
| Popup would go off-screen | Reposition to stay visible |

## Open Questions (Resolved)

- ~~Trigger method~~ → Auto on selection
- ~~Multi-word handling~~ → Phrase translation
- ~~Character set~~ → Simplified only
- ~~Pinyin format~~ → Tone marks
- ~~Audio source~~ → Web Speech API

## Success Criteria

1. Select any English word → See Chinese + pinyin instantly
2. Click audio button → Hear pronunciation
3. Works on any website without breaking page functionality
4. Settings persist across browser sessions
5. Extension loads in <1s, popup appears in <100ms

## Complexity Assessment

**Difficulty: Moderate** - This is a straightforward extension for an experienced engineer:
- No complex APIs or authentication
- Well-documented Chrome extension patterns
- Main challenges: dictionary preprocessing, popup positioning edge cases
- Estimated file count: ~6-8 files
- No backend required
