# fixtures/

ADB-captured accessibility tree XML files used for skill grounding and staleness detection.

**Status: COMPLETE — 20 fixture files committed with SHA-256 hashes.**

Source: https://github.com/RevanthBoina/google-s-dataset/tree/main/screen_xml

These files were captured from SM-S918B, Android 14, OneUI 6, 1080×2340.

After committing each file, generate its SHA-256 hash and backfill the `tree_hash` field
in `skill_definitions_v2.yaml`:

```
sha256sum fixtures/screen_N.xml
```

## Required files

| File | Skill(s) | App |
|------|----------|-----|
| screen_1.xml | send_message, open_conversation | WhatsApp chat list |
| screen_2.xml | open_linkedin_feed | LinkedIn feed |
| screen_3.xml | ask_external_ai | ChatGPT home |
| screen_4.xml | navigate_settings | Android Settings |
| screen_12.xml | make_phone_call | Samsung Dialer |
| screen_14.xml | web_search | Chrome NTP |
| screen_15.xml | open_file_in_drive | Google Drive home |
| screen_16.xml | search_install_app | Play Store home |
| screen_17.xml | view_assignments | Google Classroom |
| screen_18.xml | search_place | Google Maps |
| screen_24.xml | send_message | Google Messages list |
| screen_25.xml | set_alarm | Samsung Clock alarm tab |
| screen_27.xml | book_ride | Rapido home |
| screen_30.xml | create_video_project | InShot home |
| screen_31.xml | send_message | Telegram list |
| screen_32.xml | send_message | Snapchat feed |
| screen_33.xml | search_video | YouTube player |
| screen_34.xml | record_audio | Samsung Voice Recorder |
| screen_35.xml | create_design | Canva editor |
| screen_36.xml | search_repository | GitHub home |
| screen_39.xml | open_app | Samsung Launcher |

## Capture command

```bash
adb shell uiautomator dump /sdcard/screen_N.xml
adb pull /sdcard/screen_N.xml fixtures/screen_N.xml
sha256sum fixtures/screen_N.xml
```

Source dataset reference: https://github.com/RevanthBoina/google-s-dataset/tree/main/screen_xml
