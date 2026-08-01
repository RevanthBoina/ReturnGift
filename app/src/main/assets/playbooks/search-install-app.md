---
id: search_install_app
name: Search / Install App
triggers:
  - "install"
  - "download app"
  - "find app"
  - "play store"
  - "get app"
---

When the user wants to find or install an app from the Play Store:

1. **open_app** → call open_app(package_name="com.android.vending")
2. **get_screen_info** → confirm Play Store is open
3. **find_and_tap** → tap the Search bar
4. **input_text** → call input_text(text="[app name]")
5. **system_key** → call system_key(key="enter")
6. **get_screen_info** → read the top result (developer name, rating)
7. **confirm** → ask user: "Found [app name] by [developer]. Install?"
8. **find_and_tap** → tap "Install" only after user confirms
9. **finish** → call finish(summary="Installing [app name]...")

Extract from the user's request:
- app_name = the app to find/install

- Good: `install Spotify`, `find Duolingo on Play Store`, `download WhatsApp`
- Not this playbook: `open Spotify` → open-and-navigate
