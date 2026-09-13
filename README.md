<div align="center">

<img src="app/src/main/ic_launcher-playstore.png" width="120" alt="白い熊 Termux GUI icon" />

# 白い熊 Termux GUI

</div>

**白い熊's fork of [Termux:GUI](https://github.com/termux/termux-gui)**, the Termux plugin that lets
command-line programs draw native Android UI. The app id stays **`com.termux.gui`** on purpose — every
tgui client library and the `com.termux` shared UID depend on it — so this build **upgrades stock
Termux:GUI in place** and is signed with the one key of the whole 白い熊 com.termux family
(白い熊 Termux, Termux API, Termux X11, Termux GUI, Emacs). What changes: the label, the black-yellow
traced icon, the links, and later the 白い熊 Termux GUI page. Not on F-Droid — the APKs are on
**[our releases page](https://github.com/ShiroiKuma0/shiroikuma-termux-gui/releases)**.

---

# Termux:GUI

[<img src="https://img.shields.io/github/v/release/ShiroiKuma0/shiroikuma-termux-gui?include_prereleases"/>](https://github.com/ShiroiKuma0/shiroikuma-termux-gui/releases)
[<img src="https://img.shields.io/f-droid/v/com.termux.gui"/>](https://f-droid.org/de/packages/com.termux.gui/)


This is a plugin for [Termux](https://github.com/termux/termux-app) that enables command line programs to use the native android GUI.  
  
In the examples directory you can find demo videos, sample code is provided in the tutorials of the official language bindings.

[There are also prepackaged programs you can use](https://github.com/tareksander/termux-gui-package). The `termux-gui-package` package contains a collection of small programs.

See the [installation notes](https://github.com/termux/termux-app#installation) on general instructions to install Termux plugins. Clicking on the F-Droid badge above brings you to the F-Droid page for the plugin, the release badge to the GitHub releases.

If you want to use overlay windows or be able to open windows from the background, go into the app settings for Termux:GUI, open the advanced section and enable "Display over other apps".  

For developing applications using the plugin, see [Developing.md](./Developing.md).

### Features

- buttons, switches, toggles, checkboxes, text fields, scrolling, LinearLayout
- custom notifications
- custom widgets
- shared image buffers
- GLES2 acceleration
- WebView
- Dialogs
- Lockscreen Activities
- Wake-lock
- ...and more



### Comparison with native apps

| Native app                                                | With Termux:GUI                                                             |
|-----------------------------------------------------------|-----------------------------------------------------------------------------|
| Has to be installed                                       | Program can be run in Termux                                                |
| Full access to the Android API                            | Access to the Android API through Termux:GUI and Termux:API                 |
| Limited to C, C++, Kotlin and Java for native development | Any programming language can be used, prebuilt library for python available |
|                                                           | Lower performance caused by IPC                                             |
| Accessing files in Termux only possible via SAF           | Direct access to files in Termux                                            |
| Has to be started with `am` from Termux                   | Can be started like any other program in Termux                             |
|                                                           | Can receive command line arguments and output back to the Terminal          |


## Language Bindings

### Official

- [Python](https://github.com/tareksander/termux-gui-python-bindings)
- [C/C++](https://github.com/tareksander/termux-gui-c-bindings)
- [Bash](https://github.com/tareksander/termux-gui-bash)

### Community

Not maintained by the plugin maintainer:

- [Rust](https://github.com/sweetkitty13/tgui-rs)

