# Contributing to VoLink

VoLink is under development. Bug reports and small, focused changes are welcome. Please describe the device, Android or Linux version, and steps to reproduce when reporting a problem. Do not include passwords, SSH keys, host fingerprints, or private network addresses in reports.

See [VOICE-LITE.md](VOICE-LITE.md) for build instructions and current limitations. Keep Android and Linux changes compatible with the existing audio protocol, and test changes without disrupting a production voice session.

Useful checks include:

```sh
python3 -m unittest discover -s linux-voice -p 'test_*.py'
(cd bridge && go test -race ./...)
git diff --check
```

For Android changes, build with the SDK and NDK versions in the build guide and describe which emulator or physical devices were tested. For Linux packaging changes, prefer an isolated virtual machine and report the distribution and audio backend used.

Contributions are licensed under the project's GPL-3.0-or-later license; preserve applicable upstream copyright and license notices.
