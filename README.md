# ReturnGift — On-Device Android Agent Harness

ReturnGift is a private/internal Android app for AI phone automation. It runs a single on-device foundation model (LiteRT-LM) with LoRA-based Action Skills. There is no cloud fallback — all task execution is device-resident.

## Architecture

ReturnGift is a generic Android mobile-agent harness:

- A generic tool layer for phone control (tap, swipe, type, open app, send message, etc.)
- A task/runtime loop that lets the on-device model choose and chain tools
- LoRA-based Action Skills loaded as SkillOpt Skill docs (no cloud routing)
- A product shell on top for usability

The agent reads the accessibility tree (live UI state), picks tools, executes, observes the result, and repeats until the task is complete. All of this runs inside the installed APK, using Android Accessibility Service, Notification Access, and foreground service.

## Key Design Decisions

- **Single on-device model, no cloud fallback.** All inference runs via LiteRT-LM on the device. No API keys, no network required for task execution.
- **LoRA-based Action Skills.** New task sequences are added as SkillOpt Skill docs (markdown), not model retrains. The base model handles tool selection; Skills provide structured guidance for known task families.
- **Generic tools.** 21 tools (tap, swipe, input_text, open_app, send_message, get_screen_info, etc.) work with any app, any screen, any language.
- **No new tool-primitive sequences require a retrain.** Per the project's rule: new sequences → Skill doc, not LoRA update.

## Tools

| Tool | What it does |
|------|-------------|
| `tap` / `swipe` / `long_press` | Touch the screen |
| `input_text` | Type into any text field |
| `open_app` | Launch any installed app |
| `send_message` | Full messaging flow |
| `get_screen_info` | Read current UI tree |
| `take_screenshot` | Capture screen |
| `finish` | Signal task completion |

## Requirements

| | Minimum |
|---|---|
| **Android** | 9+ (API 28+) |
| **Architecture** | arm64 |
| **RAM** | 8 GB |

## Attribution

ReturnGift is derived from the ReturnGift project. See `NOTICE` for full upstream attribution as required by Apache License 2.0.
