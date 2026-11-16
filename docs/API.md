# API

What the TV exposes on the LAN. Default port `8787`, so `http://<tv-ip>:8787`.

- **REST** for one-shot requests (`/api/...`).
- **WebSocket** `/ws?token=...` for anything latency-sensitive (button repeats) or streaming
  (screen updates, assistant progress).

Everything is JSON. Unknown fields are ignored on input, so clients may send more than documented.

## Auth

| Endpoint | Auth |
| --- | --- |
| `GET /api/ping` | none |
| `POST /api/pair` | none (PIN + rate limit) |
| everything else | `Authorization: Bearer <token>` |
| `/ws` | `?token=<token>` in the URL |

```http
POST /api/pair
{"pin": "123456", "device": "Leeh's Pixel"}

200 {"token":"9f2…","name":"Living Room TV","version":"0.1.0"}
401 {"error":"wrong_pin","message":"That PIN is not right. 4 attempts left this minute."}
429 {"error":"rate_limited","message":"Too many wrong PINs. Try again in a minute."}
```

Tokens never expire on their own; the TV stores only their SHA-256 hash. `POST
/api/settings/devices/revoke` signs every device out.

**Error shape** for every failure:

```json
{ "error": "machine_code", "message": "human readable" }
```

`GET /api/ping` (no auth) → `{"ok":true,"name":"Living Room TV","version":"0.1.0","requiresPairing":true}`

## REST

### State

| Call | Returns |
| --- | --- |
| `GET /api/info` | name, version, port, device model/Android version, engine state, volume state, last screen snapshot, full settings, and `restrictedSettings` (true when Android 13+ is declining accessibility because of the install source) |
| `GET /api/screen` | a `ScreenSnapshot` (see below) |
| `GET /api/diagnostics` | what this TV can actually be driven through: Android/SDK/build type/model, installer package, Play Store/Play services presence, whether Google's remote-control service exists and which of its ports answer (6466/6467), ADB debugging state, the install-source block, whether the engine is on Android's enabled list, whether the app holds `WRITE_SECURE_SETTINGS`, whether the platform is signed with the public AOSP test keys, plus a human `verdict` and a list of `actions` |
| `POST /api/engine/enable` | none | `{"ok":true,"hasPermission":true,"message":"…"}` - puts the accessibility service back on Android's enabled list; only succeeds when the owner has run `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` from an adb shell |
| `GET /api/apps?refresh=1` | `{"apps":[{"pkg","label","leanback"}]}` - launcher-visible apps |
| `GET /api/volume` | `{"ok":true,"levelPercent":35,"muted":false}` |
| `GET /api/health` | `{"ok":true,"dashboards":1,"accessibility":true}` |

`ScreenSnapshot`:

```json
{
  "pkg": "com.netflix.ninja",
  "app": "Netflix",
  "focus": {"text": "Stranger Things", "desc": null, "cls": "android.widget.TextView",
            "focused": true, "focusable": true, "clickable": true, "editable": false,
            "scrollable": false, "enabled": true, "bounds": "120,340,860,520",
            "viewId": "com.netflix.ninja:id/title"},
  "field": {"text": "dune", "hint": "Search", "pkg": "com.netflix.ninja", "canSubmit": true},
  "labels": ["Home", "Search", "My List", "…"],
  "scrollable": true,
  "imeVisible": false,
  "takenAt": 1760000000000
}
```

`field` is `null` when no text field is on screen. `canSubmit` means the TV can simulate the
keyboard's search key (`ACTION_IME_ENTER`, Android 11+).

### Control

| Call | Body | Returns |
| --- | --- | --- |
| `POST /api/key` | `{"key":"down","repeat":1}` | `{"key":"down","result":{"ok":true,"via":"focus","moved":true}}` |
| `POST /api/text` | `{"text":"dune","mode":"replace","submit":false}` | `TextResult` |
| `POST /api/launch` | `{"query":"netflix"}` or `{"package":"com.netflix.ninja"}` | `{"ok":true,"pkg":"…","label":"…"}` |
| `POST /api/search` | `{"query":"dune","target":"auto"}` | `ActionResult` |
| `POST /api/volume` | `{"action":"up"}` / `{"action":"set","value":40}` | `{"ok":true,"levelPercent":40,"muted":false}` |

