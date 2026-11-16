# TV Control

Turn an Android TV into something you can drive from any browser on your Wi-Fi - plus an AI
assistant that can actually press the buttons.

The TV app runs in the background, performs navigation/typing through Android's accessibility
layer, and serves a small web dashboard from the TV itself. Open that address on a phone, pair
with the PIN on the TV screen, and you have a remote, a keyboard for the TV, and an assistant
that turns "open Netflix and search for Dune" into real button presses.

```
┌──────────────────────┐   Wi-Fi / LAN   ┌─────────────────────────────┐
│  Phone remote app    │ ───────────────▶│  Android TV                │
│  (native, Compose)   │  HTTP + WS      │  TV Control (background)    │
│  — or any browser,   │◀─────────────── │  ├ accessibility engine     │
│    the web dashboard │                 │  ├ LAN server (REST + WS)   │
└──────────────────────┘                 │  └ assistant (cloud LLM)    │
                                         └─────────────────────────────┘
```

There are now **two ways to drive the TV**: the native **phone app** (`android/phone`,
Jetpack Compose + Material 3, Material icons, auto-discovers the TV over mDNS) and the
**web dashboard** served by the TV itself. Both speak the exact same REST + WebSocket
contract, so you can use whichever you like.

## What is in this repo

| Path | What it is |
| --- | --- |
| `android/app/` | The Android **TV** app (the server): Kotlin, no Compose, min SDK 26. Foreground service + accessibility service + embedded HTTP/WebSocket server. |
| `android/phone/` | The native **phone/tablet remote** app (the client): Jetpack Compose + Material 3, Material icons, min SDK 26. Finds the TV over mDNS, pairs with the PIN, and drives it over the same LAN API. |
| `dashboard/` | The web remote/assistant UI (Vite + React + TypeScript). It is compiled **into the TV APK** and served by the TV, so nothing has to be hosted anywhere. |
| `scripts/build-dashboard.sh` | Builds the dashboard into the TV APK's assets. Wired into Gradle, so it runs on its own. |
| `.github/workflows/` | CI: `build.yml` typechecks the dashboard and builds both debug APKs on every push/PR; `release.yml` publishes both signed APKs to a GitHub Release when you push a `v*` tag. |
| `docs/ARCHITECTURE.md` | How the pieces fit together, the decisions behind them, and the honest limits. |
| `docs/API.md` | The REST + WebSocket contract the TV exposes on the LAN. |

## Install from a release (no toolchain needed)

Every tagged release on GitHub ships two APKs (built and attached by CI):

| APK | Install it on |
| --- | --- |
| `tvcontrol-tv-<ver>.apk` | the **Android TV** (the server that runs in the background) |
| `tvcontrol-remote-<ver>.apk` | your **phone or tablet** (the native remote) |

Put the TV APK on the television, open it, turn on the accessibility engine; put the remote
APK on your phone, open it, pick the TV it finds (or type the address), and pair with the PIN.

## Build and install

Requirements: a **full JDK 17+** (not a JRE-only install - Gradle needs `javac`), the Android
SDK with platform 37, and Node or Bun for the dashboard. The Gradle wrapper brings its own Gradle.

```bash
cd android

# TV app (this also builds the web dashboard into the APK)
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug     # any full JDK 17+ works

# Phone remote app
JAVA_HOME=/opt/android-studio/jbr ./gradlew :phone:assembleDebug

# …or both at once
./gradlew :app:assembleDebug :phone:assembleDebug
```

Install the TV app on the TV and the remote on your phone:

```bash
adb connect <tv-ip>:5555                                           # the TV, over Wi-Fi
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk

adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk   # your phone
```

