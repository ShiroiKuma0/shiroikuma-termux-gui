# CLAUDE.md — guide for Claude Code in this repo

**shiroikuma-termux-gui** — 白い熊's fork of [Termux:GUI](https://github.com/termux/termux-gui), the
Termux plugin that lets command-line programs draw native Android UI over a Unix socket (Kotlin +
one JNI library, protobuf/JSON protocol; GPL-3.0). Keeps upstream's install identity
`com.termux.gui` (**deliberately not renamed** — see below) and shared UID `com.termux`, and ships as
**白い熊 Termux GUI**, signed with the one key of the whole 白い熊 com.termux family.

This repo (`ShiroiKuma0/shiroikuma-termux-gui`) is a fork. We track upstream's **branch tip** on
`main` and layer our customizations on `custom`.

## Read this first

Before any work, read **`.claude/skills/build-apk/SKILL.md`** (canonical build + delivery) and
**`.claude/skills/upstream-new-version/SKILL.md`** (upstream sync + rebase, with the mandatory
proceed-gated upstream-changes table). Publishing a release uses the **global** `/publish-version`
skill — this repo has no local copy.

## Fork workflow — READ THIS FIRST

### Git remotes & branches

- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-termux-gui.git` (push here).
- `upstream` → `https://github.com/termux/termux-gui.git` (fetch only; its push URL is `DISABLED`).
- `main` — mirrors `upstream/main`, **fast-forward only**, no fork work. (termux-gui's default
  branch is `main`, not `master` — every `merge-base` / sync command in this repo says `main`.)
- `custom` — all our work, rebased onto `main` on each upstream sync, and the GitHub default branch
  so the repo page lands on the fork.

**Upstream tracking: `git`** (the branch tip, not release tags — 白い熊, 2026-09-13). Upstream has
had no stable release since `1.0.0` (versionCode 8) and keeps developing on `main`; the version
literal therefore says nothing about how current the fork is, so the fork versionName pins the
upstream base: `<upstream>+<base date>.<HH-MM>.g<sha>+<BUILD_NUMBER, 3 digits>`. See the global
**`git-versioning`** skill.

### Our customizations (install identity + build)

