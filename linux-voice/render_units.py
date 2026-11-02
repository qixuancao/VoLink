#!/usr/bin/env python3
"""Render service units for review; this does not install or start them."""
import os
from pathlib import Path
import pwd
import re
import shutil
import sys


def main():
    bundled = Path(__file__).resolve().parent / 'sonobus'
    binary = str(bundled) if bundled.is_file() else shutil.which('sonobus')
    if not binary:
        raise SystemExit('SonoBus executable not found in PATH')
    user = pwd.getpwuid(os.getuid())
    home = str(Path.home())
    # Avoid ambiguous systemd syntax. More path formats can be added once the
    # service renderer has been tested with them.
    for value in (binary, user.pw_name, home):
        if not re.fullmatch(r'[A-Za-z0-9_./-]+', value):
            raise SystemExit('User, home and SonoBus paths must contain only letters, numbers, _, -, / or .')
    directory = Path(home) / '.config/android-linux-voice-bridge/units'
    directory.mkdir(parents=True, exist_ok=False)
    templates = Path(__file__).resolve().parent
    text = (templates / 'codex-voice-audio.service').read_text()
    for key, value in {'@USER@': user.pw_name, '@UID@': str(user.pw_uid),
                       '@HOME@': home, '@SONOBUS@': binary}.items():
        text = text.replace(key, value)
    (directory / 'android-linux-voice-audio.service').write_text(text)
    (directory / 'android-linux-voice-routing.service').write_bytes(
        (templates / 'codex-voice-routing.service').read_bytes())
    print(f'Service files prepared in {directory}; they have not been installed or started.')


if __name__ == '__main__':
    main()
