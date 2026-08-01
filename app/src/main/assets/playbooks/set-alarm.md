---
id: set_alarm
name: Set Alarm
triggers:
  - "set alarm"
  - "wake me"
  - "alarm for"
  - "create alarm"
---

When the user wants to set an alarm:

1. **set_alarm** → call set_alarm(hour=[H], minute=[M], label="[label]", skip_ui=true)
   - This fires the system SET_ALARM intent directly — no UI navigation needed
   - Parse time from natural language: "7:30 AM" → hour=7, minute=30; "in 30 minutes" → compute from now
2. **finish** → call finish(summary="Alarm set for [time] ([label]).")

Extract from the user's request:
- time = when to alarm (e.g. "7:30 AM", "6 PM", "in 45 minutes")
- label = optional label (e.g. "Meeting", "Gym") — default "Alarm"
- days = optional repeat days (e.g. "every weekday")

- Good: `set an alarm for 7 AM`, `wake me up at 6:30`, `alarm for 9 PM labeled Gym`
- Not this playbook: `set a timer for 10 minutes` (different intent), `open clock app` → open-and-navigate
