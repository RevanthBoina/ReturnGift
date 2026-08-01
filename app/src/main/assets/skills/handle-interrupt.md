# Skill: handle_interrupt
version: 1.0
id: handle_interrupt
category: DISMISS
tools: get_screen_info, system_key, find_and_tap
user_facing: false
estimated_steps_saved: 3

## Purpose

A mid-task popup, permission dialog, incoming-call overlay, or notification
shade can appear at any point during a task. If a `screen_state` snapshot is
taken while such an overlay is active, the model may treat it as the real
target screen and take wrong actions (e.g. tapping "Allow" on an unrelated
permission, or treating an incoming-call screen as a navigation target).

This skill documents the decision rule the harness enforces before every
`screen_state` capture. It is NOT a LoRA retrain — it is a deterministic
harness guard implemented in `InterruptDetector.kt` + `InterruptConfig.kt`.

---

## Decision Rule

```
BEFORE every screen_state snapshot (pre-warm + Opt-3 post-action):

  inspect accessibility window stack:

    IF any window package is in AUTO_DISMISSABLE_PACKAGES
       OR any visible node text matches AUTO_DISMISS_TEXT_PATTERNS
    → AUTO_DISMISSABLE:
        pressBack() to clear overlay
        wait SCREEN_SETTLE_MS
        re-capture screen_state
        continue original task transparently

    ELSE IF any window package is in PAUSE_AND_CONFIRM_PACKAGES
         OR any visible node text matches PAUSE_TEXT_PATTERNS
    → PAUSE_AND_CONFIRM:
        halt pending tool call
        fire onSystemDialogBlocked() callback
        surface "task paused — screen changed unexpectedly" notice in UI
        require user tap to resume
        log event to AppLogStore

    ELSE
    → CLEAN: pass screen_state to model as normal
```

---

## Maintained Lists (edit InterruptConfig.kt, not this file)

### AUTO_DISMISSABLE packages
- `com.android.systemui` — status bar / notification shade / PiP controls
- `com.google.android.inputmethod.latin` — Gboard suggestion bar
- `com.samsung.android.honeyboard` — Samsung keyboard suggestion bar
- `android` — framework toasts
- Other IME packages (see `InterruptConfig.AUTO_DISMISSABLE_PACKAGES`)

### AUTO_DISMISSABLE text patterns
- "Copied to clipboard", "Screenshot saved", "Done", "Saved", "Sent"

### PAUSE_AND_CONFIRM packages
- `com.android.permissioncontroller` — runtime permission dialogs
- `com.google.android.permissioncontroller`
- `com.samsung.android.permissioncontroller`
- `com.android.server.telecom` — incoming call screen
- `com.google.android.dialer`, `com.samsung.android.incallui`
- `com.miui.securitycenter` — MIUI permission overlay

### PAUSE_AND_CONFIRM text patterns
- "Allow … to …" / "Grant … permission"
- "Allow" / "Deny" / "Don't allow" (standalone button labels)
- "Incoming call" / "Answer call" / "Decline call"
- "Battery optimization", "Unknown source", "Install … anyway"

---

## Adding New Entries

1. Open `InterruptConfig.kt`.
2. Add the package name to either `AUTO_DISMISSABLE_PACKAGES` or
   `PAUSE_AND_CONFIRM_PACKAGES`.
3. Or add a `Regex(...)` to `PAUSE_TEXT_PATTERNS` / `AUTO_DISMISS_TEXT_PATTERNS`.
4. No retrain needed. Change takes effect on next app launch.

---

## Logging

Every interrupt event writes one line to AppLogStore:

```
INTERRUPT [timestamp] type=<AUTO_DISMISSABLE|PAUSE_AND_CONFIRM> source=<package> action=<action> desc=<description>
```

Lines are included in the debug-report.zip exported from Settings → About →
Share Debug Report.

---

## Related files

- `InterruptDetector.kt` — detection logic
- `InterruptConfig.kt`   — all package/text lists (edit here to add entries)
- `DefaultAgentService.kt` — injection points (pre-warm + Opt-3 post-action)
- `golden_transcripts/interrupt_handling.jsonl` — regression test cases
