---
id: make_phone_call
name: Make Phone Call
triggers:
  - "call"
  - "dial"
  - "phone"
  - "ring"
---

When the user wants to place a voice call:

1. **make_call** → call make_call(phone_number="[number or contact]")
   - If a contact name is given, resolve it first via get_device_info or contacts lookup
   - Always confirm with the user before dialling: "Call [name/number]?"
2. **finish** → call finish(summary="Calling [target]...")

Extract from the user's request:
- target = contact name or phone number (e.g. "Mom", "+91 98765 43210")

Safety: never dial premium-rate numbers (0900, 1900). Confirm before every call.
- Good: `call Mom`, `dial 9876543210`, `ring Alice`
- Not this playbook: `video call Alice` (unsupported), `send a message to Alice` → send-message
