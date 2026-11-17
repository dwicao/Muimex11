<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:7c3aed,50:2563eb,100:06b6d4&height=170&section=header&text=Muimex11&fontSize=58&fontColor=ffffff&fontAlignY=36&desc=Android%20game%20optimizer%20%E2%80%94%20free%2C%20no%20ads%2C%20no%20premium&descSize=20&descAlignY=58&animation=fadeIn" width="100%" />

[![Build](https://github.com/dwicao/Muimex11/actions/workflows/build.yml/badge.svg)](https://github.com/dwicao/Muimex11/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/dwicao/Muimex11?label=Release&color=3765D8)](https://github.com/dwicao/Muimex11/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/dwicao/Muimex11/total?label=Downloads&color=brightgreen)](https://github.com/dwicao/Muimex11/releases)
[![Stars](https://img.shields.io/github/stars/dwicao/Muimex11?color=yellow)](https://github.com/dwicao/Muimex11/stargazers)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84)

</div>

# About

Muimex11 is an Android game optimizer built on [Shizuku](https://shizuku.rikka.app/). It runs ADB-level commands without root: a floating Game Space dock with a live FPS counter, a Samsung panel that removes the Game Optimizing Service bottleneck, real-time hardware monitoring, and a set of performance/display tweaks.
No ads, no accounts, no paid tiers — every feature is available to everyone.

<div align="center">

<img src="https://readme-typing-svg.demolab.com/?font=Righteous&weight=600&size=22&duration=3000&pause=900&color=7CF5C9&center=true&vCenter=true&width=640&height=60&lines=Floating+Game+Space+Dock+%7C+Live+FPS;Samsung+Boost+Panel;Shizuku+Powered+%7C+No+Root" alt="live animation" />

<img src="https://count.getloli.com/get/@Muimex11-official-readme" alt="visitors" width="200" />

</div>

# Features

## Floating Game Space dock
A draggable orb that snaps to the screen edge and opens a glass panel over any game. Stays dark and readable over bright content.

- **App detection** — name and icon of whatever app is on screen
- **Live FPS counter** — sampled per second, color-coded by threshold
- **Two-column tile grid** with live on/off state:

| Boost | Game Boost |
|---|---|
| kills background apps + trims caches | forces Android Game Mode *Performance* on the detected app |
| **Fixed Performance** | **Thermal Override** |
| locks the CPU against mid-fight downclocking | suspends thermal throttling limits |
| **120Hz Lock** | **Focus Mode** |
| min + peak refresh rate locked at 120 | heads-up banners disabled |
| **No Window Blurs** | **Resolution Cycle** |
| disables blur effects that cost GPU time | 100% → 75% → 50% render scale |
| **HW Monitor** | **Stop Game** |
| toggles the stats overlay | force-stops the detected app |

## Samsung panel (One UI)
- **Max Boost** — thermal override + background clear + cache trim in one tap
- **GOS disable** — removes Samsung's Game Optimizing Service FPS cap, with restore
- **RAM Plus off**, **logging daemons frozen**, **Device Security frozen**, **Samsung bloat frozen**
- **Restore all** — returns every package and setting to default

## Hardware monitor
Floating overlay with CPU temperature and frequency, RAM usage, battery temp and network traffic, updated in real time.

## Graphics API switcher (Vulkan/OpenGL)
Switch the GPU rendering API without root, over Shizuku. Adapted from [GAMA](https://github.com/palincat/gama) (MIT license).

- **Vulkan** — lower temps and better battery on supported devices
- **OpenGL** — the Android default, maximum compatibility
- The switch is verified read-back and applies to newly launched apps; the choice is saved and Vulkan is re-applied automatically after reboot once Shizuku is ready (Android clears the setting on reboot, and Reset-all returns it to OpenGL).
- Renderer support varies by device and app: some apps ignore Vulkan and fall back on their own, which is normal. If the screen glitches after switching, hold Power + Volume Down to force-restart, then switch back to OpenGL.

## Performance & system tools
RAM booster · cache cleaner · app freezer · fstrim · fixed-performance modes · Game Mode selector · per-game downscale/FPS tuning · thermal override + headroom forecast · blur disable · fast animations · refresh rate control (OS-aware presets) · resolution + DPI + font scale · graphics API switcher (Vulkan/OpenGL) + Force 4x MSAA · private/custom DNS · captive-portal control · airplane/NFC/location toggles · touch and network tuners · standby buckets · dexopt booster · gyro fix · crosshair overlay · auto clicker · gaming DND · APK/OBB installer (with downgrade) · Shizuku shell terminal · device info · English and Indonesian.

## What's new in this fork

This fork is 80+ commits ahead of upstream. Highlights, newest first:

### Reliability & engineering (v4.35)
- **Charge alarm actually runs in the background again** — `BatteryAlarmService` declared `foregroundServiceType="health"` while calling `startForeground(..., TYPE_SPECIAL_USE)`, and the resulting API 34+ rejection was swallowed by an empty catch. It is now a `specialUse` service like the other overlays, and a rejected foreground start is logged instead of vanishing.
- **Tweak state is typed, not text** — "is this already applied?" was answered by substring-matching the *localized* status label (`status.contains("Recently")`), so editing copy or switching to Indonesian could change what a button does. `StatusRegistry` now holds a `StatusKind` per card, fed by real read-back, and the UI colours themselves derive from it.
- **Status refresh forks one shell instead of thirteen** — `settings get` reads in the status, network and system sections now run as a single tagged script (`ShellBatcher` + `ShizukuRunner.runTagged`), so `sh` process creation drops from 13 to 3 per refresh. The marker parser handles noisy, multi-line and missing output, and is unit-tested.
- **Build hygiene** — Shizuku deps moved into the version catalog, dead no-op ProGuard keeps deleted (`LogicHelpers` never existed as a class), unused Espresso/androidTest dependencies removed, dock command failures now log their exit code.
- **Lint is tracked** — the report is uploaded and summarised on every run so findings stop being invisible.

### Quick Settings tiles fix (v4.34)
- **Tiles actually fire on the shade now** (Samsung One UI included): without Shizuku ready — or on a locked device — a tile tap unlocks/opens Muimex11 via the Android 14+-safe `startActivityAndCollapse(PendingIntent)` API. The previous Intent-based call throws `UnsupportedOperationException` on API 34+, and the exception was swallowed, so taps did absolutely nothing.
- **Every tile outcome gives feedback** — Private DNS on/off, Focus mode on/off, "Graphics API → … applies to newly launched apps" (or a copyable failure reason), instead of silent success/failure. Reset-all consistency, tasker `renderer` action and the boot re-apply path are covered by unit tests.

### Security & hygiene (v4.32)
- **Self-update hardening** — APK downloads are now verified against the pinned release certificate (SHA-256) *before* installing, in addition to the existing sha256/integrity check. The expected signature is only ever read from the build-pinned constant — never fetched from the network. GitHub/raw.githubusercontent URLs are repo-scoped (only `dwicao/Muimex11` release paths are trusted). The CI release pipeline now fails instead of just printing when the built APK's certificate doesn't match `signature.txt`.
- **Portuguese removed, Indonesian added** — the app now ships English + Indonesian (full parity, ~1,150 strings, EN↔ID placeholder-checked). `locales_config.xml` now en/id and is wired into the manifest via `android:localeConfig`.
- **Dead code dropped** — the old ML-Kit machine-translation scaffolding (never-used `AppTranslator`/`LanguageSetup`), boilerplate tests and the duplicate keep-rules placeholder are gone.
- **Diagnostics** — previously-silent catch blocks now log to logcat (`Muimex11`, warning level); coroutine scopes of overlay services are properly cancelled on destroy; `MainActivity` is no longer exported; predictive back gesture enabled; dark mode now follows the system by default.
- All command builders route package names through the shell sanitizer.

### Display & comfort (v4.28)
- **Comfort & Display Extras card** — 20 verified tweaks: screen timeout, full Night Light (on/off + Kelvin + sunset schedule), font scale, adaptive brightness, mono audio, chat bubbles, tap-to-wake, dark mode via `cmd uimode`, extra dim, grayscale, force-dark, lock-after-timeout, lockscreen visibility, ambient gestures, camera double-press, ignore-orientation.
- **System Tweaks card (v4.27)** — Force 4x MSAA, show touches, AOD toggle, safe-volume warning off, force resizable, stay-awake-on-charge, status-bar `icon_blacklist` picker, animations 0.0x. All verified read-back, reversible, with per-card + reset-all coverage.
- **OS-aware refresh presets (v4.26)** — presets come from `DisplayManager`, unsupported rates are never offered; a revert dialog suggests the nearest supported rate when the ROM refuses a value.

### Network & radio (v4.28)
- **Net & Radio card** — captive-portal control, airplane via `cmd connectivity`, data roaming, NFC via `svc`, location master switch, and a probe-gated experimental radio-mode lock (4G-only for stable ping).
- **APK downgrade (v4.27)** — `pm install -r -d` via Shizuku shell for single APKs.

### Memory, battery & per-game (v4.28)
- **Memory & Background card** — background-process limit, per-app standby buckets, one-shot dexopt booster, `dumpsys alarm` wakeup report.
- **Per-Game Tuning card** — `cmd game` standard/performance/battery/custom with downscale + FPS cap per game, live config readout, per-game + full reset.
- **App privacy+ extended** — autostart (`BOOT_COMPLETED`), background-run and per-app audio-mute ops alongside the existing camera/mic/location/clipboard/notification controls.
- **Battery trigger level + thermal headroom** — auto-saver at a charge level; read-only 10-second thermal forecast in the thermal panel.

### Automation & control
- **Tasker / MacroDroid API (v4.18)** — token-guarded broadcast receiver (`com.muimex11.official.TASKER_RUN`): switch Vulkan/OpenGL or trigger a RAM boost from automation apps. The token lives in Shell Execute → Automation.
- **Quick Settings tiles (v4.19, fixed v4.34)** — one-tap Private DNS, Focus-DND and Vulkan toggles. v4.34 makes taps reliable on Android 14+ (PendingIntent launch path), with a toast for every outcome.
- **Shizuku recovery helper (v4.11)** — one-tap wireless-debugging re-enable that opens whichever Shizuku manager is installed (official or forks like Shevery).

### Gaming
- **Game Profiles v2 (v4.5–v4.19)** — per-game auto-apply on launch with restore on exit: Game Mode, fixed performance, 120Hz/refresh cap, resolution scale, orientation lock, keep-awake, no-blur, Focus DND, and force-stop on exit.
- **Graphics API switcher (v4.9)** — Vulkan/OpenGL via `setprop debug.hwui.renderer`, verified read-back and re-applied after reboot (adapted from [GAMA](https://github.com/palincat/gama), MIT).
- **In-game HW monitor extras (v4.18)** — live ping + jitter readout in the overlay.
- **Copyable command errors (v4.15)** — every failed tweak shows the full shell output in a copyable sheet instead of a truncated toast.

### System & privacy tools
- **Per-app language (v4.11)** — force a locale per app on Android 13+, verified read-back.
- **Per-app background-data block (v4.19)** — same mechanism as the system toggle, over `cmd netpolicy`, reversible.
- **App privacy+ (v4.5, v4.17)** — per-app AppOps (camera, mic, location + clipboard + notifications), one-tap batch block, and an access timeline showing which app used what last.
- **App uninstall with factory restore (v4.10)** — `pm uninstall -k --user 0` + `install-existing` restore; APK backup to Download/ (v4.10) incl. file-based settings backup/restore (v4.18).
- **Reboot menu (v4.10–v4.19)** — system / recovery / bootloader / SystemUI restart / turn screen off.
- **Battery suite (v4.4–v4.19)** — saver modes + Doze tuning + screen-off schedule, charge alarm, battery-health card (voltage/temp/cycles).
- **Thermal suite (v4.6–v4.19)** — adaptive governor with hysteresis + thresholds, heat-response auto drop to 60 Hz, manual bypass.
- **Radio info (v4.11, v4.19)** — `*#*#4636#*#*` launcher plus live operator/network/SIM readout.
- **Orientation lock card (v4.10)** + per-app forced rotation.

### Reliability engineering
- **Crash reporter (v4.13)** — uncaught exceptions are logged to `Download/Muimex11/` and shown in a copyable dialog on next launch (covers startup crashes).
- **Verified shell ops** — every set is read back; ROM rejections (e.g. Realme blocking `pointer_speed`, with highest-working-value probing since v4.16) degrade gracefully to partial states instead of silent lies.
- **Boot-safe state** — synchronous commit-based persistence with post-boot re-apply for Vulkan.
- **Danger scan (v4.17)** — shell scripts are scanned for destructive patterns before executing with Shizuku privileges.

### UI
- **View modes (v4.12–v4.14)** — list / 2-column grid / compact grid (no descriptions).
- **Search, colors, auto-hide header (v4.20–v4.25)** — live search across tweaks, per-category accent colors, status text without the redundant "Status:" prefix, icon reset buttons, search+chips that hide on scroll and slide back with enterAlways physics, one-time onboarding.

## Self-updating
The app checks this repository's `version.json`, prompts when a newer release is published, and installs it over itself. Updates are verified against the repo's `signature.txt` fingerprint.

# Install

1. Get the APK from [Releases](https://github.com/dwicao/Muimex11/releases/latest) (signed by the repo key on GitHub Actions).
2. Install [Shizuku](https://shizuku.rikka.app/) and start it (wireless debugging, root, or one-time USB from a PC).
3. Open Muimex11, grant Shizuku permission, and enable the tools you want.
4. Optional: allow *Display over other apps* for the Game Space dock and monitor.

Muimex11 installs alongside the old Benimaru app; you can remove Benimaru at any time.

# Building and releases

- Every push to `main` runs the [Build workflow](https://github.com/dwicao/Muimex11/actions/workflows/build.yml) and produces a signed APK artifact.
- Launcher icons are regenerated at build time from `art/app-icon.jpg` (Pillow; previews attached to every run).
- To publish an update: bump `versionCode`/`versionName` in `app/build.gradle.kts`, commit, then push a tag `v<name>` — the workflow signs the APK, creates the GitHub release, and refreshes `version.json` and `signature.txt` so installed apps pick it up.
- Signing material lives in write-only Actions secrets (`MUIMEX_KEYSTORE_BASE64`, `MUIMEX_KEYSTORE_PASSWORD`, `MUIMEX_KEY_ALIAS`) and is never committed.

<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/dwicao/Muimex11/output/github-contribution-grid-snake-dark.svg" />
  <img src="https://raw.githubusercontent.com/dwicao/Muimex11/output/github-contribution-grid-snake.svg" alt="contribution snake" width="100%" />
</picture>

[![Telegram](https://img.shields.io/badge/Telegram-muimex11-2CA5E0?logo=telegram&logoColor=white)](https://t.me/muimex11)

</div>

# Indonesia

Muimex11 adalah pengoptimal game Android gratis — tanpa iklan dan tanpa premium — yang berjalan di atas Shizuku (tanpa root).

- **Game Space mengambang**: gelembung yang bisa diseret di atas game apa pun dengan **nama + ikon aplikasi yang terbuka**, **FPS counter langsung**, dan kisi aksi dua kolom: boost RAM, Game Boost di aplikasi yang sedang dibuka, fixed performance, thermal bypass, kunci 120Hz, mode fokus, tanpa blur, rotasi resolusi (100/75/50%), monitor dan tutup game.
- **Panel Samsung**: Max Boost sekali ketuk, matikan GOS (dengan pemulihan), RAM Plus mati, bekukan daemon log, Device Security dan bloat Samsung, plus kembalikan semua.
- **Monitor hardware** mengambang real-time (CPU, RAM, baterai, jaringan).
- **API grafis (Vulkan/OpenGL)**: mengganti renderer GPU tanpa root via Shizuku (diadaptasi dari [GAMA](https://github.com/palincat/gama), lisensi MIT) — Vulkan untuk suhu lebih dingin/hemat baterai di perangkat yang didukung, OpenGL sebagai bawaan yang aman; diterapkan ulang sendiri setelah restart.
- **Yang baru di fork ini** (80+ commit di atas upstream): profil game dengan auto-apply (game mode, 120Hz, resolusi, orientasi, DND, kill saat keluar), tuning per game (mode/downscale/FPS via cmd game), Vulkan/OpenGL + MSAA 4x, preset refresh rate asli perangkat, kenyamanan & tampilan (timeout, Night Light, font, mode gelap, grayscale), jaringan & radio (captive portal, pesawat, NFC, lokasi, kunci 4G), memori (batas latar belakang, standby per app, dexopt), bahasa per app, blok data latar belakang per app, privasi AppOps (batch + timeline + autostart + audio), uninstall dengan pemulihan pabrik, cadangan APK, downgrade APK, menu reboot, baterai (hemat, Doze, alarm, kesehatan, level otomatis), termal adaptif + prakiraan, API otomasi utk Tasker, tile Quick Settings, ping/jitter di monitor, mode tampilan list/grid/compact + pencarian, dan pelaporan crash dengan log di `Download/Muimex11`.
- Alat performa, tampilan, jaringan, APK/OBB dan terminal Shizuku — semuanya dalam **Bahasa Indonesia dan Inggris**.

Unduh di [Releases](https://github.com/dwicao/Muimex11/releases/latest), mulai Shizuku dan izinkan di aplikasi. Muimex11 memperbarui sendiri saat versi baru rilis di GitHub.

---
<div align="center">

<img src="https://capsule-render.vercel.app/api?type=rect&color=0:7c3aed,100:06b6d4&height=4&section=footer" width="100%"/>

</div>
