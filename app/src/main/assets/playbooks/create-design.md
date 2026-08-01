---
id: create_design
name: Create Design (Canva)
status: draft
triggers:
  - "create design"
  - "new canva"
  - "make design"
  - "canva"
---

When the user wants to create a design in Canva:

NOTE: Canva uses a WebView-based UI. Accessibility tree access is limited.
This playbook attempts best-effort navigation; if the WebView is not accessible,
it will notify the user to proceed manually.

1. **open_app** → call open_app(package_name="com.canva.editor")
2. **get_screen_info** → check if the editor or home screen is accessible
3. **find_and_tap** → tap "Create a design" or "+" button if visible
4. **find_and_tap** → tap the template type if specified (e.g. "Instagram Post", "Presentation")
5. **finish** → call finish(summary="Opened Canva for a new [template_type] design.")

If step 3 or 4 fails due to WebView inaccessibility:
- **finish** → call finish(summary="Canva is open. The editor uses a web view — please tap 'Create a design' manually.")

Extract from the user's request:
- template_type = type of design (e.g. "Instagram post", "presentation", "logo") — default "blank"
