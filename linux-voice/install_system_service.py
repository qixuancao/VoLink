#!/usr/bin/env python3
"""Install this package's system service; invoked via sudo by its user."""
import os
from pathlib import Path
import pwd
import re
import subprocess
import sys

UNIT = Path('/etc/systemd/system/android-linux-voice-audio.service')


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise SystemExit('Run through voice-bridge setup using sudo.')
    uid = int(os.environ.get('SUDO_UID', '0'))
    if uid == 0:
        raise SystemExit('A non-root login user is required.')
    account = pwd.getpwuid(uid)
    binary = sys.argv[1]
    if binary == '--remove':
        if not UNIT.exists():
            return
        content = UNIT.read_text()
        if (f'User={account.pw_name}\n' not in content or
                '/usr/lib/android-linux-voice-bridge/verify_firewall.py' not in content):
            raise SystemExit('Service is not owned by this user or has been modified; refusing removal.')
        subprocess.run(['/usr/bin/systemctl', 'disable', '--now', UNIT.name], check=True)
        UNIT.unlink()
        subprocess.run(['/usr/bin/systemctl', 'daemon-reload'], check=True)
        return
    for value in (account.pw_name, account.pw_dir, binary):
        if not re.fullmatch(r'[A-Za-z0-9_./-]+', value):
            raise SystemExit('Unsupported user or executable path.')
    if not Path(binary).is_file() or not os.path.isabs(binary):
        raise SystemExit('SonoBus executable not found.')
    text = (Path(__file__).resolve().parent / 'codex-voice-audio.service').read_text()
    for key, value in {'@USER@': account.pw_name, '@UID@': str(uid),
                       '@HOME@': account.pw_dir, '@SONOBUS@': binary}.items():
        text = text.replace(key, value)
    try:
        with UNIT.open('x') as file:
            file.write(text)
    except FileExistsError:
        raise SystemExit(f'{UNIT} already exists; refusing to overwrite it.')
    linger_was_enabled = (Path('/var/lib/systemd/linger') / account.pw_name).exists()
    try:
        # Keep the per-user audio manager available before login and after logout.
        subprocess.run(['/usr/bin/loginctl', 'enable-linger', account.pw_name], check=True)
        subprocess.run(['/usr/bin/systemctl', 'daemon-reload'], check=True)
        subprocess.run(['/usr/bin/systemctl', 'enable', UNIT.name], check=True)
        subprocess.run(['/usr/bin/systemctl', 'start', UNIT.name], check=True)
        subprocess.run(['/usr/bin/systemctl', 'is-active', '--quiet', UNIT.name], check=True)
    except Exception:
        subprocess.run(['/usr/bin/systemctl', 'disable', '--now', UNIT.name], check=False)
        UNIT.unlink(missing_ok=True)
        subprocess.run(['/usr/bin/systemctl', 'daemon-reload'], check=False)
        if not linger_was_enabled:
            subprocess.run(['/usr/bin/loginctl', 'disable-linger', account.pw_name], check=False)
        raise


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit('Unable to install or remove the voice service; see the message above.')
    except OSError as exc:
        raise SystemExit(f'Unable to install or remove the voice service: {exc}')
