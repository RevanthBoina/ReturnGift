---
id: open_and_navigate
name: Open App and Do Something
triggers:
  - "open"
  - "go to"
  - "check"
  - "navigate"
  - "find place"
  - "directions"
  - "launch"
---

When the user wants to open an app and do something inside it:

1. **open_app** → call open_app(package_name="[app]") to open the app FIRST
2. **get_screen_info** → see what's on screen
3. Do what the user asked (tap, scroll, read, etc.) using the appropriate tool
4. **finish** → call finish(summary="[what you found or did]")

Common app mappings:
- "email" or "gmail" → open_app(package_name="com.google.android.gm")
- "youtube" → open_app(package_name="com.google.android.youtube")
- "chrome" or "browser" → open_app(package_name="com.android.chrome")
- "camera" → open_app(package_name="com.android.camera2")
- "settings" → open_app(package_name="com.android.settings")
- "maps" / "google maps" → open_app(package_name="com.google.android.apps.maps")
- "rapido" → open_app(package_name="com.rapido.passenger")
- "linkedin" → open_app(package_name="com.linkedin.android")
- "github" → open_app(package_name="com.github.android")
- "drive" / "google drive" → open_app(package_name="com.google.android.apps.docs")
- "classroom" → open_app(package_name="com.google.android.apps.classroom")
- "inshot" → open_app(package_name="com.camerasideas.instashot")
- "canva" → open_app(package_name="com.canva.editor")
- For any other app → call get_installed_apps(keyword="[app name]") first to find the package name

Navigation sub-cases:
- "navigate to [place]" / "directions to [place]" → use search-place playbook instead
- "find [place] on maps" → use search-place playbook instead

IMPORTANT: Always open the app FIRST. Never act on the current screen if the user wants you in a different app.
