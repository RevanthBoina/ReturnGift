# Skill Integration Analysis

## 19 YAML Skills → Existing Project Mapping

| # | YAML Skill ID | Status | Existing Equivalent | Action |
|---|--------------|--------|---------------------|--------|
| 1 | send_message | EXACT | playbook: send_message.md | Keep playbook, enhance with YAML detail |
| 2 | open_conversation | PARTIAL | playbook: send_message (triggers overlap) | Create new playbook |
| 3 | make_phone_call | MISSING | none | Create new playbook |
| 4 | ask_external_ai | MISSING | none | Create new playbook |
| 5 | web_search | PARTIAL | playbook: open_and_search.md | Enhance existing playbook |
| 6 | search_place | PARTIAL | playbook: open_and_navigate.md | Enhance existing playbook |
| 7 | book_ride | MISSING | none | Create new playbook |
| 8 | search_video | PARTIAL | playbook: open_and_search.md | Enhance existing playbook |
| 9 | search_install_app | MISSING | none | Create new playbook |
| 10 | set_alarm | MISSING | none | Create new playbook |
| 11 | navigate_settings | MISSING | none | Create new playbook |
| 12 | search_repository | MISSING | none | Create new playbook |
| 13 | create_design | MISSING | none | Create new playbook (draft status) |
| 14 | open_file_in_drive | MISSING | none | Create new playbook |
| 15 | create_video_project | MISSING | none | Create new playbook (draft) |
| 16 | open_app | EXACT | playbook: open_and_navigate.md | Keep playbook, enhance triggers |
| 17 | open_linkedin_feed | MISSING | none | Create new playbook |
| 18 | record_audio | MISSING | none | Create new playbook |
| 19 | view_assignments | MISSING | none | Create new playbook |

## New Playbooks to Create (12):
- open_conversation.md
- make_phone_call.md
- ask_external_ai.md
- book_ride.md
- search_install_app.md
- set_alarm.md
- navigate_settings.md
- search_repository.md
- create_design.md
- open_file_in_drive.md
- create_video_project.md
- open_linkedin_feed.md
- record_audio.md
- view_assignments.md

## Playbooks to Enhance (3):
- send_message.md (add intent routes, verification)
- open_and_search.md (add web_search, search_video, search_repo triggers)
- open_and_navigate.md (add search_place, open_app triggers)

## Built-in Skills to Consider (for deterministic execution):
- Some YAML skills have UI automation routes that could become BuiltInSkills.kt entries
- But playbooks are the primary mechanism for local LLM
