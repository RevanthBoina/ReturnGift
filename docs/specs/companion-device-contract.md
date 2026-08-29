# Companion Device Contract

## Overview

P3.2 introduces a **second body** for ReturnGift: a companion device (TV, tablet, etc.)
that the phone's brain can command through a minimal "hands" endpoint. The brain
stays on the phone (privacy invariant); bodies are pluggable.

This document defines the JSON contract for the "hands" endpoint. The companion target
itself (a TV-optimized build of this app exposing ONLY the accessibility service +
hands endpoint, no UI, no brain) is scope for a later wave — this wave ships the
phone side + this contract doc.

## Architecture

```
┌──────────────────────┐         mDNS discovery        ┌──────────────────────┐
│  ReturnGift (phone)  │ ───────────────────────────→ │  Companion (TV)      │
│  Brain + UI          │        (pairing token)        │  Hands only           │
│                      │                              │  No brain, no UI      │
│  DeviceRegistry      │ ←─────────────────────────── │  /hands endpoint     │
│  ToolRegistry.executeOn() │                   │  (token-gated)       │
└──────────────────────┘                              └──────────────────────┘
```

## Discovery

Companion devices are discovered via mDNS over the existing LAN infrastructure.
The companion advertises a service of type `_returngift._tcp` with:

- `id`: unique identifier (e.g. device MAC or mDNS service name)
- `name`: human-readable name (e.g. "Living Room TV")
- `address`: IP:port of the "hands" endpoint
- `deviceType`: one of `tv`, `watch`, `tablet`, `speaker`
- `capabilities`: set of tool names the companion supports
- `pairingToken`: the SAME pairing token as the phone (P0.2 pairing token — no new auth surface)

## Pairing Token

The pairing token is the SAME token used on the phone (P0.2 pairing token
infrastructure). This means:

- No per-device secrets — the same token works on both sides
- Existing pairing infrastructure is reused — no new auth surface
- Token revocation is identical to the phone's — the pairing token manager handles it

## Hands Endpoint Contract

The companion exposes a single HTTP endpoint at `http://<address>/hands`:

### Request

```
POST /hands HTTP/1.1
Content-Type: application/json

{
    "tool": "dpad_up",
    "params": {
        "key": "KEYCODE_DPAD_UP"
    },
    "token": "<pairing-token>"
}
```

### Response

```
HTTP/1.1 200 OK
Content-Type: application/json

{
    "success": true,
    "message": "dpad_up executed"
}
```

### Error Response

```
HTTP/1.1 403 Forbidden
Content-Type: application/json

{
    "success": false,
    "message": "Invalid pairing token"
}
```

## Security

- **Token-gated**: The pairing token is required for every request. Without it, the
  companion returns 403.
- **Single endpoint**: The companion exposes ONLY `/hands` — no other endpoints (no
  web UI, no debug info, no accessibility service access from the network).
- **Same vocabulary**: Both sides share the same tool vocabulary (`dpad_up`, `dpad_down`,
  `dpad_left`, `dpad_right`, `dpad_center`, `volume_up`, `volume_down`, `press_menu`,
  `press_power`).
- **Safety check runs on the phone**: Before dispatch, `ToolRegistry.executeOn()` runs
  the `SafetyInterceptor` check on the phone (brain). The companion does NOT make
  safety decisions — it just executes what it's told.

## Tool Vocabulary

The companion supports the same tool vocabulary as the phone's TV tools:

| Tool | Description |
|------|-------------|
| `dpad_up` | Press the up key on the D-pad |
| `dpad_down` | Press the down key on the D-pad |
| `dpad_left` | Press the left key on the D-pad |
| `dpad_right` | Press the right key on the D-pad |
| `dpad_center` | Press the center/OK key on the D-pad |
| `volume_up` | Increase the volume |
| `volume_down` | Decrease the volume |
| `press_menu` | Press the menu key |
| `press_power` | Press the power key |

## Test Contract (Loopback E2E)

The loopback E2E test verifies the dispatch path end-to-end:

1. Register a fake companion in `DeviceRegistry` pointing to a test server
2. Set the test server to echo back `{ "success": true, "message": "ok" }` on valid requests
3. Dispatch `dpad_up` via `ToolRegistry.executeOn(deviceId, "dpad_up", {})`
4. Assert the JSON arrived token-gated (the test server validates the token)
5. Assert the ToolResult is success with the expected message

## Scope Notes

- **This wave**: Phone side only — `DeviceRegistry`, `ToolRegistry.executeOn()`,
  loopback E2E test, and this contract doc.
- **Later wave**: Companion APK build (TV-optimized build exposing ONLY the
  accessibility service + hands endpoint, no UI, no brain).
- **NOT in scope**: mDNS discovery implementation — the companion is expected
  to advertise via the existing LAN infrastructure (the ConfigServer's nanohttpd
  + the P0.2 pairing token apply). The phone side just needs to receive the
  discovery events and register them in `DeviceRegistry`.