---
id: navigate_settings
name: Navigate Settings
triggers:
  - "open settings"
  - "go to settings"
  - "settings"
  - "wifi settings"
  - "bluetooth settings"
---

When the user wants to open a specific settings section:

1. **open_app** → fire the appropriate Settings intent for the section:
   - "wifi" → android.settings.WIFI_SETTINGS
   - "bluetooth" → android.settings.BLUETOOTH_SETTINGS
   - "display" → android.settings.DISPLAY_SETTINGS
   - "sound" / "volume" → android.settings.SOUND_SETTINGS
   - "battery" → android.settings.BATTERY_SAVER_SETTINGS
   - "apps" → android.settings.APPLICATION_SETTINGS
   - "notifications" → android.settings.APP_NOTIFICATION_SETTINGS
   - "privacy" → android.settings.PRIVACY_SETTINGS
   - "location" → android.settings.LOCATION_SOURCE_SETTINGS
   - "security" → android.settings.SECURITY_SETTINGS
   - "about" / "device info" → android.settings.DEVICE_INFO_SETTINGS
   - "developer options" → android.settings.APPLICATION_DEVELOPMENT_SETTINGS
   - "date" / "time" → android.settings.DATE_SETTINGS
   - "language" → android.settings.LOCALE_SETTINGS
   - "storage" → android.settings.INTERNAL_STORAGE_SETTINGS
   - "accessibility" → android.settings.ACCESSIBILITY_SETTINGS
   - generic "settings" → com.android.settings main screen
2. **finish** → call finish(summary="Opened [section] settings.")

- Good: `open wifi settings`, `go to display settings`, `open about phone`
- Not this playbook: `turn on wifi` → toggle action via accessibility service