`scripts/build-apk.sh` builds the TV APK and picks a usable JDK for you (handy when the
system's `java` is a JRE without `javac`).

Then, **on the TV**:

1. Open **TV Control**. It starts the server and shows an address like `http://192.168.1.42:8787`,
   a PIN, and a QR code.
2. Open **Settings → Accessibility → TV Control remote** and turn it on. Without this, navigation,
   OK, Back/Home and typing cannot work (volume and playback still do).

Then, **on your phone**: open the **TV Remote** app and pick the TV it discovers (or type the
address), then enter the PIN. No app? Scan the QR code or open the address in any browser for the
web dashboard instead.

## Using it

- **Remote** - D-pad with hold-to-repeat, OK, Back/Home/Recents, volume, playback, and a scroll pad.
- **Keyboard for the TV** - when a search box is focused on the TV, type here and it lands in the
  box on the TV (`Send`, `Append`, `Clear`, `Send + Enter`).
- **App launcher** - every launchable app on the TV, one tap away.
- **Search shortcuts** - push a query straight into YouTube or the Play Store instead of typing.
- **Ad blocking** - one switch in the Settings panel points the TV's Private DNS at a filtering
  resolver (AdGuard DNS, or a hostname you bring from NextDNS/ControlD), so every app that uses the
  TV's DNS loses ad and tracker domains. The card reports whether ad domains are really being sunk;
  before switching, the app proves the resolver is reachable and rolls the TV back on its own if DNS
  stops answering.
- **Assistant** - describe what you want in plain words. The assistant reads the current TV screen,
  asks your model for a plan, and performs it step by step, streaming progress to your phone.
  A local model server (Ollama, LM Studio, llama.cpp, …) works too: set the base URL to
  `http://<your-pc>:11434/v1` and leave the API key empty.

## Development

```bash
# Dashboard with hot reload, pointed at a real TV
cd dashboard && npm install && npm run dev
# open http://localhost:5173?host=<tv-ip>:8787

# Rebuild just an APK after changing Kotlin
cd android && ./gradlew :app:assembleDebug     # TV app
cd android && ./gradlew :phone:assembleDebug   # phone remote

# Typecheck the dashboard
cd dashboard && npm run typecheck
```

The phone app points at a TV by mDNS discovery; to develop against a specific one, just type
its `ip:port` on the connect screen. Both Gradle builds need the same full JDK; `JAVA_HOME`
must point at one when you call `./gradlew` directly.

## Releasing

CI lives in `.github/workflows`:

- **`build.yml`** runs on every push and PR: it typechecks the dashboard and builds both debug
  APKs, so a break is caught early.
- **`release.yml`** runs when you push a tag like `v0.2.0` (or from the Actions tab by hand). It
  builds both APKs with the tag's version, then publishes them to a GitHub Release as
  `tvcontrol-tv-<ver>.apk` and `tvcontrol-remote-<ver>.apk`.

```bash
git tag v0.2.0 && git push origin v0.2.0      # cut a release
```

Signing is optional. Set the repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` to sign the release
APKs with your own key; without them the release APKs are debug-signed, which still installs fine
for sideloading. The same four values work locally through an `android/keystore.properties` file
(git-ignored) with keys `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`.

## What it deliberately does not do

Honest limits, because they shape the design:

- **No key event injection.** Without root or an ADB connection, an app cannot inject D-pad keys
  system-wide. TV Control drives the *focus system* instead, which is how TV UIs are built. In the
  handful of apps that do not expose focus targets, movement reports a failure rather than
  guessing (see `docs/ARCHITECTURE.md`).
- **No power key, no long-press MENU.** Both need privileges a sideloaded app cannot have.
- **No YouTube in-video ads.** The player fetches its ads and its video from the same `googlevideo.com`
  hosts, so DNS-level filtering cannot tell them apart. The ad blocking above is real for everything
  else; the honest options for in-video ads are the player's own "Skip" button or a different client
  (SmartTube). See ADR-010.
- **Cleartext HTTP on the LAN.** The dashboard is served over plain HTTP inside your network;
  traffic never leaves it. See the security notes in the architecture doc.

## Troubleshooting

**The accessibility switch for "TV Control remote" turns itself straight back off.**

Open the dashboard's **Settings** panel first. The **"Can this TV be controlled?"** card runs a
compatibility check and spells out the situation instead of leaving you to guess: it reports the
install-source block, whether Google's remote-control service exists, whether ADB debugging is on,
whether the platform is signed with the public AOSP test keys, and it ends with a list of exact
actions for what it found. `GET /api/diagnostics` returns the same report raw.

The three outcomes you can see:

1. **`Accessibility blocked: yes - install source`** - Android 13+ is refusing accessibility to an
   app that did not come from the device's own store. Lift the restriction once from an adb shell
   (it sticks):
   ```bash
   adb shell cmd appops set com.codesky.tvcontrol.debug ACCESS_RESTRICTED_SETTINGS allow
   ```
   Some builds also show **⋮ → Allow restricted settings** on the app's info page; many TV builds
   hide it. Turning **Play Protect "scan apps"** off and reinstalling helps on some Google TV
   builds as well.
2. **`Accessibility blocked: no`, ADB debugging `on`** - the install-source rule is not the
   problem, so give the app the right to fix itself. From an adb shell:
   ```bash
   pm grant com.codesky.tvcontrol.debug android.permission.WRITE_SECURE_SETTINGS
   ```
   `WRITE_SECURE_SETTINGS` carries the "development" flag, so an adb grant is the only way to hand
   it over - and after that the dashboard's warning card grows a **"Turn the engine on from here"**
   button. The app also puts its own service back on Android's list at boot, which matters because
   some Android TV 14 builds switch every accessibility service off after a reboot, and while it is
   running it checks once a minute that its entry is still there (launchers on TV builds rewrite
   that list) and quietly adds it back.
3. **`Platform test keys: yes - system privileges possible`** - the TV is a developer build signed
   with the public AOSP test certificate (`userdebug`/`test-keys` in the fingerprint). A build of
   the app signed with the matching companion key runs with platform privileges on such a device
   and can press real remote keys directly through the system input manager - no accessibility
   service involved at all.

**No computer, no cable?** ADB runs over Wi-Fi, so a phone is enough:

1. On the TV: enable Developer options (Settings → System → About → tap the build entry 7 times),
   then turn on **ADB debugging** in Developer options.
2. On an Android phone on the same Wi-Fi: install **Termux** (Play Store), run
   `pkg install android-tools`, then `adb connect <tv-ip>:5555` - or use an ADB shell app such as
   **Remote ADB Shell** - and accept the prompt that appears on the TV.
3. Type the `appops` or `pm grant` command from above (`adbd` needs no `adb shell` prefix when you
   are already inside a shell).

There are also ADB shell apps made for Android TV that run the same commands on the TV itself.

To see what the TV is actually saying while you flip the switch:

```bash
adb logcat -c        # then toggle the switch, then:
adb logcat -d | grep -Ei "restricted|accessibility|tvcontrol|FATAL"
```

`AccessibilityManagerService: ... disallowed ... ACCESS_RESTRICTED_SETTINGS` means it is the
install-source rule above. A `FATAL EXCEPTION` naming `com.codesky.tvcontrol` would be an app bug -
that trace is worth sending.

Two things that are platform behaviour, not bugs: **force-stopping** the app turns the service off
(launch the app again to bring it back), and launchers on TV builds rewrite Android's accessibility
list while the box is running - which is why the app re-adds its own entry at boot and once a minute
while the server is up (it needs the `WRITE_SECURE_SETTINGS` grant to do that). The TV screen in the
app says when the engine is off, and the dashboard shows the same state.

**Control dies by itself a few minutes after someone starts watching YouTube on the TV.**

This firmware ships a background-app cleaner of its own. `com.hisilicon.tv.service` - the TV's
platform-signed system service - runs a five-minute timer that walks every running process and
calls `ActivityManager.forceStopPackage()` on each one that is not the app currently on screen,
not a system app, has a launcher icon, and is not on its three-entry hard-coded whitelist. It
happily kills players (VLC and even YouTube TV were force-stopped by it on this box) and it used
to kill this app - and because a force-stop also strips the accessibility entry, control died and
stayed dead until the app was opened again. Nothing in the engine crashed; `dumpsys` shows the
kills:

```bash
# who killed us? force-stop records name the caller's pid (2957 = the TV's own service here)
adb shell dumpsys activity exit-info com.codesky.tvcontrol.debug
```

The fix is a one-line manifest decision: the cleaner identifies its candidates by **process name**
(`PackageManager.getLaunchIntentForPackage(processName)`), and a sub-process name like
`com.codesky.tvcontrol.debug:tvcontrol` is not a package, so the app is skipped before any other
check. The whole app runs in that private process now - the launcher icon, the accessibility
binding, the boot receiver and the LAN server all behave exactly as before. Confirm it on your TV:

```bash
adb shell ps -A | grep codesky     # expect ... com.codesky.tvcontrol.debug:tvcontrol
```

If some future TV force-stops whole *packages* instead of processes, the escalation is to install
the app under `/system/app` (or `/system/priv-app`): the cleaner skips `FLAG_SYSTEM` apps, and a
later `adb install -r` update of a system app keeps that flag.

**The dashboard is dark for a few seconds after a reboot.** The engine and the server both come up
on their own: the boot receiver asks for the listener, the accessibility service's own connection is
a second starting point (whichever fires first wins, the other is ignored), and a port that is still
busy with the previous socket is retried for up to a minute instead of given up on. Nothing on the
TV has to be touched. If it stays dark, the engine state on the TV screen and
`GET /api/diagnostics` say why.

## Status

`0.1.0` - the control path, the dashboard and the assistant are implemented end to end, and the
debug APK builds cleanly with the dashboard embedded in it
(`android/app/build/outputs/apk/debug/app-debug.apk`, verified to contain `assets/dashboard/`).
Verified on a real TV (WhaleTV/ZVT Android 14 box, no Google remote service, vendor-signed
platform): adb install, paired dashboard over Wi-Fi, D-pad navigation round-trips
(`{"ok":true,"via":"focus","moved":true}`), the engine survives a reboot, the server starts itself
at boot, and a cold start takes about four seconds without an ANR. The TV's own background-app
cleaner (a vendor service that force-stops every non-system, non-foreground app every five minutes)
no longer touches it: a 13-minute watch across three of its cycles left the process, the
accessibility binding and the server untouched (ADR-009). Ad blocking is a switch in the Settings
panel (`GET|POST /api/adblock`) that writes the TV's Private DNS setting, verified on the same TV:
`pagead2.googlesyndication.com` answered `127.0.0.1` while it was on and a real address when it was
off, bad resolver hostnames were refused before anything changed, and a deliberately dead resolver
was detected and reported with the fix instead of pretending everything was fine (that test found
and fixed a caching bug in the check - ADR-010). Still to check on hardware:
focus behaviour in each individual app, system keyboards, and mDNS discovery.
