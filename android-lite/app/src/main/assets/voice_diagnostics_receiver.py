#!/usr/bin/env python3
"""Receive bounded, metadata-only diagnostics on an authenticated SSH channel."""
import datetime
import fcntl
import json
import math
import os
from pathlib import Path
import re
import stat
import sys
import time

LIMIT = 1024 * 1024
FIELDS = {
    'event', 'phase', 'error', 'version', 'session', 'time_ms', 'uptime_ms',
    'state', 'foreground', 'permission', 'system_mic_muted', 'requested',
    'capture', 'success', 'muted', 'ptt', 'peak', 'rms', 'peers',
    'native_tx_bytes', 'native_rx_bytes', 'tx_packets', 'rx_packets',
    'tx_bytes', 'rx_bytes', 'heartbeats', 'dropped_events',
    'recording_session', 'recording_active', 'sample_rate', 'audio_source', 'client_silenced',
}
TEXT = {'event', 'phase', 'error', 'version', 'session'}


def sanitize(record):
    if not isinstance(record, dict):
        return None
    out = {}
    for key, value in record.items():
        if key not in FIELDS:
            continue
        if key in TEXT:
            if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.-]{1,96}', value):
                out[key] = value
        elif isinstance(value, (bool, int, float)) and math.isfinite(value):
            out[key] = value
    if 'event' not in out:
        return None
    out['received_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    return out


def append_record(root, record, limit=LIMIT):
    # The lock serializes reconnecting clients; each write opens the current file
    # after rotation so an old connection cannot keep writing an unlinked file.
    flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW
    lock = os.open(root / 'receiver.lock', flags, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX)
        path = root / 'android.jsonl'
        line = (json.dumps(record, ensure_ascii=True, allow_nan=False) + '\n').encode()
        try:
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('not a regular log file')
            if info.st_size + len(line) > limit:
                path.replace(root / 'android.jsonl.1')
        except FileNotFoundError:
            pass
        fd = os.open(path, flags, 0o600)
        with os.fdopen(fd, 'ab') as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(line)
    finally:
        os.close(lock)


def main():
    os.umask(0o077)
    root = Path.home() / '.local/state/volink/diagnostics'
    root.mkdir(parents=True, exist_ok=True)
    if root.is_symlink() or root.stat().st_uid != os.getuid():
        raise ValueError('unsafe diagnostics directory')
    root.chmod(0o700)
    print('VOLINK_DIAGNOSTICS_V1', flush=True)
    window, count = time.monotonic(), 0
    while True:
        raw = sys.stdin.buffer.readline(8193)
        if not raw:
            return
        if len(raw) > 8192 or not raw.endswith(b'\n'):
            return
        now = time.monotonic()
        if now - window >= 1:
            window, count = now, 0
        count += 1
        if count > 20:
            continue
        try:
            record = sanitize(json.loads(raw))
        except (ValueError, UnicodeError, OverflowError):
            continue
        if record is not None:
            append_record(root, record)


if __name__ == '__main__':
    main()
