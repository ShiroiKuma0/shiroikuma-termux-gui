---
name: build-apk
description: Build the signed release APK of shiroikuma-termux-gui (白い熊 Termux GUI — our fork of termux/termux-gui, app id com.termux.gui kept) with the buildFork Gradle task, then deliver it automatically via the global /after-build skill (adb push if the phone is reachable, else scp to skhw — no prompt). Always build first without asking permission to build. Use whenever 白い熊 asks to build the app, build the APK, make a release build, or build and send to the phone, and after any functional code change.
---

# Build the 白い熊 Termux GUI release APK and deliver it

> **ALWAYS build, then ALWAYS deliver — no asking (白い熊's standing authorization, 2026-07-09).**
> After ANY functional change, build **immediately** and deliver. Do not stop at a compile-check, do
> not offer to build, do not ask how to transfer it. Build-and-deliver does **not** commit or push —
> a commit/push still waits for 白い熊's explicit "Push". (Skip the build only for non-functional
> edits — docs, comments.)

## Build environment (this machine)

The default `java` is **JDK 11**, which cannot run Gradle 8.10 (nor `sdkmanager`). Always export
JDK 21, and `ANDROID_HOME` too (a background shell does not inherit it):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/shiroikuma/android-sdk
```

The SDK path also comes from the gitignored `local.properties` (`sdk.dir=/home/shiroikuma/android-sdk`)
— recreate it if a build fails with **`SDK location not found`**. The JNI library needs NDK
**`23.1.7779620`** (upstream's pinned `ndkVersion`; installed under `~/android-sdk/ndk/`) and CMake;
upstream's `preBuild` also wants `~/android-sdk/sources/android-34` (installed 2026-09-13 — see
*Upstream build quirks* below).

## Steps

1. **Note the output filename / version.**
   - `grep -nE 'versionCode |versionName ' app/build.gradle | head -2` — upstream's base
     (`8` / `1.0.0`); these track upstream and are **never hand-edited**.
   - `grep -E '^(BUILD_NUMBER|LAST_BUILT_VERSION_CODE)' gradle.properties` — the `N` used for THIS
     build (the task bumps it afterwards) and the floor it must exceed.
   - The pin: `git merge-base HEAD main | cut -c1-8` and that commit's UTC committer time
     (`TZ=UTC git show -s --format=%cd --date=format-local:%Y-%m-%d.%H-%M <sha>`). **`main`**, not
     `master` — termux-gui's mirror branch is `main`.
   - APK will be `shiroikuma-termux-gui_<upstream>+<date>.<HH-MM>.g<sha8>+<NNN>_universal.apk`
     (`N` zero-padded to three digits in the name), e.g.
     `shiroikuma-termux-gui_1.0.0+2025-06-25.23-38.gbe24a5f3+001_universal.apk`.
   - versionCode for this build = `8 * 10000 + N` (plain, unpadded), e.g. `80001`.

2. **Build** (signed release) — from the repo root:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew buildFork --console=plain < /dev/null
   ```
   - `buildFork` (defined in `app/shiroikuma.gradle`) first checks — at configuration time, before
     any compilation — that `keystore.properties` exists and that the versionCode exceeds
     `LAST_BUILT_VERSION_CODE`; then runs `assembleRelease` (CMake JNI for all four ABIs, R8
     minify, signed from `keystore.properties`), copies `app/build/outputs/apk/release/app-release.apk`
     to `~/tmp/<apk name>`, refuses to overwrite an existing file there, and bumps `BUILD_NUMBER` +
     `LAST_BUILT_VERSION_CODE` in `gradle.properties`.
   - It prints `>>> <path>`, `>>> versionName …` and `>>> versionCode …` in cyan — use those to
     confirm the exact filename/code; confirm `BUILD SUCCESSFUL`.
   - A warm build takes ~1.5 min; a cold one (fresh checkout, new Gradle distro) several — run it
     with `run_in_background` and poll; never abandon a running build.
   - **Fast iteration:** `./gradlew :app:assembleDebug` gives a debug APK under
     `app/build/outputs/apk/debug/`, signed with upstream's tracked `app/dev_keystore.jks` — it
     does **not** install over the family-signed app (different key, same id, shared UID). Use it
     only for compile checks; the shippable build is always `buildFork`.

