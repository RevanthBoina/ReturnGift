---
id: open_conversation
name: Open Conversation
triggers:
  - "open chat"
  - "show conversation"
  - "go to chat"
  - "navigate to chat"
  - "open messages"
---

When the user wants to open an existing chat thread (not send a message):

1. **open_app** → call open_app(package_name="[app package]") to open the messaging app
2. **get_screen_info** → confirm the chat list is visible
3. **find_and_tap** → call find_and_tap(text="[contact name]") to open the conversation
4. **finish** → call finish(summary="Opened conversation with [contact] in [app]")

Extract from the user's request:
- contact = the person or group name (e.g. "Mom", "Team Alpha")
- app = which app (default "WhatsApp" → com.whatsapp; "Telegram" → org.telegram.messenger)

Use this playbook when the user wants to *view* a chat, not send a message.
- Good: `open my chat with Alice`, `show conversation with Mom`, `go to the Team chat`
- Not this playbook: `send hi to Alice` → use send-message playbook
