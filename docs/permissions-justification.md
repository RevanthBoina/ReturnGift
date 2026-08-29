# Permissions Justification

This document lists every Android permission declared in `app/src/main/AndroidManifest.xml`
and the code path that legitimately requires it. It is the source of truth for the
"permissions this app uses" table rendered in Settings → Privacy.

## Runtime permissions

| Permission | Why needed | Code path |
|---|---|---|
| `INTERNET` | Cloud LLM calls (optional) + channel webhooks (Telegram/Discord) | `agent/llm/LlmClientFactory.kt`, `tool/impl/TelegramTool.java`, `tool/impl/DiscordTool.java` |
| `WAKE_LOCK` | Keep the CPU alive during long device-automation tasks | `service/ForegroundService.kt` |
| `POST_NOTIFICATIONS` | Heads-up notifications for clarifications + task completion | `service/ClarificationNotifier.java` |
| `SYSTEM_ALERT_WINDOW` | Floating task-progress pill overlay | `ui/overlay/FloatingCircleManager.kt` |
| `FOREGROUND_SERVICE` | Background agent execution | `service/ForegroundService.kt` |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Same as above, special-use variant | `service/ForegroundService.kt` |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent the OS from killing long-running tasks | `service/ForegroundService.kt` — startup check |
| `READ_PHONE_STATE` | Phone number for `make_call`/`sms` Tier-1 intents | `tool/impl/MakeCallTool.java`, `tool/impl/SendSmsTool.java` |
| `READ_CONTACTS` | Resolve contact names to numbers in `send_message` | `tool/impl/ContactListUiUtils.java` |
| `READ_EXTERNAL_STORAGE` | Read downloaded images / PDFs for import | `tool/impl/ImportDownloadTool.java` |
| `WRITE_EXTERNAL_STORAGE` | Save screenshots / vault artifacts to gallery | `tool/impl/SaveToGalleryTool.java` |
| `MANAGE_EXTERNAL_STORAGE` | Legacy fallback for API 28 download-folder discovery | `tool/impl/ImportDownloadTool.java:95` — only when `Build.VERSION.SDK_INT < 29` AND user triggered the import |
| `REQUEST_INSTALL_PACKAGES` | Install dev-channel OTA APKs | `dev/AppUpdateManager.kt` |
| `ACCESS_NETWORK_STATE` | Check connectivity before web_search/web_fetch | `tool/impl/WebSearchTool.java`, `tool/impl/WebFetchTool.java` |
| `ACCESS_WIFI_STATE` | Same as above | `tool/impl/WebSearchTool.java`, `tool/impl/WebFetchTool.java` |
| `QUERY_ALL_PACKAGES` | List installed apps for `get_installed_apps` | `tool/impl/GetInstalledAppsTool.java` |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot | `service/BootReceiver.kt` |
| `SCHEDULE_EXACT_ALARM` | Schedule alarms/timers | `tool/impl/ScheduleAlarmTool.java` |
| `USE_EXACT_ALARM` | Same as above | `tool/impl/ScheduleAlarmTool.java` |
| `BLUETOOTH` | Legacy Bluetooth adapter state reporting only — **no Bluetooth API calls**; `get_device_info(category=bluetooth)` reports a static string | `agent/guard/DirectDeviceDataGuard.kt:105` |
| `BLUETOOTH_CONNECT` | **REMOVED** — was unused | — |
| `BLUETOOTH_SCAN` | **REMOVED** — was unused | — |

## Manifest-level features

| Feature | Why needed |
|---|---|
| `android.hardware.touchscreen` (required) | Android handheld requirement |

## Manifest drift guard

The CI check `manifest-drift` (in `scripts/ci-preflight.sh`) parses this table and
compares it against `AndroidManifest.xml`. If a permission is added to the manifest
without a corresponding row here, the check fails. If a row here has no matching
manifest permission, the check fails.

## Removals

- `BLUETOOTH_CONNECT` — was never used (no `BluetoothManager` calls in the codebase).
- `BLUETOOTH_SCAN` — was never used. Only `BLUETOOTH` remains, and it is now
  reported as a static string (not an actual Bluetooth API call).