import importlib.machinery
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class ActivationTest(unittest.TestCase):
    def test_personal_installation_blocks_activation_and_failed_sudo_rolls_back(self):
        for personal_active, previous_unit in ((True, None), (False, None),
                                               (False, 'matching'), (False, 'modified')):
            with self.subTest(personal_active=personal_active, previous_unit=previous_unit), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                home = root / 'home'
                config = home / '.config/android-linux-voice-bridge'
                config.mkdir(parents=True)
                (config / 'voice.sonobus').touch()
                (config / 'alsa.conf').touch()
                lib = root / 'usr/lib/android-linux-voice-bridge'
                lib.mkdir(parents=True)
                source = Path(__file__).parent
                shutil.copy(source / 'codex-voice-routing.service', lib)
                unit = home / '.config/systemd/user/android-linux-voice-routing.service'
                if previous_unit:
                    unit.parent.mkdir(parents=True)
                    unit.write_text((source / 'codex-voice-routing.service').read_text()
                                    if previous_unit == 'matching' else 'custom service')
                tool = root / 'usr/bin/voice-bridge'
                tool.parent.mkdir(parents=True)
                shutil.copy(source / 'voice-bridge', tool)
                loader = importlib.machinery.SourceFileLoader('voice_cli_test', str(tool))
                module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
                loader.exec_module(module)
                calls = []
                def run(cmd, check=False, **kwargs):
                    calls.append(cmd)
                    if personal_active and 'is-active' in cmd:
                        return SimpleNamespace(returncode=0)
                    if cmd[0] == 'sudo':
                        raise subprocess.CalledProcessError(1, cmd)
                    return SimpleNamespace(returncode=1 if '--quiet' in cmd else 0)
                class FakeSocket:
                    def __enter__(self): return self
                    def __exit__(self, *args): pass
                    def bind(self, address): pass
                with patch.object(Path, 'home', return_value=home), \
                     patch.object(module.sys, 'argv', ['voice-bridge', 'activate']), \
                     patch.object(module.os, 'geteuid', return_value=1000), \
                     patch.object(module.pwd, 'getpwuid', return_value=SimpleNamespace(pw_dir=str(home))), \
                     patch.object(module.shutil, 'which', return_value='/usr/bin/sonobus'), \
                     patch.object(module.socket, 'socket', return_value=FakeSocket()), \
                     patch.object(module.subprocess, 'run', side_effect=run):
                    expected = SystemExit if personal_active or previous_unit == 'modified' else subprocess.CalledProcessError
                    with self.assertRaises(expected):
                        module.main()
                self.assertEqual(unit.exists(), bool(previous_unit))
                if previous_unit == 'modified':
                    self.assertEqual(unit.read_text(), 'custom service')
                self.assertEqual(any(cmd[0] == 'sudo' for cmd in calls),
                                 not personal_active and previous_unit != 'modified')
