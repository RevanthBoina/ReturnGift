---
id: open_linkedin_feed
name: Open LinkedIn Feed
triggers:
  - "linkedin"
  - "linkedin feed"
  - "linkedin notifications"
  - "check linkedin"
---

When the user wants to open their LinkedIn feed or notifications:

1. **open_app** → call open_app(package_name="com.linkedin.android")
   - Alternatively fire deep link: linkedin://feed
2. **get_screen_info** → confirm the feed is visible
3. **finish** → call finish(summary="Opened LinkedIn feed.")

- Good: `show my LinkedIn feed`, `check LinkedIn notifications`, `what's new on LinkedIn`
- Not this playbook: `open LinkedIn` (generic) → open-and-navigate, `show system notifications` (different)
