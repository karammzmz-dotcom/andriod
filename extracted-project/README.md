# Arduboy Black & White — Android

A monochrome Arduboy launcher and emulator for Android. The app starts in an offline game library and runs Arduboy `.hex` games through the Arduous libretro core. Catacombs of the Damned remains available as the original native port, with its original ArduboyTones sound patterns now rendered as square-wave audio through Android `AudioTrack`.

## What is included

- Black-and-white launcher with an offline library built into the APK.
- Catacombs of the Damned native Android port.
- Up to 40 additional Arduboy games selected from the public ArduboyCollection catalogue, only where `game.ini` declares an allowlisted permissive SPDX licence (MIT, Apache-2.0, BSD-3-Clause, BSD-2-Clause, ISC, or CC0-1.0).
- Extra games from the GitHub repos listed in `scripts/extra_repos.txt`. Each repo is included only if its LICENSE is permissive and it yields a valid `.hex` (prebuilt, or compiled with PlatformIO); others are skipped.
- Arduous emulator core, built from source for Android arm64-v8a by GitHub Actions.
- Original Catacombs sound sequences played at their encoded frequencies and 1/1024-second durations. Emulator-core games use the core's audio stream.
- USB/Bluetooth gamepad and keyboard input. **No on-screen gameplay controls.**
- No internet connection is needed after installing the APK; ROMs and emulator core are packaged into it.

The game catalogue and Arduous core are downloaded by GitHub Actions during the APK build, not at app runtime. The build embeds each game's title, author, description, source path, and licence ID, plus the applicable licence texts. The exact Arduous source commit used is recorded in `app/src/main/assets/ARDUOUS_SOURCE.txt` inside the built APK.

## Controls

| Action | Keyboard | Controller |
|---|---|---|
| Browse library | Arrow keys | D-pad / left stick |
| Launch selected game | Enter / Space | A / X |
| Up / down / left / right in game | Arrow keys or W/A/S/D | D-pad / left stick |
| Arduboy A | Z / Enter / Space | A / X |
| Arduboy B | X / Escape | B / Y |
| Toggle sound | M | Start |
| Return to library | Android Back | Android Back |

The exact A/B labels depend on the controller. Touchscreen taps do not control gameplay; the library list can also be selected with a controller or keyboard.

## Build with GitHub Actions

1. Extract this ZIP.
2. Upload the **contents** of the extracted folder to the root of your GitHub repository. The repository root must contain `settings.gradle`, `app/`, `scripts/`, and `.github/`.
3. If you uploaded the extracted contents, open **Actions** and run **Build Arduboy Black & White APK**. If you uploaded only the ZIP, run **Unzip Source and Build Arduboy Black & White APK** instead.
4. Download the `Arduboy-Black-and-White-debug` artifact from the completed run.
5. Extract the artifact ZIP and install `app-debug.apk` on an Android ARM64 device.

The build workflow installs Android SDK/NDK/CMake, clones Arduous with its simavr submodule, compiles the core for arm64-v8a, clones the ArduboyCollection catalogue, prepares the licensed offline ROM bundle, and builds the APK. `unzip.yml` is the manual workflow variant.

## Technical details

- Android minimum SDK 23; compile SDK 35.
- ARM64-v8a build (appropriate for modern Android tablets and phones).
- Catacombs uses its original 128×64 framebuffer at 30 FPS.
- Arduboy emulator games use a 128×64 monochrome output at 60 FPS.
- Mono 44.1 kHz Android audio output.
- No touch gameplay input or virtual buttons.

## Licences and attribution

- Catacombs of the Damned original source: James Howard, MIT. The original MIT notice is in `THIRD_PARTY_LICENSE.txt`.
- Arduous Arduboy emulator core: GPL-3.0. Its GPL text is bundled in the APK assets; its exact source commit is recorded in `ARDUOUS_SOURCE.txt`.
- ArduboyCollection metadata catalogue: BSD-3-Clause. Each additional ROM is included only when its own `game.ini` declares one of the allowlisted permissive licences. The build bundles the relevant SPDX licence texts and generates `THIRD_PARTY_GAMES.txt` credits.
- This project is distributed under GPL-3.0 because it incorporates the Arduous emulator core. See `LICENSE`.

The bundled catalogue is not the complete Arduboy community site. Games without an explicit allowlisted licence, games without a local `.hex`, and applications/demos outside the selected game categories are not bundled.
