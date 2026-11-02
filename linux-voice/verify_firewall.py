#!/usr/bin/env python3
"""Fail closed before starting SonoBus if systemd's IP firewall is absent."""
import json
from pathlib import Path
import subprocess
import sys


def valid_programs(programs):
    found = {(entry.get('attach_type'), entry.get('name')) for entry in programs}
    return (('cgroup_inet_ingress', 'sd_fw_ingress') in found and
            ('cgroup_inet_egress', 'sd_fw_egress') in found)


def main():
    try:
        line = next(line for line in Path('/proc/self/cgroup').read_text().splitlines()
                    if line.startswith('0::'))
        group = Path('/sys/fs/cgroup') / line[3:].lstrip('/')
        result = subprocess.run(['/usr/sbin/bpftool', '-j', 'cgroup', 'show', str(group)],
                                check=True, capture_output=True, text=True)
        if not valid_programs(json.loads(result.stdout)):
            raise ValueError('required systemd firewall programs are not attached')
    except (OSError, StopIteration, ValueError, subprocess.SubprocessError) as exc:
        print(f'Cannot verify the loopback firewall: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
