# Contributing to UltraDAW

Thanks for helping! UltraDAW drives a real DAW from real hardware, so the bar for "it works" is
higher than "it compiles". These rules keep `main` always installable on a CoreBAR.

## How changes get in
1. **Fork** the repo and create a branch from `main` (`feature/…`, `fix/…`).
2. Open a **pull request** against `main`. Direct pushes to `main` are blocked.
3. A PR can be merged only when:
   - the **CI check "Build & test"** passes (unit tests + debug APK build);
   - a **maintainer has approved** it (see `.github/CODEOWNERS`);
   - every review conversation is **resolved**;
   - the branch is **up to date** with `main`.
4. PRs are merged with **squash merge**, so keep the PR title meaningful: it becomes the commit message.

## What "it works" means here
Every PR must state in its description how it was verified (the PR template asks for it):

| Change touches… | Required evidence |
|---|---|
| `rtpmidi/` or `mcu/` (protocol) | New or updated **unit tests** that cover the change. Protocol code must stay pure and testable. |
| `ui/` | A **screenshot at 1424×280** (emulator AVD or a real CoreBAR). |
| Connection / DAW behaviour | Tested **end to end** with a DAW: say which one (Logic, Ableton…), which macOS version, and whether it ran on the emulator or a real CoreBAR. |

The maintainer re-tests protocol and connection changes on a real CoreBAR with Logic Pro before merging.

## Local setup
```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
```
- Emulator: create an AVD with a **1424×280** screen at density **160** (the real CoreBAR runs Android 10 / API 29).
- Real CoreBAR: enable *Settings → Developer options → ADB over network*, then `adb connect <bar-ip>:5555`.

## Ground rules
- **Target the real device**: `minSdk 29` (Android 10) and 32-bit ARM. Don't add native code or APIs above 29 without a fallback.
- **No vendor code**: don't commit Sanytron firmware, APKs, decompiled sources or credentials. Describe protocol findings in your own words.
- **No secrets**: signing keys and `keystore.properties` stay out of Git.
- Keep dependencies minimal (AndroidX, Compose, coroutines). Discuss new ones in an issue first.
- One topic per PR. Large features: open an issue first so we can agree on the approach.

## Reporting bugs
Use the **Bug report** issue template. Include the UltraDAW version, DAW and version, macOS version,
and, if you can, a MIDI trace from a debug build (`adb logcat -s UltraDAW/MIDI`).

By contributing you agree your contribution is licensed under the project's MIT license.
