# EssentialMapper

Personal Android app for a **Nothing Phone (3a) Lite** that remaps the hardware
**Essential Key** to launch any installed app based on tap count
(single / double / triple). Sideloaded, single-device, no Play Store.

> Status: Steps 1–4 complete + flashlight action. Live mapping: single → Claude,
> double → Google Wallet, triple → Flashlight toggle. See [Build order](#build-order).

---

## How the Essential Key behaves on this device

Discovered empirically on the 3a Lite (model `A001T`, Android 16 / Nothing OS 4):

| Property   | Value                                              |
|------------|----------------------------------------------------|
| keyCode    | `0` (`KEYCODE_UNKNOWN`) — **ambiguous, do not use** |
| scanCode   | **`250`** — the stable identity we match on        |
| source     | `0x101` (`SOURCE_KEYBOARD`)                         |
| deviceId   | `3`                                                |

We intercept via an `AccessibilityService` with `canRequestFilterKeyEvents` +
`flagRequestFilterKeyEvents` and match on **scanCode**, not keyCode (keyCode 0 is a
collision bucket shared by multiple buttons). This is the same mechanism the
open-source Key Mapper app uses.

### Single-press requires a one-time ADB step

The single press is system-owned (hardware path beats the app layer), so
`onKeyEvent` returning `true` won't suppress the stock action until the Nothing
"Essential" packages are disabled. Double / triple-tap are interceptable without
this. On this Android 16 build, three packages were disabled (the classic Essential
Space app alone was not enough — `essentialintelligence` is the active surface):

```bash
DEV=00254359X001220   # your phone's adb serial; omit -s if it's the only device

adb -s $DEV shell pm disable-user --user 0 com.nothing.ntessentialspace
adb -s $DEV shell pm disable-user --user 0 com.nothing.ntessentialrecorder
adb -s $DEV shell pm disable-user --user 0 com.nothing.essentialintelligence
```

Reverse any time with `pm enable <package>`. All reversible; nothing is uninstalled.

---

## Build & install

**Gotcha: the default `java` here is JDK 25, which Gradle rejects.** Build with JDK 21:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew :app:assembleDebug
```

(Android Studio uses its own bundled JBR, so it works there without the export.)

Install to the phone (two devices are usually attached — phone + emulator — so target explicitly):

```bash
adb -s 00254359X001220 install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open **EssentialMapper** → **Open Accessibility settings** → enable
**EssentialMapper**. Reinstalls keep the toggle unless the OEM clears it.

### Toolchain

- Kotlin 2.0.21, Jetpack Compose, AGP 8.9.1, Gradle 8.11.1
- `minSdk 31`, `compile/targetSdk 36` (only platforms 36/36.1/37 are installed locally)
- Single dependency of note: `androidx.datastore:datastore-preferences`

---

## Watching logs

```bash
adb -s 00254359X001220 logcat -s EMAP
```

A double-tap produces:

```
tap registered (running count=1)
tap registered (running count=2)
resolved tapCount=2
launched com.anthropic.claude
```

---

## Architecture

| File | Role |
|------|------|
| `EssentialKeyService.kt` | Accessibility service: matches scanCode 250, counts taps on a debounce, dispatches by count. |
| `Config.kt` | `KeyMap` model + DataStore persistence (`{single, double, triple}` → package name or null). |
| `MainActivity.kt` | Compose config UI + shortcut to Accessibility settings. |
| `res/xml/key_log_service_config.xml` | Accessibility service config (filter-key-events flags). |

### Tunable constants

All in `EssentialKeyService.kt`'s companion object:

- `TARGET_SCAN_CODE = 250` — the Essential Key's scanCode
- `TAP_DEBOUNCE_MS = 400L` — window after the last tap before the count resolves

The first-run default mapping is seeded in `Config.seedDefaultsIfEmpty()` (single → Claude,
double → Wallet, triple → flashlight). Change bindings in the app afterwards; the seed only
fires once. A slot holds either a package name or the `Config.ACTION_FLASHLIGHT` token.

---

## Build order

1. **Keycode discovery** — observe-only key logger. ✅ → scanCode 250.
2. **Prove the pipeline** — detect key, count taps, double-tap launches Claude. ✅
3. **Config UI + persistence** — Compose app picker, DataStore-backed `{single, double, triple}` map. ✅
4. **Wire service to config** — service dispatches by the persisted map instead of the hardcode. ✅
5. *(optional)* extra action types — flashlight toggle ✅ done; arbitrary intent / deep-link and
   broadcast not yet built (the dispatch `when` in `EssentialKeyService` is the place to extend).

---

## What to map it to

Popular Essential Key remaps from the Nothing community (Reddit, XDA, the Nothing
forum), grouped by what suits a physical, no-look, one-handed side button. Press-type
convention people settle on: **single = the instant everyday action, double = a
secondary app, long-press = deliberate/sensitive actions** (so they're hard to trigger
by accident).

**Launch an app**
- **Camera** — the single most-cited remap; the button sits where a shutter key would.
  (With conditional logic you can even make a tap act as the shutter once the camera's open.)
- **AI assistant** (Claude / Gemini / Perplexity / ChatGPT) — spiritually closest to the
  key's original "capture & ask" purpose.
- **Google Wallet / Pay** — usually long-press, to avoid accidental checkout-time triggers.
- **Notes / voice recorder** — quick capture, the key's original intent.
- **Spotify / music** — one-press playback on a run or in the car.

**Run an action / toggle**
- **Flashlight toggle** — the most-recommended pure *action*; on/off without unlocking.
- **Screenshot** — mirrors the stock short-press; easier one-handed than volume+power.
- **Mute / DND toggle** — feel-it-without-looking silence in meetings or at night.

### Suggested defaults for this phone

Double-tap is already **Claude**. **Camera is intentionally NOT mapped here** — double-tap of
the power button already launches it, so the Essential Key is better spent on something
without an existing gesture.

| Tap | Suggestion | Why |
|-----|------------|-----|
| **Single** | **Flashlight toggle** | The highest-value thing a physical button unlocks once camera (power double-tap) and AI (double-tap) are covered. No-look, no existing gesture. Needs Step 5 (it's a torch *action*, not an app launch). |
| **Double** | **Claude** | current |
| **Triple** | **Pacewise** *(fitness)* | On-brand no-look workout start (Pacewise / Fitbit installed; no Strava). Plain app launch — works with Steps 1–4. |

If you'd rather stay app-launch-only for now: single-tap → **Pacewise** or **Google Wallet**
(`com.google.android.apps.walletnfcrel`). Camera package, if you ever want it, is `com.nothing.camera`.

---

## Known gotchas

- **Accessibility services get battery-killed** in the background — the #1 real-world
  failure mode for this class of app. Exempt EssentialMapper from battery optimization
  before relying on it daily.
- **Package visibility (Android 11+)**: a `<queries>` block for `MAIN`/`LAUNCHER` is
  required in the manifest or `getLaunchIntentForPackage()` returns null.
- **scanCode 250 is device/firmware-specific** and unconfirmed on other models — fine
  for this single-device tool, don't assume it ports.

---

## Credits

- The ADB approach to freeing the Essential Key came from
  [z3phydev/How-to-remap-or-disable-the-Essential-Key](https://github.com/z3phydev/How-to-remap-or-disable-the-Essential-Key)
  — the guide that got this unblocked. On this Android 16 build, disabling
  `com.nothing.essentialintelligence` was also required (see [the ADB step](#single-press-requires-a-one-time-adb-step)).
- Interception mechanism mirrors how [Key Mapper](https://github.com/keymapperorg/KeyMapper)
  uses accessibility filtered key events.

## License

[MIT](LICENSE) © 2026 Aiden Rudolph. Personal project, shared as-is — no warranty,
not affiliated with Nothing.
