import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

# The receiver lives in APK assets; importing it must not add a .pyc asset.
sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().parents[1] / 'android-lite/app/src/main/assets/voice_diagnostics_receiver.py'
spec = importlib.util.spec_from_file_location('receiver', SCRIPT)
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)


class DiagnosticsReceiverTest(unittest.TestCase):
    def test_private_metadata_only_receiver_and_eof(self):
        with tempfile.TemporaryDirectory() as folder:
            env = dict(os.environ, HOME=folder)
            p = subprocess.run([sys.executable, str(SCRIPT)], env=env, input=json.dumps({
                'event': 'audio', 'capture': 1, 'peak': .1,
                'password': 'must_not_persist', 'audio': 'must_not_persist',
                'error': 'credential with spaces must_not_persist',
            }) + '\nnot-json\n' + json.dumps({'event': 'transport', 'tx_packets': 12}) + '\n',
                capture_output=True, text=True, timeout=5, check=True)
            self.assertEqual(p.stdout.strip(), 'VOLINK_DIAGNOSTICS_V1')
            log = Path(folder) / '.local/state/volink/diagnostics/android.jsonl'
            rows = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual([r['event'] for r in rows], ['audio', 'transport'])
            self.assertNotIn('must_not_persist', log.read_text())
            self.assertEqual(log.stat().st_mode & 0o777, 0o600)
            self.assertEqual(log.parent.stat().st_mode & 0o777, 0o700)

    def test_rotation_bounds_history(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for i in range(60):
                receiver.append_record(root, {'event': 'audio', 'time_ms': i}, limit=200)
            self.assertLessEqual((root / 'android.jsonl').stat().st_size, 200)
            self.assertLessEqual((root / 'android.jsonl.1').stat().st_size, 200)
            rows = [json.loads(line) for line in (root / 'android.jsonl').read_text().splitlines()]
            self.assertEqual(rows[-1]['time_ms'], 59)

    def test_symlink_cannot_overwrite_another_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            target = root / 'keep'; target.write_text('unchanged')
            (root / 'android.jsonl').symlink_to(target)
            with self.assertRaises(ValueError):
                receiver.append_record(root, {'event': 'audio'})
            self.assertEqual(target.read_text(), 'unchanged')

    def test_overlong_frame_exits_without_writing(self):
        with tempfile.TemporaryDirectory() as folder:
            p = subprocess.run([sys.executable, str(SCRIPT)], env=dict(os.environ, HOME=folder),
                input='x' * 8193 + '\n', capture_output=True, text=True, timeout=5)
            self.assertEqual(p.returncode, 0)
            self.assertFalse((Path(folder) / '.local/state/volink/diagnostics/android.jsonl').exists())

    def test_nonfinite_and_unknown_values_are_not_logged(self):
        out = receiver.sanitize({'event': 'audio', 'peak': float('nan'), 'rms': float('inf'), 'host': 'private'})
        self.assertEqual(set(out), {'event', 'received_utc'})


if __name__ == '__main__':
    unittest.main()
