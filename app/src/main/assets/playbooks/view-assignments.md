---
id: view_assignments
name: View Assignments
triggers:
  - "assignments"
  - "homework"
  - "classroom"
  - "what's due"
  - "pending assignments"
---

When the user wants to see their Google Classroom assignments:

1. **open_app** → call open_app(package_name="com.google.android.apps.classroom")
   - Alternatively fire deep link: https://classroom.google.com/u/0/a/not-turned-in/all
2. **get_screen_info** → confirm the Classroom feed or To-do list is visible
3. **find_and_tap** → tap the "To-do" tab if not already on it
4. **get_screen_info** → read the list of assignments (title, due date, course)
5. **finish** → call finish(summary="You have [N] assignments due. Next: [title], due [date].")

Extract from the user's request:
- class_name = optional specific class to filter by
- filter = "assigned" (default) | "missing" | "done"

If no assignments are found: finish(summary="Nothing due in Classroom right now.")
- Good: `show my assignments`, `what homework is due`, `check classroom to-do`
- Not this playbook: `open classroom` → open-and-navigate, `submit assignment` (unsupported)
