#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${1:-"$root/build/packages"}
mkdir -p "$out"
out=$(CDPATH= cd -- "$out" && pwd)
stage=$(mktemp -d)
trap 'rm -r "$stage"' EXIT HUP INT TERM
chmod 755 "$stage"
arch=$(dpkg --print-architecture)
case "$arch" in
    amd64|arm64) goarch=$arch ;;
    *) echo "Unsupported build architecture: $arch" >&2; exit 1 ;;
esac
mkdir -p "$stage/DEBIAN" "$stage/usr/bin" "$stage/usr/lib/android-linux-voice-bridge" "$stage/usr/share/doc/android-linux-voice-bridge"
(cd "$root/bridge" && CGO_ENABLED=0 GOOS=linux GOARCH="$goarch" go build -trimpath -o "$stage/usr/bin/sonobus-ssh-bridge" .)
if [ -n "${SONOBUS_DEB:-}" ]; then
    test "$(dpkg-deb -f "$SONOBUS_DEB" Package)" = sonobus
    test "$(dpkg-deb -f "$SONOBUS_DEB" Version)" = 1.7.2-0
    test "$(dpkg-deb -f "$SONOBUS_DEB" Architecture)" = "$arch"
    extracted="$stage/upstream-temp"
    mkdir "$extracted"
    dpkg-deb -x "$SONOBUS_DEB" "$extracted"
    install -m 755 "$extracted/usr/bin/sonobus" "$stage/usr/lib/android-linux-voice-bridge/sonobus"
    rm -r "$extracted"
fi
install -m 755 "$root/linux-voice/voice-bridge" "$stage/usr/bin/voice-bridge"
install -m 755 "$root/linux-voice/route_audio.py" "$root/linux-voice/generate_setup.py" "$root/linux-voice/render_units.py" "$root/linux-voice/verify_firewall.py" "$root/linux-voice/install_system_service.py" "$stage/usr/lib/android-linux-voice-bridge/"
install -m 644 "$root/linux-voice/alsa.conf" "$root/linux-voice/codex-voice-audio.service" "$root/linux-voice/codex-voice-routing.service" "$stage/usr/lib/android-linux-voice-bridge/"
install -m 644 "$root/LICENSE" "$stage/usr/share/doc/android-linux-voice-bridge/copyright"
install -m 644 "$root/LICENSE_EXCEPTION" "$root/README.UPSTREAM.md" "$stage/usr/share/doc/android-linux-voice-bridge/"
install -m 755 "$root/packaging/debian/prerm" "$stage/DEBIAN/prerm"
cat > "$stage/DEBIAN/control" <<EOF
Package: android-linux-voice-bridge
Version: 0.1.0~dev1
Section: sound
Priority: optional
Architecture: $arch
Maintainer: VoLink contributors
Depends: python3, pulseaudio-utils, bpftool, sudo, libasound2t64 | libasound2, libasound2-plugins, libopus0, libfreetype6, libstdc++6
Description: VoLink Android to Linux SSH audio bridge (experimental)
 VoLink components and configuration tools for an SSH audio connection to SonoBus.
EOF
dpkg-deb --build --root-owner-group "$stage" "$out/android-linux-voice-bridge_0.1.0~dev1_${arch}.deb"
