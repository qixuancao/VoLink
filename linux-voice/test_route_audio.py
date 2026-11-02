import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import route_audio


class RemoveRoutingTest(unittest.TestCase):
    def test_refuses_unowned_existing_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / 'routing.json'
            with patch.object(route_audio, 'STATE', state), \
                    patch.object(route_audio.sys, 'argv', ['route_audio.py', 'start']), \
                    patch.object(route_audio, 'devices', return_value={'android_voice_bridge_mic'}), \
                    patch.object(route_audio, 'pactl') as pactl:
                with self.assertRaises(SystemExit):
                    route_audio.main()
                pactl.assert_not_called()

    def test_skips_multiline_modules_and_only_unloads_owned_ids(self):
        modules = (
            '1\tlibpipewire-module-rt\t{\n'
            '            nice.level = -11\n'
            '        }\t\n'
            '6\tlibpipewire-module-metadata\t\t\n'
            '100\tmodule-null-sink\tsink_name=android_voice_bridge_to_android rate=48000\t\n'
            '101\tmodule-null-sink\tsink_name=unrelated\t\n'
        )
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / 'routing.json'
            state.write_text(json.dumps({'sink': 'old_sink', 'source': 'old_source',
                                         'modules': [100, 101]}))
            def pactl(*args):
                if args == ('list', 'short', 'modules'):
                    return modules
                return ''
            with patch.object(route_audio, 'STATE', state), \
                    patch.object(route_audio, 'pactl', side_effect=pactl), \
                    patch.object(route_audio, 'devices', return_value=set()), \
                    patch.object(route_audio.subprocess, 'run') as run:
                route_audio.remove_routing()
            run.assert_called_once_with(['pactl', 'unload-module', '100'],
                                        check=True, capture_output=True, timeout=5)
            self.assertFalse(state.exists())

    def test_preserves_defaults_changed_by_user(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / 'routing.json'
            state.write_text(json.dumps({'sink': 'old_sink', 'source': 'old_source', 'modules': []}))
            def pactl(*args):
                if args == ('get-default-sink',):
                    return 'another_sink'
                if args == ('get-default-source',):
                    return 'android_voice_bridge_mic'
                return ''
            with patch.object(route_audio, 'STATE', state), \
                    patch.object(route_audio, 'pactl', side_effect=pactl) as call, \
                    patch.object(route_audio, 'devices', side_effect=lambda kind: {'old_sink'} if kind == 'sinks' else {'old_source'}):
                route_audio.remove_routing()
            self.assertNotIn(unittest.mock.call('set-default-sink', 'old_sink'), call.call_args_list)
            self.assertIn(unittest.mock.call('set-default-source', 'old_source'), call.call_args_list)


if __name__ == '__main__':
    unittest.main()
