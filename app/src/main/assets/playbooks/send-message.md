---
id: send_message
name: Send Message
triggers:
  - "send"
  - "tell"
  - "reply"
  - "message"
  - "text"
  - "dm"
  - "snap"
  - "forward"
---

When the user wants to send a message to someone else:

1. **send_message** → call send_message(contact="[person name]", message="[what to say]", app="[app name, default WhatsApp]")
2. **finish** → call finish(summary="Sent '[message]' to [contact] on [app]")

Extract from the user's request:
- contact = the person's name (e.g. "Mom", "Girlfriend", "John")
- message = what to send (e.g. "hi", "I'll be late", "今晚返屋企食飯")
- app = which app (default "WhatsApp" if not specified)
  - "telegram" / "tg" → org.telegram.messenger
  - "snapchat" / "snap" → com.snapchat.android
  - "slack" → com.Slack
  - "sms" / "text" / "messages" → com.google.android.apps.messaging
  - "whatsapp" / "wa" → com.whatsapp (default)

Verification: after sending, confirm the message bubble appears in the thread.
Safety: never send messages containing OTP, CVV, PIN, or password text.

Only use this playbook when the user clearly wants delivery to another person.
- Good: `send hi to Mom`, `tell Alice I'll be late`, `dm John on Slack`, `snap Alice a message`
- Not this playbook: `say hi`, `open Alice's chat` → open-conversation, `call Alice` → make-phone-call
