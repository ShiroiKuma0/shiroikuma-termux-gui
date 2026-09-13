---
name: upstream-new-version
description: Sync the shiroikuma-termux-gui fork onto new upstream commits of termux/termux-gui (branch tip `main`, git-tracked) — fast-forward our mirror branch `main`, rebase `custom`, keep BUILD_NUMBER counting (never reset), build the next +NNN. Use when 白い熊 says upstream has moved, asks to check/update/sync to upstream, or to rebase custom onto the latest Termux:GUI. ALWAYS present the proceed-gated upstream-changes table BEFORE rebasing.
---

# Sync shiroikuma-termux-gui onto new upstream Termux:GUI commits

This fork tracks [termux/termux-gui](https://github.com/termux/termux-gui) — the Termux plugin that
lets command-line programs draw native Android UI. Our `main` mirrors **`upstream/main`** (the
branch tip; termux-gui's default branch is `main`, not `master`); `custom` carries our patches and
is rebased onto it.

**We follow the branch TIP, not release tags** (`Upstream tracking: git` — 白い熊, 2026-09-13).
Upstream has had no stable release since `1.0.0` / versionCode 8 (2023) and keeps developing on
`main`, so a tag-based sync would never fire. A sync therefore happens whenever **new commits** land
on `upstream/main` — and the fork versionName pins the base commit (`+<date>.<HH-MM>.g<sha8>`), so
every sync is visible in the version even though upstream's literal never moves.

> **Never `git push` or `git commit` unprompted.** After the rebase + build you stop and let 白い熊
> test; you push only on their explicit **"Push"** (`custom` needs `--force-with-lease` after a
> rebase; `main` fast-forwards with a plain push).

## Branch / remote model

| Branch | Role | Update mode |
| --- | --- | --- |
| `main` | Mirrors `upstream/main`. No fork work here. | `git merge --ff-only upstream/main` |
| `custom` | Our patches; the working/dev branch and the GitHub default branch. | rebased onto `main` each sync |

`origin` = `git@github.com:ShiroiKuma0/shiroikuma-termux-gui.git` (push). `upstream` =
`https://github.com/termux/termux-gui.git` (fetch only; push URL `DISABLED`).

## Steps

1. **Check whether upstream has moved:**
   ```bash
   cd ~/git/shiroikuma-termux-gui
   git fetch upstream
   git fetch origin
   old=$(git rev-parse main)                                   # capture BEFORE any fast-forward
   if git merge-base --is-ancestor upstream/main main; then
     echo ">>> No new upstream commits. main is already at or above upstream/main. Nothing to do."
   else
     echo ">>> New upstream commits: $(git rev-list --count main..upstream/main) on upstream/main"
     git show main:app/build.gradle          | grep -E 'versionCode |versionName ' | head -2
     git show upstream/main:app/build.gradle | grep -E 'versionCode |versionName ' | head -2
   fi
   ```
   The trigger is `upstream/main` **not** being an ancestor of our `main` — the version literals are
   reported for 白い熊 but almost never change (and when `versionCode` does move, say so loudly: it is
   the one case where `BUILD_NUMBER` may be reset — see step 5). If nothing is new, stop and report
   "already current" with the current base pin.

2. **⛔ PROCEED GATE — present the upstream changes as a table, then STOP.** 白い熊's standing
   requirement: **before** anything is fast-forwarded or rebased, show what the new upstream commits
   actually bring.

   Gather the material from all of these — they complement each other:
   ```bash
   git log --oneline --no-merges "$old"..upstream/main          # what really landed
   git log --merges --format='%s' "$old"..upstream/main         # which PRs were merged
   git log --stat --format='%n### %h  %s%n%b' "$old"..upstream/main   # bodies + files, to judge relevance
   git diff --stat "$old"..upstream/main                        # where the weight is
   git diff "$old"..upstream/main -- Protocol.md | head -80     # protocol changes are what clients feel
   ls fastlane/metadata/android/en-US/changelogs/               # only if a versionCode landed
   gh release view <tag> -R termux/termux-gui                   # only if a tag landed
   ```
   Dependabot bumps (`.github/workflows`, Gradle deps) get **one** row; pure doc/README commits one
   row.

   Present a **descriptive markdown table** — one row per feature/change, in plain language, not raw
   commit subjects:

   | Area | Change | What it means for us |
   | --- | --- | --- |
   | Protocol / bindings | … | … (a new message or a changed field breaks or extends the tgui client libraries 白い熊 uses) |
   | Views / activities | … | … |
   | Service / lifecycle | … | … |
   | Build / deps / NDK | … | … (a new `ndkVersion`, `compileSdk`, protobuf or AGP bump changes what the toolchain needs — check `~/android-sdk` has it) |
   | Docs | … | … |

   Cover features, fixes, protocol changes, and anything touching files our patches own —
   **flag those rows**, they are the likely conflict sites:
   `app/build.gradle` (only its last line is ours), `gradle.properties`, `.gitignore`,
   `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, the launcher mipmaps,
   `fastlane/`, `README.md`, and anything under `app/src/main/java/com/termux/gui/shiroikuma/` or
   wired to it (once Phases 3–4 have landed). A `compileSdk` bump also changes the
   `sources;android-<N>` package `ensure-sources` wants — install it once, like `android-34` was.

   Also state the stack size (`git rev-list --count main..custom`) and the plan.

   **Then stop and wait for 白い熊's explicit go-ahead.** Do not move `main`, do not rebase, do not
   build until they say proceed. If they decline, nothing has been touched.

3. **Fast-forward `main`** (mirror; no fork work lives here) and keep a safety branch:
   ```bash
   git status --short                       # the tree must be clean (unsandboxed — see CLAUDE.md)
   git checkout main
   git merge --ff-only upstream/main
   git branch custom-pre-$(date +%Y-%m-%d) custom    # untouched copy of the stack, in case
   ```
   `main` is pushed only in step 8, together with `custom`.

4. **Rebase `custom`:**
   ```bash
   git checkout custom
   git rebase main
   ```
   Resolve conflicts so **all** our customizations survive (table below). Reconcile, don't drop: if
   upstream restructured a file we patch, port our change to the new structure rather than forcing
   the old diff. Keep **upstream's** `versionCode` / `versionName` literals — `app/shiroikuma.gradle`
   reads and overwrites them, so they are never edited by hand. **If conflicts are significant,
   stop and plan with 白い熊** before continuing. If it goes irrecoverable, `git rebase --abort`
   (only `main` has moved, safely, and `custom-pre-<date>` still holds the old stack).

5. **Do NOT reset the build tail.** `BUILD_NUMBER` in `gradle.properties` keeps counting: the
   rebase moved the merge-base, so the next build picks up the new `+<date>.<HH-MM>.g<sha8>` pin by
   itself, and `versionCode = 8 * 10000 + N` must keep rising or the phone reads the sync as a
   downgrade. The **only** exception: upstream's own `versionCode` literal moved (step 1 said so) —
   then, and only then, `BUILD_NUMBER=1` is allowed, because every new code exceeds the old line
   anyway. `LAST_BUILT_VERSION_CODE` is never edited by hand; `buildFork` maintains it.

6. **Verify our customizations are intact after the rebase:**

   | What | Expected | Where |
   | --- | --- | --- |
   | Installed app id | `com.termux.gui` (upstream's, **unchanged**) | `app/build.gradle` → `defaultConfig.applicationId` |
   | Code namespace | `com.termux.gui` (unchanged) | `app/build.gradle` → `namespace` |
   | Shared UID | `android:sharedUserId="com.termux"` (upstream's, unchanged) | `app/src/main/AndroidManifest.xml` |
   | Fork script hook | the file **ends** with `apply from: 'shiroikuma.gradle'` | `app/build.gradle` |
   | Fork version + signing + task | `upstreamPin` / `forkVersionName` / `forkVersionCode`, `signingConfigs.create('release')`, `tasks.register('buildFork')` | `app/shiroikuma.gradle` |
   | Build tail | `BUILD_NUMBER` unchanged from before the sync, `LAST_BUILT_VERSION_CODE` present | `gradle.properties` |
   | App label | `白い熊 Termux GUI` (once Phase 3 has landed) | `app_name` in `app/src/main/res/values/strings.xml` |
   | Black-yellow icon | yellow line-art foreground + black background (once Phase 2 has landed) | `app/src/main/res/mipmap-anydpi-v26/`, `mipmap-*/` |
   | 白い熊 Termux GUI UI page | present and wired (once Phase 4 has landed) | `app/src/main/java/com/termux/gui/shiroikuma/`, manifest |
   | De-branding | our name + our GitHub links in every user-visible string and root doc | `values/strings.xml`, `README.md`, `fastlane/` |
   | Committed agent files | `CLAUDE.md`, `.claude/skills/` tracked; only `.claude/settings.local.json` ignored | `.gitignore` |

   Watch for **new upstream strings that reintroduce "Termux:GUI"** or `termux/termux-gui` links.
   Grep after the rebase and re-de-brand (the CLAUDE.md rebase grep guard):
   ```bash
   grep -rn "Termux:GUI\|termux/termux-gui" app/src/main/res/values/strings.xml fastlane README.md | grep -v ShiroiKuma0
   tail -1 app/build.gradle     # must be: apply from: 'shiroikuma.gradle'
   ```
   Sanity check the script still evaluates and shows the new pin:
   `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew :app:buildFork --dry-run --console=plain < /dev/null`
   (a dry run executes nothing — no copy, no bump — but runs the configuration-time guards).

7. **Build the next `+NNN`** via the **build-apk** skill
   (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew buildFork --console=plain < /dev/null`),
   restore R8's tracked by-products (`git checkout -- app/mapping.txt app/seeds.txt app/usage.txt`),
   then deliver via the global **`/after-build`** skill (no transfer prompt). Its versionName
   carries the new pin: `1.0.0+<new date>.<HH-MM>.g<new sha8>+<NNN>`.

8. **Stop.** Let 白い熊 test. On their explicit **"Push"** — and only then:
   ```bash
   git checkout main   && git push origin main                      # fast-forward, safe
   git checkout custom && git push --force-with-lease origin custom # rebased history
   git branch -d custom-pre-<date>                                  # once the push is confirmed
   ```

## Notes

- Keep our changes a **small, legible layer** on top of upstream — prefer rebasing (linear history)
  over merging, so the customization set stays easy to audit and replay.
- **The package id never changes.** If a conflict tempts a rename of `com.termux.gui` — don't;
  every tgui client library broadcasts to `com.termux.gui/.GUIReceiver`.
- **The key never changes.** The whole `com.termux` shared-UID family is signed with
  `~/.android-keystores/shiroikuma-emacs-termux.jks`; `keystore.properties` is gitignored and
  untouched by a rebase.
- If upstream bumps `ndkVersion`, `compileSdk` or the protobuf plugin, the toolchain row of the gate
  table must say what `~/android-sdk` needs (`sdkmanager "ndk;<ver>"`, `"sources;android-<N>"`,
  `"platforms;android-<N>"`) — install once, then build.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of
the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
