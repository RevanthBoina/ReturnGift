---
id: open_file_in_drive
name: Open File in Drive
triggers:
  - "open drive"
  - "find file"
  - "google drive"
  - "open document"
  - "search drive"
---

When the user wants to find or open a file in Google Drive:

1. **open_app** → call open_app(package_name="com.google.android.apps.docs")
2. **get_screen_info** → confirm Drive home with search bar is visible
3. **find_and_tap** → tap the "Search in Drive" bar
4. **input_text** → call input_text(text="[filename]")
5. **system_key** → call system_key(key="enter")
6. **get_screen_info** → read the results
7. **find_and_tap** → tap the best-matching file name
8. **finish** → call finish(summary="Opened '[filename]' from Drive.")

Extract from the user's request:
- filename = the file or document name to find

If multiple matches appear, ask: "Which file? I see: [list of names]"
- Good: `open my budget spreadsheet in drive`, `find the project proposal on Google Drive`
- Not this playbook: `open drive` (no file specified) → open-and-navigate, `create a new document` (unsupported)