| What | Value | Where |
| --- | --- | --- |
| applicationId | `com.termux.gui` (**UNCHANGED** — every termux-gui client library, Python/C/… bindings included, broadcasts to `com.termux.gui/.GUIReceiver` and connects to that package's socket; renaming would orphan every client) | `app/build.gradle` → `defaultConfig` (upstream's line, untouched) |
| namespace (R/BuildConfig pkg) | `com.termux.gui` (**never rename**) | `app/build.gradle` |
| sharedUserId | `com.termux` (literal in the manifest; the reason the whole family is signed with one key) | `app/src/main/AndroidManifest.xml` (upstream's line, untouched) |
| App label | `白い熊 Termux GUI` — **pending (Phase 3)**; today upstream's `Termux:GUI` | `app_name` in `app/src/main/res/values/strings.xml` |
| App icon | black-yellow traced (yellow `#FFFF00` line-art on black) — **pending (Phase 2)**; today upstream's | `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` + the `mipmap-*/` webp rasters, `fastlane/metadata/android/en-US/images/icon.png` |
| 白い熊 Termux GUI UI | the house black-yellow page + Export/Import + 保存復元 automation rows — **pending (Phase 4)** | to be added under `app/src/main/java/com/termux/gui/shiroikuma/` |
| Version tail | `versionName = "<upstream>+<base date>.<HH-MM>.g<sha8>+NNN"`, `versionCode = <upstream code>*10000+N` | `app/shiroikuma.gradle` (applied by the last line of `app/build.gradle`) |
| Signing | gitignored `keystore.properties` → `~/.android-keystores/shiroikuma-emacs-termux.jks` (alias `Emacs keystore`, **the one family key**) | `app/shiroikuma.gradle` → `signingConfigs.release` |
| Fork links | `https://github.com/ShiroiKuma0/shiroikuma-termux-gui` everywhere the app/docs link out — **pending (Phase 3)** | README, `fastlane/`, any in-app link |
| De-branding | our name + our GitHub links everywhere user-visible; code namespaces, log tags, protocol names, upstream copyright untouched — **pending (Phase 3)** | `values/strings.xml`, root docs |

**Why the package id stays `com.termux.gui`** (decision 白い熊, 2026-09-13): the bindings
(`termux-gui-python`, `-c`, `-bash`, …) and every program built on them hardcode the package name
for the start-broadcast and the socket handshake, and the app must share UID `com.termux` with
Termux to read the caller's files. Renaming it means forking every client. So the fork is told apart
from stock by label, icon, links, signing key and our UI page — **not** by id. On the phone it
**upgrades stock Termux:GUI in place** (same id, same key as the rest of the re-signed family,
higher versionCode).

### Versioning & APK naming

- The upstream base lives in `app/build.gradle` `defaultConfig` as upstream's own
  `versionCode 8` / `versionName "1.0.0"` literals. `app/shiroikuma.gradle` — applied by the **last
  line** of `app/build.gradle` — reads them and overwrites the pair, so a rebase brings a new base
  in automatically. **Never hand-edit those two literals.**
- The pin is `git merge-base HEAD main` (the upstream commit our patches sit on — not our HEAD, not
  `main`'s tip) shortened to 8 chars, plus that commit's own committer date **and time, in UTC**
  (`%ct` epoch → `yyyy-MM-dd.HH-mm`; never `--date=format:`, which renders the commit's own zone).
  It moves only on a sync. Today: `1.0.0+2025-06-25.23-38.gbe24a5f3+NNN`.
- `BUILD_NUMBER` (in `gradle.properties`) is our per-build `N`:
  `versionName = "<base>+<YYYY-MM-DD>.<HH-MM>.g<sha8>+<NNN>"`, `versionCode = 8 * 10000 + N`
  (`80001`, `80002`, …). Zero-padded to 3 digits **in the name only**; `versionCode` and
  `gradle.properties` keep the plain integer. The `buildFork` task bumps it after every successful
  build.
- **`BUILD_NUMBER` runs MONOTONICALLY and is NEVER reset** — upstream's `versionCode 8` has stood
  since 2023 and a git-tracked base moves without it. An installer compares `versionCode` and
  nothing else; the date and sha in `versionName` are cosmetic, so a fresh pin does not make a build
  newer, and resetting `N` on a sync would send `versionCode` *backwards* and make every sync a
  downgrade. Reset to `1` **only** if upstream's own `versionCode` literal ever moves (which then
  lifts every new code above the old line anyway).
- `buildFork` enforces this: it records `LAST_BUILT_VERSION_CODE` in `gradle.properties` and
  **refuses to build** a `versionCode` that does not exceed it. Raise `BUILD_NUMBER` past the last
  built tail; never lower it. It also refuses to overwrite an APK that already exists in `~/tmp/`.
- APK: `shiroikuma-termux-gui_<versionName>_universal.apk`, copied to `~/tmp/`. Upstream builds no
  ABI splits — the one release APK carries the JNI library for all four ABIs, hence `_universal`.
  The versionName contains no `_` and no `~` (Debian and `git check-ref-format` respectively — see
  the global `git-versioning` skill).
- `+001` is reserved for the first de-branded build (Phase 3): the toolchain-proving build of
  2026-09-13 ran `assembleRelease` without `buildFork`, so it consumed no counter.

### Build commands

```bash
# Our build: signed release → ~/tmp + bump BUILD_NUMBER (use this)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew buildFork --console=plain < /dev/null
# Release APK only (no copy / no bump) → app/build/outputs/apk/release/app-release.apk
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew :app:assembleRelease --console=plain < /dev/null
```

Upstream's own CI builds `assembleDebugMinify` (signed with the tracked `app/dev_keystore.jks`);
that is upstream's release channel, not ours — **we ship `release`** (R8 minify, no resource
shrink, our key).

**Build-time quirks of this repo, all upstream's:**

- Unless `GITHUB_ACTIONS` is set, `preBuild` runs two upstream tasks: `ensure-sources` (calls
  `sdkmanager "sources;android-34"`) and `gen-proto-keycodes` (rewrites the `KeyCode` enum in
  `app/src/main/proto/GUIProt0.proto` from `sources/android-34/android/view/KeyEvent.java`). The
  sources package was installed once on 2026-09-13 (`~/android-sdk/sources/android-34`, 215 MB);
  with it present the `sdkmanager` call is a 0.5 s no-op and the proto rewrite is a no-op
  (`KeyCode enum up-to-date`). If `sdkmanager` ever cannot reach the network, `GITHUB_ACTIONS=1`
  in the environment skips both tasks cleanly (the committed proto is already current).
- R8 writes `app/mapping.txt`, `app/seeds.txt`, `app/usage.txt` (`-printmapping` & co. in
  `app/proguard-rules.pro`), and upstream **tracks** those three files. Every release build
  rewrites them (~20 MB of noise in `git status`). **Never stage them** — restore with
  `git checkout -- app/mapping.txt app/seeds.txt app/usage.txt` after a build; only a deliberate
  upstream-style refresh commits them.
- The configuration cache is **on** (`org.gradle.unsafe.configuration-cache=true`). Everything
  in `app/shiroikuma.gradle` is written for it (`providers.exec`, values captured at configuration
  time); keep it that way when editing.

### Toolchain

- JDK **21** at `/usr/lib/jvm/java-21-openjdk-amd64` (the host default `java` is JDK 11; Gradle
  8.10 and the `sdkmanager` both need 17+ — always set `JAVA_HOME`).
- Android SDK at `~/android-sdk` (`local.properties` → `sdk.dir=/home/shiroikuma/android-sdk`,
  gitignored); `compileSdk 34`, `targetSdk 34`, `minSdk 24`; **NDK `23.1.7779620`** (pinned by
  upstream's `ndkVersion`, present under `~/android-sdk/ndk/`) for the CMake JNI library
  `app/src/main/cpp/gui.cpp` and `hbuffers/`'s native part; `sources;android-34` (see quirks).
- Gradle wrapper 8.10.2, AGP 8.8.1, Kotlin 2.0.21, protobuf-gradle-plugin 0.9.4 (protoc 4.29.3,
  `lite` runtime).
- The build also needs `ANDROID_HOME` in the environment for a background shell — pass it inline
  as in the commands above.

## Architecture (upstream Termux:GUI)

Three Gradle modules: `:app` (the plugin), `:hbuffers` (hardware-buffer helper library, JNI),
`:externaltest` (upstream's test client — not shipped).

| Area | Where |
| --- | --- |
| Application class, settings | `app/src/main/java/com/termux/gui/App.kt`, `Settings.kt`, `GUIConfigActivity.kt` (the launcher-visible settings screen) |
| Foreground service + socket accept loop | `GUIService.kt`, `ConnectionHandler.kt`, `GUIReceiver.kt` (the start broadcast clients send to `com.termux.gui/.GUIReceiver`) |
| Activities clients draw into | `GUIActivity.kt`, `GUIActivityDialog.kt`, `GUIActivityLockscreen.kt`, `GUIWebViewJavascriptDialog.kt` |
| Home-screen widget | `GUIWidget.kt`, `GUIWidgetConfigurationActivity.kt`, `res/xml/widget.xml` |
| Wire protocol | `app/src/main/proto/GUIProt0.proto` (protobuf v0) and `protocol/{json,protobuf,shared}/v0/` handlers; documented in `Protocol.md` |
| Custom views | `app/src/main/java/com/termux/gui/views/` |
| JNI (EGL/GLES2 surfaces) | `app/src/main/cpp/gui.cpp` (CMake), `hbuffers/src/main/cpp/` |
| Store metadata | `fastlane/metadata/android/en-US/` |
| Upstream CI | `.github/workflows/build.yml` (debugMinify artefact only) |

## Hard rules

- **Never rename `com.termux.gui`** — not the `applicationId`, not the `namespace`, not the manifest's
  `sharedUserId`. Every tgui client and the shared-UID contract with Termux depend on all three.
- **Same keystore as the whole com.termux shared-UID family** — `shiroikuma-termux`, `-api`,
  `-x11`, `-gui` and `shiroikuma-emacs` are all signed with
  `~/.android-keystores/shiroikuma-emacs-termux.jks`. A different key on any one member makes its
  install fail with `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`; changing the key later means
  uninstall + restore for **everything**. Never create a per-repo key here.
- **Never commit/push unprompted.** Build, deliver, and stop; 白い熊 tests. Commit + push only on
  their explicit **"Push"** — that means commit, then `git push --force-with-lease origin custom`
  (`custom` is rebased on every sync; `main` fast-forwards with a plain push).
- `keystore.properties`, `local.properties` and `*.jks` are gitignored — never commit them.
  (Upstream's tracked `app/dev_keystore.jks` is its debug key and stays as upstream has it.)
- **Always run `adb`, `scp` and `git status`/`git diff` with `dangerouslyDisableSandbox: true`**
  (the sandbox blocks adb's server socket and invents phantom untracked dotfiles at the repo root).
- **After ANY functional change, build and deliver automatically** — the global `/after-build`
  standing authorization; never wait for "build it". Every build bumps `BUILD_NUMBER`; never
  overwrite or delete an older APK, in `~/tmp/` or on the phone.
- On new upstream commits, run the **`upstream-new-version`** skill — it presents the
  proceed-gated upstream-changes table **before** any rebasing, then fast-forwards `main`, rebases
  `custom`, and builds the next `+NNN` (**no** `BUILD_NUMBER` reset).
- **Rebase grep guard.** After every rebase, before building:
  `grep -rn "Termux:GUI\|termux/termux-gui" app/src/main/res/values/strings.xml fastlane README.md | grep -v "ShiroiKuma0"`
  must list only what Phase 3 has knowingly left (upstream attribution, the wiki/protocol links) —
  anything else is a re-brand upstream slipped back in. Also check `app/build.gradle` still ends
  with `apply from: 'shiroikuma.gradle'`.
- Never `adb uninstall` to get past an install refusal. `adb install -r -d` is the
  deliberate-rollback escape hatch, and a signature refusal means a key mismatch somewhere in the
  shared-UID family — something to diagnose, never to paper over.

### CHANGELOG.md is unified — never fork it, never overwrite upstream's

Upstream ships **no** `CHANGELOG.md` (its per-versionCode notes live in
`fastlane/metadata/android/en-US/changelogs/<code>.txt`, which stay upstream's). Ours is therefore a
root `CHANGELOG.md` that starts life as pure fork content, **our sections on top**, newest first:

- Fork entries are `## 白い熊 Termux GUI <tag> — <YYYY-MM-DD>`, one per published release, each
  carrying (a) our changes since the previous release and (b) an **"Upstream since `<previous base
  sha8>`"** subsection distilled from the proceed-gate table of the syncs in between — that is the
  "merged changelog" published with each release. Only the first release lists everything.
- Should upstream ever add a `CHANGELOG.md`, keep theirs below ours byte for byte and the diff a pure
  insertion (`git diff CHANGELOG.md | grep -c '^-[^-]'` → `0`); on a conflict the resolution is
  "keep both blocks, ours still on top".
- The same text goes in the GitHub release notes. The **global `/publish-version` skill** does both.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer, nor a "🤖 Generated with Claude Code" /
Anthropic-attribution line, to commit messages or PR bodies in this repo. End the message at the last
line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
