import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import install_system_service as installer


class InstallServiceTest(unittest.TestCase):
    def test_install_and_failure_rollback(self):
        for fail in (None, 'enable', 'start', 'is-active'):
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as tmp:
                unit = Path(tmp) / 'android-linux-voice-audio.service'
                binary = Path(tmp) / 'sonobus'
                binary.touch()
                account = SimpleNamespace(pw_name='tester', pw_dir='/home/tester')
                def run(cmd, check=False):
                    if fail and fail in cmd:
                        raise subprocess.CalledProcessError(1, cmd)
                    return SimpleNamespace(returncode=0)
                with patch.object(installer, 'UNIT', unit), patch.object(installer.os, 'geteuid', return_value=0), \
                     patch.dict(os.environ, {'SUDO_UID': '1000'}), \
                     patch.object(installer.sys, 'argv', ['installer', str(binary)]), \
                     patch.object(installer.pwd, 'getpwuid', return_value=account), \
                     patch.object(installer.subprocess, 'run', side_effect=run):
                    if fail:
                        with self.assertRaises(subprocess.CalledProcessError):
                            installer.main()
                        self.assertFalse(unit.exists())
                    else:
                        installer.main()
                        self.assertIn('User=tester', unit.read_text())
                        with self.assertRaises(SystemExit):
                            installer.main()
