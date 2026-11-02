#!/usr/bin/env python3
"""Create the two separate audio paths; persist module IDs for clean removal."""
import json
from pathlib import Path
import shlex
import subprocess
import sys

STATE = Path.home() / '.local/state/android-linux-voice-bridge/routing.json'


def pactl(*args):
    return subprocess.check_output(['pactl', *args], text=True, timeout=5).strip()


def devices(kind):
    return {d['name'] for d in json.loads(pactl('-f', 'json', 'list', kind))}


def remove_routing():
    if not STATE.exists():
        return
    state = json.loads(STATE.read_text())
    owned = {
        ('module-null-sink', 'sink_name=android_voice_bridge_to_android'),
        ('module-null-sink', 'sink_name=android_voice_bridge_from_android'),
        ('module-remap-source', 'source_name=android_voice_bridge_mic'),
    }
    live = {}
    for line in pactl('list', 'short', 'modules').splitlines():
        columns = line.split('\t', 3)
        # PipeWire also lists native modules, whose arguments can span lines
        # or be empty. Only parse the PulseAudio module types we create.
        if (len(columns) >= 3 and columns[0].isdigit()
                and columns[1] in {'module-null-sink', 'module-remap-source'}):
            live[int(columns[0])] = (columns[1], shlex.split(columns[2]))
    # Restore only defaults that still point at our devices. Do not undo a
    # deliberate device change made by the user while the bridge was running.
    restore = {}
    for kind, ours in (('sink', 'android_voice_bridge_to_android'),
                       ('source', 'android_voice_bridge_mic')):
        restore[kind] = (pactl('get-default-' + kind) == ours
                         and state[kind] in devices(kind + 's'))
    for module_id in reversed(state['modules']):
        name, args = live.get(module_id, ('', []))
        # Module IDs can be reused after restarting PipeWire. Check ownership.
        if any(name == expected and marker in args for expected, marker in owned):
            subprocess.run(['pactl', 'unload-module', str(module_id)], check=True, capture_output=True, timeout=5)
    for kind in ('sink', 'source'):
        if restore[kind]:
            pactl('set-default-' + kind, state[kind])
    STATE.unlink()


def main():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    if sys.argv[1:] == ['stop']:
        remove_routing()
        return
    if sys.argv[1:] != ['start']:
        raise SystemExit('usage: route_audio.py start|stop')
    if STATE.exists():
        if ('android_voice_bridge_mic' in devices('sources')
                and {'android_voice_bridge_to_android', 'android_voice_bridge_from_android'} <= devices('sinks')):
            pactl('set-default-sink', 'android_voice_bridge_to_android')
            pactl('set-default-source', 'android_voice_bridge_mic')
            return
        remove_routing()
    if ('android_voice_bridge_mic' in devices('sources') or
            {'android_voice_bridge_to_android', 'android_voice_bridge_from_android'} & devices('sinks')):
        raise SystemExit('bridge audio devices already exist without owned state; refusing to modify them')
    state = {'sink': pactl('get-default-sink'), 'source': pactl('get-default-source'), 'modules': []}
    try:
        for args in [
            ('module-null-sink', 'sink_name=android_voice_bridge_to_android', 'rate=48000', 'channels=2',
             'sink_properties=device.description=VoLink_to_Android'),
            ('module-null-sink', 'sink_name=android_voice_bridge_from_android', 'rate=48000', 'channels=2',
             'sink_properties=device.description=VoLink_from_Android'),
            ('module-remap-source', 'master=android_voice_bridge_from_android.monitor',
             'source_name=android_voice_bridge_mic', 'channels=1', 'master_channel_map=front-left',
             'channel_map=mono', 'source_properties=device.description=VoLink_Android_Microphone'),
        ]:
            state['modules'].append(int(pactl('load-module', *args)))
            STATE.write_text(json.dumps(state, indent=2) + '\n')
        pactl('set-default-sink', 'android_voice_bridge_to_android')
        pactl('set-default-source', 'android_voice_bridge_mic')
    except Exception:
        # Let the systemd ExecStopPost cleanup any partially created routing.
        raise


if __name__ == '__main__':
    main()
