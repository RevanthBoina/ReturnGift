---
id: record_audio
name: Record Audio
triggers:
  - "record audio"
  - "voice note"
  - "start recording"
  - "record voice"
  - "voice recorder"
---

When the user wants to record audio or a voice note:

1. **confirm** → ask user: "Start recording audio?"
2. **open_app** → call open_app(package_name="com.sec.android.app.voicenote")
   - Fallback: fire intent android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION
3. **get_screen_info** → confirm recorder is visible; dismiss any ad overlay if present
4. **find_and_tap** → tap the Record button (content-desc "Record" or "Start recording")
5. **finish** → call finish(summary="Recording started. Say 'stop recording' when done.")

Safety: requires RECORD_AUDIO permission. A visible recording indicator must be present.
- Good: `start recording audio`, `record a voice note`, `open voice recorder`
- Not this playbook: `open recorder` (no recording intent) → open-and-navigate, `play the recording` (unsupported)
