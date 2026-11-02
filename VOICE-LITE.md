# VoLink (work in progress)

This is a voice-focused derivative of [SonoBus](https://github.com/sonosaurus/sonobus), based on commit `35f1062`. It routes audio between an Android device and a Linux host over SSH. It is not affiliated with OpenAI or SonoBus.

**Experimental software.** Generic installation, security hardening, microphone release across Android versions, and real-device audio still need verification. Do not expose the unencrypted SonoBus UDP port to an untrusted network. This repository is not a replacement for a security audit.

## Overview

The Android app connects using an SSH address, port, username and password. On first connection, it displays the server's SHA256 host-key fingerprint for the user to verify and confirm; it stores the accepted fingerprint for that address and port. Later connections verify it automatically and refuse a changed key. The SSH process starts `/usr/bin/sonobus-ssh-bridge --stdio`, falling back to `~/.local/bin/sonobus-ssh-bridge` for existing personal installations. The bridge forwards framed datagrams to a local SonoBus endpoint. The Linux audio server uses PipeWire/PulseAudio virtual devices.

The app stores an optional password encrypted with Android Keystore; it does not back up its preferences. The entire channel between the Android SSH client and the SSH host is encrypted. A relay such as FRPC is optional. The SonoBus UDP protocol itself is not encrypted and must be confined to loopback with an independently verified OS firewall or equivalent restriction.

Add the **VoLink 连接** tile from Android Quick Settings to connect or disconnect. Starting a connection opens the app so Android can grant microphone access; it may first ask for permission or connection settings. If a password was not saved, it must be entered again after the app process exits. The notification's separate microphone action closes or reopens the audio input stream without disconnecting playback. This has been exercised on an Android 15 emulator; microphone release and background behavior must still be tested on physical devices. Android background microphone restrictions vary by OS version; if starting capture from a notification fails, return to the app.

While a connection is active, VoLink retries after a network interruption or a change of the default network, including a VPN switch. A manual disconnect cancels retries. A rejected SSH host fingerprint or incorrect credentials stop retrying and require correction in settings. Reconnect works while the app and its foreground service remain running; Android may stop them under device-specific conditions. When both sides support heartbeats, the Linux bridge also exits after 45 seconds without a heartbeat, cleaning up a connection left behind by an abrupt network loss. Older bridges use the original protocol and rely on SSH to detect such disconnects. Recovery and a switch between emulator network types have been tested; a real VPN switch and device-specific power management have not.

## Android build

JDK 17, Android SDK 35, Build Tools 35.0.0, NDK 25.2.9519653 and CMake 3.22.1 are required. Opus 1.5.2 is built from the included source in `deps/opus`.

```bash
cd android-lite
ANDROID_HOME="$HOME/Android/Sdk" ./gradlew assembleDebug
```

To build for an x86_64 emulator, pass `-PvoiceAbi=x86_64`. Never commit signing keys or passwords. Release signing uses `VOICE_SIGNING_STORE` and `VOICE_SIGNING_PASSWORD`.

For the first connection, obtain the Linux host's SSH fingerprint through a trusted terminal or other trusted channel. For example, for an Ed25519 host key:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Compare the displayed `SHA256:...` fingerprint and key type with the confirmation shown by the app. Confirm only if they match; this first check is needed to protect against an unexpected SSH host. The app remembers the accepted fingerprint and checks it on later connections. A changed fingerprint is rejected, not automatically trusted. If the computer's SSH key was intentionally replaced, verify the replacement through a trusted channel, disconnect, and explicitly clear the saved fingerprint in connection settings before confirming the new one. Changing the configured address or port clears the saved fingerprint and requires a new confirmation. The app never displays a first-connection confirmation while it is in the background.

## Linux: development status

Build an experimental Debian package using the upstream SonoBus 1.7.2-0 package for the target architecture:

```bash
SONOBUS_DEB=/path/to/sonobus_1.7.2-0_amd64.deb ./packaging/debian/build.sh
sudo apt install ./build/packages/android-linux-voice-bridge_0.1.0~dev1_amd64.deb
voice-bridge setup
```

The build verifies the supplied package's name, version and architecture and includes its SonoBus executable. Without `SONOBUS_DEB`, install SonoBus separately and ensure `sonobus` is on PATH. A working SSH server and PipeWire/PulseAudio user session are required.

Run `setup` as the desktop user, not root. It creates a preset and an ALSA configuration, then uses sudo to install, enable and start the system service and installs a user audio-routing service. It enables systemd user lingering so the audio session can run before login and after logout; this remains enabled after uninstall because other services might rely on it. The routing service changes the user's default audio devices. If setup cannot finish after creating the configuration, resolve the reported issue and run `voice-bridge activate`. `voice-bridge setup --prepare-only` only creates configuration; `voice-bridge prepare-service` renders units for inspection. `voice-bridge status` checks the installation. `voice-bridge uninstall` stops and removes the services and preserves the configuration; remove the Debian package separately.

The service refuses to start SonoBus unless both systemd cgroup firewall programs can be verified. This requires a supported kernel, systemd and bpftool; systems without that support fail closed. Installation, reboot, firewall isolation and failure rollback have been tested in an isolated Ubuntu 24.04 VM, using PipeWire and PulseAudio; other systems and real devices still need testing before a release.

The experimental package uses its own `android-linux-voice-bridge` configuration and audio device names. Its bridge and generated preset use UDP port 12001; they must be used together. This avoids the personal installation's configuration and UDP port 12000, but both versions can still change the same user's audio defaults. Do not start the experimental routing alongside an existing installation.

`bridge/` contains the Go SSH-to-local-UDP bridge; run `go test -race ./...` there. `linux-voice/` contains the routing script, preset generator and service templates. The package renders the system template for the target user during activation.

The routing script currently changes the host's default input and output devices. This affects other audio applications. On a normal stop it restores the previous defaults if the user has not selected different ones in the meantime; crash recovery needs more testing. Do not run on a shared or production machine without reviewing the code and security implications.

## License

SonoBus is GPLv3; see `LICENSE`, `LICENSE_EXCEPTION`, `README.UPSTREAM.md` and the upstream third-party notices. New project code is GPL-3.0-or-later where indicated. Distribution of builds must include the appropriate corresponding source and third-party license notices. Verify the exact dependency notices before publishing binaries.