**Keys** (`/api/key`): `up` `down` `left` `right` `ok` `back` `home` `recents` `notifications`
`quick_settings` `menu` `scroll_up` `scroll_down` `scroll_left` `scroll_right` `volume_up`
`volume_down` `mute` `play_pause` `play` `pause` `next` `previous` `stop` `rewind` `fast_forward`

`repeat` presses the key up to 30 times with 60 ms between presses - cheaper than 10 round trips.
`menu` is reported as unsupported (see the architecture doc); it is accepted and refuses politely
rather than failing the request.

**Text modes** (`/api/text`): `replace` (whole field), `append` (to what is there), `clear`,
`enter` (leave the text alone and press the keyboard's search key). `submit: true` presses the
search key after writing. `TextResult`:

```json
{"ok":true,"via":"set-text","submitted":true,
 "target":{"text":"dune","hint":"Search","pkg":"com.netflix.ninja","canSubmit":true},
 "note":null}
```

**Search targets**: `auto` (YouTube if installed, else Play Store), `youtube`, `play`. Search is a
deep link, not UI automation: it is the most reliable way to "find" something on a TV.

`ActionResult` = `{"ok":bool,"via":string|null,"moved":bool|null,"note":string|null}`.
`note` is written for humans and is safe to show in a toast. When `ok` is false the note explains
the limitation (for example "the accessibility engine is not running" or "this app does not expose
focus targets").

### Settings

| Call | Body / returns |
| --- | --- |
| `GET /api/settings` | `SettingsDto` |
| `PUT /api/settings` | partial patch, returns the updated `SettingsDto` |
| `POST /api/settings/pin/rotate` | returns `SettingsDto` with a fresh `pin` |
| `POST /api/settings/devices/revoke` | `{"ok":true}` - every token invalidated |

```http
PUT /api/settings
{
  "deviceName": "Living Room TV",
  "port": 8787,
  "autoStart": true,
  "llm": {"provider":"openai","baseUrl":"https://api.openai.com/v1",
          "model":"gpt-4o-mini","apiKey":"sk-…"},
  "assistant": {"mode":"plan","maxSteps":12,"stepDelayMs":400}
}
```

`SettingsDto.llm.apiKeyConfigured` is a boolean; the key itself is never returned. Changing
`provider` resets base URL and model to that provider's defaults unless they are given too.
A port change applies at the next server restart (the running listener cannot rebind without
dropping the request that asked for it).

### Ad blocking (Private DNS)

System-wide ad blocking is not an app-level feature: the TV's Private DNS setting *is* the switch,
and the app writes it (it already holds `WRITE_SECURE_SETTINGS` for the accessibility engine). Every
app that resolves through the system resolver is covered - no per-app work, no new process on the TV,
and the setting survives reboots. What it cannot cover is YouTube's in-video ads: those segments come
from the same `googlevideo.com` hosts as the video itself.

| Call | Body / returns |
| --- | --- |
| `GET /api/adblock` | `AdBlockState` - what the TV is actually set to right now |
| `GET /api/adblock?probe=1` | the same, plus one live DNS check (a couple of seconds) |
| `POST /api/adblock` | `{"enabled":true,"hostname":"dns.adguard-dns.com"}` or `{"enabled":false}`; returns `AdBlockState`, whose `note` says what happened |
| `GET /api/root` | `RootReport` - `level` is `app` (this app got uid 0 from `su`), `shell` (su / adb root exists, apps cannot use it), `weak` (developer build, not rooted) or `none`; `checks` lists the evidence |

`AdBlockState`:

| Field | Meaning |
| --- | --- |
| `enabled` | `private_dns_mode == "hostname"` with a specifier - the TV's own setting, not a stored flag |
| `hostname`, `mode` | the raw `private_dns_specifier` and `private_dns_mode` values |
| `canWrite` | whether the app holds `WRITE_SECURE_SETTINGS`; without it no change is attempted |
| `chosenHostname` | the resolver the app would apply next (remembered between sessions) |
| `previousHostname` | what turning it off restores; `null` means the network's own DNS |
| `presets` | `[{id,label,hostname,note}]` - AdGuard DNS and AdGuard DNS Family |
| `lastCheck` | the last probe: `{at,normalResolves,adBlocked,note}`, or `null` if never run |
| `verdict`, `actions` | one plain sentence about the state, plus the exact grant/next step |

Enabling is guarded. The app resolves the hostname and opens TCP 853 on it first, and refuses if
either step fails - a wrong resolver would take the whole TV's DNS down. After switching it waits for
the platform to apply the change and probes `connectivitycheck.gstatic.com`/`example.com`; if
ordinary names stop resolving it puts the previous `private_dns_mode`/`private_dns_specifier` back,
re-checks and says so in `note`. Turning it off restores whatever Private DNS was set to before the
app touched it. Filtering is detected by asking for ad test domains
(`pagead2.googlesyndication.com`, `googleads.g.doubleclick.net`): a filtering resolver sinks them to
loopback/wildcard addresses or fails them, which is what `lastCheck.adBlocked` reports.

### SOCKS5 proxy routing

Sends every app's traffic on the TV through a SOCKS5 server the owner saved, using Android's
`VpnService` slot. Android asks once, on the TV, before an app may do this (the dashboard D-pad can
press OK; `appops set <package> ACTIVATE_VPN allow` over adb skips the prompt). Every call returns
`ProxyState`.

| Call | Body |
| --- | --- |
| `GET /api/proxy` | - |
| `POST /api/proxy` | `{"enabled":true}` (optionally `"id"` to pick the server) or `{"enabled":false}` |
| `POST /api/proxy/servers` | `{"name","host","port","username","password"}` to add; include `"id"` to edit. `password`: omitted keeps the stored one, `""` clears it |
| `DELETE /api/proxy/servers/{id}` | - |
| `POST /api/proxy/servers/{id}/test` | - (connects through it and fetches the exit address) |
| `POST /api/proxy/active` | `{"id":"…"}` - switch servers; while routing is on, open connections are cut and reconnect through the new one |
| `POST /api/proxy/options` | `{"failOpen":false}` turns the kill switch on |

`ProxyState`: `enabled` (the owner's wish), `running` (tunnel up), `paused` (the watchdog paused it
because the proxy stopped answering), `consentNeeded`, `failOpen`, `activeId`, `servers`
(`[{id,name,host,port,username,hasPassword,active,lastTest}]` - passwords never come back), `stats`
(`{since,activeConnections,totalConnections,bytesUp,bytesDown,dnsQueries}` while running), `note`,
`verdict`, `actions`.

Switching on and switching servers are guarded: the server is tested first (SOCKS5 handshake, login,
and an HTTP fetch of `api.ipify.org` through it) and the change is refused with the reason if that
fails. How the tunnel works: TCP is NAT-rewritten in the tun device onto a local relay socket, so the
kernel's own TCP stack does the TCP work and the relay opens one SOCKS5 CONNECT per connection. DNS is
answered with fake addresses from `198.18.0.0/15`, and the relay sends the *name* to the proxy, so
lookups never leave around it. Other UDP (QUIC) and IPv6 are dropped, so apps fall back to TCP over
IPv4. Private, link-local, CGNAT and multicast ranges are not routed in, so the dashboard, casting
and discovery keep working. With `failOpen` (the default) the watchdog pauses the tunnel after three
failed probes a minute apart and resumes it when the proxy answers again; with the kill switch the
tunnel stays up and the TV is offline while the proxy is down.

### Bluetooth

Full Bluetooth device discovery, profile management (A2DP audio, HID gamepads/keyboards, headsets), pairing, and connection management.

| Call | Body | Returns |
| --- | --- | --- |
| `GET /api/bluetooth` | - | `BluetoothStatusDto` (adapter state, bonded devices, scanned devices) |
| `POST /api/bluetooth/state` | `{"enabled":true}` | `BluetoothActionResult` |
| `POST /api/bluetooth/scan` | `{"action":"start"}` or `{"action":"stop"}` | `BluetoothActionResult` |
| `POST /api/bluetooth/pair` | `{"address":"XX:XX:XX:XX:XX:XX"}` | `BluetoothActionResult` |
| `POST /api/bluetooth/connect` | `{"address":"XX:XX:XX:XX:XX:XX"}` | `BluetoothActionResult` |
| `POST /api/bluetooth/disconnect` | `{"address":"XX:XX:XX:XX:XX:XX"}` | `BluetoothActionResult` |
| `POST /api/bluetooth/unpair` | `{"address":"XX:XX:XX:XX:XX:XX"}` | `BluetoothActionResult` |
| `POST /api/bluetooth/launch-pairing` | - | `BluetoothActionResult` (launches native Android TV pairing wizard on screen) |

`BluetoothStatusDto`:
```json
{
  "supported": true,
  "enabled": true,
  "scanning": false,
  "name": "Smart TV",
  "address": "40:82:53:C8:38:F4",
  "pairedDevices": [
    {
      "name": "oraimo SpaceBuds Lite",
      "address": "84:75:E7:5C:38:DC",
      "bonded": true,
      "connected": false,
      "deviceType": "audio",
      "majorClass": 1024,
      "rssi": null,
      "bondState": "bonded"
    }
  ],
  "discoveredDevices": []
}
```

### Files & Sideload

Transfer files (media, subtitles, documents) directly to TV storage over local Wi-Fi, inspect disk storage, and install APKs wirelessly.

| Call | Body / Query | Returns |
| --- | --- | --- |
| `GET /api/files` | - | `StorageInfoDto` (total/free disk space, storage path, file items) |
| `POST /api/files/upload` | `multipart/form-data` (`file`); optional `?install=1` | `FileActionResponse` |
| `POST /api/files/install` | `{"name":"app.apk"}` | `FileActionResponse` (launches native package installer on TV) |
| `DELETE /api/files` | `?name=file.ext` or `{"name":"file.ext"}` | `{"ok":true}` |
| `GET /api/files/download` | `?name=file.ext` | file binary stream (with attachment header) |

`StorageInfoDto`:
```json
{
  "totalBytes": 5323350016,
  "freeBytes": 3461140480,
  "usableBytes": 3461140480,
  "formattedFree": "3.2 GB",
  "formattedTotal": "5 GB",
  "freePercent": 65,
  "storagePath": "/storage/emulated/0/Android/data/com.codesky.tvcontrol.debug/files/Download",
  "files": [
    {
      "name": "SmartTube.apk",
      "size": 24117248,
      "lastModified": 1792365400000,
      "isApk": true,
      "extension": "apk",
      "formattedSize": "23 MB",
      "packageName": "com.teamsmart.videomanager.tv",
      "appName": "SmartTube",
      "appVersion": "24.1",
      "path": "/storage/emulated/0/Android/data/com.codesky.tvcontrol.debug/files/Download/SmartTube.apk"
    }
  ]
}
```

### Now Playing & Media Control

Real-time media session tracker and transport controls for media apps (SmartTube, YouTube, Spotify, Netflix, VLC). Emits live updates across WebSocket and supports precise timeline scrubbing.

| Call | Body / Query | Returns |
| --- | --- | --- |
| `GET /api/media/now-playing` | - | `NowPlayingDto` |
| `POST /api/media/control` | `{"action":"play\|pause\|play_pause\|next\|previous\|rewind\|fast_forward\|seek","positionMs":1234}` | `MediaControlResult` |
| `GET /api/media/art` | `?token=...` | Album / thumbnail JPEG binary stream |

`NowPlayingDto`:
```json
{
  "active": true,
  "title": "Song / Video Title",
  "artist": "Artist / Channel",
  "album": "Album Name",
  "packageName": "org.smarttube.stable",
  "appName": "SmartTube",
  "durationMs": 240000,
  "positionMs": 45000,
  "playbackSpeed": 1.0,
  "isPlaying": true,
  "playbackState": "playing",
  "canSeek": true,
  "canPlayPause": true,
  "canSkipNext": true,
  "canSkipPrev": true,
  "hasArtwork": true,
  "artworkBase64": "data:image/jpeg;base64,...",
  "lastUpdated": 1792368000000
}
```

### Assistant

| Call | Body | Returns |
| --- | --- | --- |
| `POST /api/assistant` | `{"utterance":"open netflix and search dune","useScreen":true,"dryRun":false}` | `{"runId":"run_…"}` |
| `POST /api/assistant/cancel` | - | `{"ok":true}` |
| `GET /api/assistant/{id}` | - | `AssistantRun` |

Progress arrives over the WebSocket as `assistant.run` messages (the whole run each time, so a
reconnecting client can simply replace its state).

```json
{
  "id": "run_8c1f",
  "utterance": "open netflix and search dune",
  "status": "done",
  "say": "Opened Netflix and searched for Dune.",
  "steps": [
    {"index":1,"action":"open_app","detail":"open_app Netflix","status":"ok","note":"Netflix"},
    {"index":2,"action":"type","detail":"type \"dune\"","status":"ok","note":null}
  ],
  "error": null,
  "startedAt": 1760000000000,
  "finishedAt": 1760000001400
}
```

`status`: `running` | `done` | `partial` | `failed` | `cancelled` | `needs_input`.
Step status: `running` | `ok` | `failed` | `planned`.

`dryRun: true` returns the plan without touching the TV.

## WebSocket

`ws://<tv-ip>:8787/ws?token=<token>`

Envelopes, both directions:

```json
{ "v": 1, "type": "screen.update", "ts": 1760000000000, "data": { } }
```

### Client → TV

| Type | `data` | Notes |
| --- | --- | --- |
| `ping` | - | answered with `pong`; the dashboard sends one every 20 s |
| `hello` | - | asks for a `hello` snapshot |
| `key` | `{"key":"down","repeat":1}` | same vocabulary as `POST /api/key`; the fast path |
| `text` | `{"text":"dune","mode":"replace","submit":true}` | same as `POST /api/text` |
| `screen.request` | - | answered with a fresh `screen.update` |
| `assistant.run` | `{"utterance":"…","useScreen":true,"dryRun":false}` | answered with `assistant.started` |
| `assistant.cancel` | - | stops the active run |

### TV → Client

| Type | `data` | When |
| --- | --- | --- |
| `hello` | `{name, version, port, mode}` | on `hello` |
| `pong` | - | after `ping` |
| `engine.state` | `{"connected":true}` | accessibility engine attached/detached |
| `screen.update` | `ScreenSnapshot` | debounced (220 ms) and de-duplicated |
| `input.field` | `ScreenSnapshot` | focused text field changed - this is what drives the keyboard UI |
| `app.changed` | `{"pkg":"…","app":"…"}` | foreground app changed |
| `key.result` | `{"key":"down","result":ActionResult}` | after a key sent over the socket |
| `text.result` | `TextResult` | after text sent over the socket |
| `assistant.started` | `{"runId":"…"}` | run accepted |
| `assistant.run` | `AssistantRun` | on every step status change and at the end |
| `server.state` | `{"running":true,"port":8787,"url":"http://…"}` | listener started/stopped |
| `settings.changed` / `settings.note` | `{}` / `{"message":"…"}` | settings written; a note worth showing |
| `bluetooth_device_found` | `BluetoothDeviceDto` | discovered nearby Bluetooth device during scan |
| `bluetooth_discovery` | `{"scanning":bool}` | discovery scan started / finished |
| `bluetooth_bond_changed` | `{"address":"…","name":"…","bondState":"bonded\|bonding\|none","bonded":bool}` | bond status changed |
| `bluetooth_connection_changed` | `{"address":"…","name":"…","connected":bool}` | connection state changed |
| `bluetooth_state_changed` | `{"enabled":bool}` | Bluetooth adapter turned on/off |
| `now_playing` | `NowPlayingDto` | active playback state, metadata, or timeline position changed |
| `error` | `{"message":"…"}` | unknown message type, unusable assistant request |

## Assistant action vocabulary

The model is instructed to answer with exactly this shape (see `ai/Prompts.kt`):

```json
{ "say": "one short sentence", "confidence": 0.8,
  "plan": [ {"action":"search","query":"dune","target":"auto"} ] }
```

| Action | Arguments | Effect |
| --- | --- | --- |
| `dpad` | `dir` (up/down/left/right), `count` | focus movement |
| `ok` | - | click the focused item |
| `back` / `home` / `recents` | - | global action |
| `type` | `text`, `submit`, `mode` | text into the focused field |
| `open_app` | `name` | launch an installed app by name |
| `search` | `query`, `target` | YouTube / Play deep link |
| `media` | `cmd` (play_pause, next, previous, volume_up, volume_down, mute) | transport |
| `scroll` | `dir`, `count` | scroll without moving the selection |
| `wait` | `ms` | let the TV settle |
| `observe` | `why` | checkpoint |
| `ask` | `question` | ask the user, run ends as `needs_input` |
| `say` | `text` | message only, no TV action |

Bounded by `maxSteps` (default 12) and one replan in agent mode. There is no action that can
delete, purchase or type without an already-focused field.
