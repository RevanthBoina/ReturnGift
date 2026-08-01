---
id: create_video_project
name: Create Video Project (InShot)
triggers:
  - "create video"
  - "edit video"
  - "inshot"
  - "new video project"
---

When the user wants to start a video project in InShot:

1. **open_app** → call open_app(package_name="com.camerasideas.instashot")
2. **get_screen_info** → confirm InShot home screen is visible
3. **find_and_tap** → tap "Video" to start a new video project
4. **get_screen_info** → wait for the media picker or editor to load
5. **finish** → call finish(summary="Opened InShot for a new video project.")

If an ad overlay appears at any step:
- **find_and_tap** → tap the close/X button on the ad before continuing

Extract from the user's request:
- media_path = optional specific video file to edit (if mentioned)

Note: This is a long-running stateful workflow. If interrupted, InShot saves drafts automatically.
- Good: `create a new video in InShot`, `edit my video using InShot`, `start a video project`
- Not this playbook: `open InShot` → open-and-navigate
