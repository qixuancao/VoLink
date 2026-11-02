#!/usr/bin/env python3
"""Exercise a built package in a temporary directory without installing it."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

package = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    subprocess.run(['dpkg-deb', '-x', str(package), str(root / 'package')], check=True)
    env = dict(os.environ, HOME=str(root / 'user'))
    tool = root / 'package/usr/bin/voice-bridge'
    subprocess.run([str(tool), 'setup', '--prepare-only'], env=env, check=True)
    config = root / 'user/.config/android-linux-voice-bridge'
    preset = config / 'voice.sonobus'
    initial = preset.read_bytes()
    assert (config / 'alsa.conf').is_file()
    second = subprocess.run([str(tool), 'setup', '--prepare-only'], env=env, capture_output=True)
    assert second.returncode != 0 and preset.read_bytes() == initial
    fake_bin = root / 'bin'
    fake_bin.mkdir()
    executable = fake_bin / 'sonobus'
    executable.write_text('#!/bin/sh\nexit 0\n')
    executable.chmod(0o755)
    env['PATH'] = str(fake_bin) + os.pathsep + env['PATH']
    subprocess.run([str(tool), 'prepare-service'], env=env, check=True)
    unit = (config / 'units/android-linux-voice-audio.service').read_text()
    bundled = root / 'package/usr/lib/android-linux-voice-bridge/sonobus'
    expected = bundled if bundled.exists() else executable
    assert '@UID@' not in unit and str(expected) in unit
    assert 'IPAddressDeny=any' in unit and 'IPAddressAllow=localhost' in unit
    repeat = subprocess.run([str(tool), 'prepare-service'], env=env, capture_output=True)
    assert repeat.returncode != 0
    print('Setup creates configuration and refuses to overwrite it.')