3. **After the build, restore R8's by-products** — upstream tracks `app/mapping.txt`,
   `app/seeds.txt`, `app/usage.txt` and `proguard-rules.pro` rewrites them every release build:
   ```bash
   git checkout -- app/mapping.txt app/seeds.txt app/usage.txt
   ```
   Never stage them (unsandboxed `git status` will otherwise show ~20 MB of noise).

4. **Deliver via the global `/after-build` skill** — no exceptions, no asking. It runs `/adb-check`
   UNSANDBOXED, `adb push`es **this repo's** newest `~/tmp/shiroikuma-termux-gui_*.apk` to
   `/sdcard/tmp/` if the phone is reachable, otherwise `scp`s it to `skhw:~/tmp/`, then announces
   what landed. `~/tmp/` is shared with parallel chats building sister apps — always pick the
   `shiroikuma-termux-gui_*` APK, never merely the newest file there.
   On the phone it **upgrades stock Termux:GUI in place** (same `com.termux.gui`, same family key
   as the re-signed Termux, higher versionCode). If the install is refused with
   `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`, another member of the `com.termux` shared UID is
   still signed with a different key — never "fix" that by changing this repo's keystore.

5. **Never delete or prune older APKs** — not in `~/tmp/`, not in `/sdcard/tmp/`. Every build carries
   a unique `+NNN`; older builds stay where they are so 白い熊 can roll back.

## Upstream build quirks (all upstream's, none ours)

- Unless `GITHUB_ACTIONS` is set, `preBuild` runs `ensure-sources` (`sdkmanager "sources;android-34"`)
  and `gen-proto-keycodes` (regenerates the `KeyCode` enum in `app/src/main/proto/GUIProt0.proto`
  from the SDK sources). With `sources/android-34` installed both are sub-second no-ops
  (`KeyCode enum up-to-date`). If `sdkmanager` cannot reach the network and fails the build,
  `GITHUB_ACTIONS=1 ./gradlew …` skips both cleanly — the committed proto is already current. If
  `gen-proto-keycodes` ever *does* rewrite the proto (a newer `sources;android-34` revision), that
  is an upstream-side change: report it, do not commit it silently.
- The configuration cache is on. Do not add anything to `app/shiroikuma.gradle` that touches
  `project`/`layout`/`rootProject` inside a task action.
- Upstream's own CI ships `assembleDebugMinify` signed with `dev_keystore.jks`; ours is `release`.

## Signing

Release signing is non-interactive: `app/shiroikuma.gradle` reads `keystore.properties` (gitignored,
at the repo root) which points at **`~/.android-keystores/shiroikuma-emacs-termux.jks`**, alias
**`Emacs keystore`** (the alias contains a space — quote it on a command line), PKCS12. This is the
**one keystore of the whole com.termux shared-UID family** (`shiroikuma-termux`, `-api`, `-x11`,
`-gui`, `shiroikuma-emacs`): Android requires every member of a shared UID to be signed with the same
key, so no repo of the family may ever get its own. The password is recorded in
`~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org` and the key is backed up to that directory's
`android-keystores/`. If `keystore.properties` is missing, `buildFork` refuses to run — restore it
from `keystore.properties_sample` rather than working around it. Verify a built APK with
`apksigner verify --print-certs <apk> | grep SHA-256` → `50:b4:7e:8f:…:96:04`.

## Versioning (how the numbers are formed)

- Upstream's own `versionCode 8` / `versionName "1.0.0"` in `app/build.gradle` `defaultConfig` are
  the base; a rebase brings new values in automatically. **Never hand-edit them.**
- `BUILD_NUMBER` in `gradle.properties` is **our** increment, bumped on every `buildFork` and
  **never reset** (the mirror follows upstream's branch tip, whose versionCode stands still; an
  installer compares versionCode alone, so a reset would make every sync a downgrade).
  `LAST_BUILT_VERSION_CODE` is the floor the task refuses to go at or below.
- `versionName = "<upstream name>+<base date>.<HH-MM>.g<sha8>+<NNN>"` — the pin is the upstream
  commit our patches sit on (`git merge-base HEAD main`), in UTC; it moves only on a sync. Zero-padded
  `NNN` so `+002` sorts before `+010`; `versionCode = 8 * 10000 + N` (plain integer).
  See the global `git-versioning` skill.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of
the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
