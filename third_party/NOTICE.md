# Third-party code

`terminal-emulator` and `terminal-view` come from [termux/termux-app](https://github.com/termux/termux-app)
at tag v0.118.1 (commit 43317b78c920a48254f8846f5e14b5f873faa271). They derive from
[Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator) and are licensed
under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Modifications for Nautile:
- Gradle build rewritten (Kotlin DSL, AGP 8 namespaces).
- `TerminalSession` gains a `Backend` mode so the emulator can be fed by a remote stream (SSH)
  instead of a local subprocess, and an `InputListener` hook on what the user types.

## Font

`app/src/main/res/font/jetbrains_mono_nerd_regular.ttf` is JetBrains Mono (no ligatures) patched by
[Nerd Fonts](https://github.com/ryanoasis/nerd-fonts) v3.2.1, licensed under the SIL Open Font License 1.1
(see `JetBrainsMono-OFL.txt`). It provides the Powerline / Nerd Font glyphs used by zsh themes such as
Powerlevel10k and Starship.

The other terminal fonts are the *Nerd Font Mono* (single-width glyphs) Regular variants from
Nerd Fonts v3.5.1:

| File | Font | License |
|---|---|---|
| `fira_code_nerd_regular.ttf` | Fira Code | SIL OFL 1.1 (`FiraCode-OFL.txt`) |
| `hack_nerd_regular.ttf` | Hack | MIT + Bitstream Vera (`Hack-LICENSE.md`) |
| `source_code_pro_nerd_regular.ttf` | Source Code Pro (as « SauceCodePro ») | SIL OFL 1.1 (`SourceCodePro-OFL.txt`) |
| `cascadia_code_nerd_regular.ttf` | Cascadia Code (as « CaskaydiaCove ») | SIL OFL 1.1 (`CascadiaCode-OFL.txt`) |

## Remote desktop (core:rdp)

`core/rdp/src/main/jniLibs/<abi>/` holds libraries built by `scripts/build-freerdp.sh` from:

| Library | Version | License |
|---|---|---|
| [FreeRDP](https://github.com/FreeRDP/FreeRDP) (`libfreerdp3`, `libfreerdp-client3`, `libwinpr3`) | 3.33.1 | Apache 2.0 |
| FreeRDP Android JNI layer (`libfreerdp-android`, `client/Android/Studio/freeRDPCore/src/main/cpp`) | 3.33.1 | MPL 2.0 |
| [OpenSSL](https://www.openssl.org) (`libssl`, `libcrypto`) | 4.0.1 | Apache 2.0 |
| [cJSON](https://github.com/DaveGamble/cJSON) | 1.7.19 | MIT |
| [uriparser](https://github.com/uriparser/uriparser) | 1.0.2 | BSD 3-Clause |
| LLVM libc++ (`libc++_shared`, from the NDK) | NDK 29 | Apache 2.0 with LLVM exception |

`core/rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` is adapted from FreeRDP's
aFreeRDP (MPL 2.0, file-level copyleft: its source stays available under MPL 2.0); the changes are
described in its header. The MPL 2.0 allows combining these files with this GPLv3 application.

## AI provider logos

`app/src/main/java/com/nautile/app/ui/common/ProviderLogo.kt` holds path data from
[Lobe Icons](https://github.com/lobehub/lobe-icons) 1.102.0 (MIT). The logos are trademarks of their
owners and are only used to name the service the user configured.
