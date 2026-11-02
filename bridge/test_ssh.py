#!/usr/bin/env python3
"""Exercise the real SSH/FRPC path. SSH asks for its password on the terminal."""
import argparse
import select
import struct
import subprocess
import time


def osc_string(value):
    b = value.encode() + b'\0'
    return b + b'\0' * (-len(b) % 4)


parser = argparse.ArgumentParser(description='Test the audio bridge over a previously verified SSH connection')
parser.add_argument('destination', help='SSH destination, e.g. user@example.org')
parser.add_argument('--port', type=int, default=22)
args = parser.parse_args()
command = ['ssh', '-T', '-p', str(args.port), '-o', 'StrictHostKeyChecking=yes',
           '-o', 'PubkeyAuthentication=no', '-o', 'PreferredAuthentications=password',
           '-o', 'NumberOfPasswordPrompts=1', '-o', 'ConnectTimeout=10',
           args.destination, 'exec "$HOME/.local/bin/sonobus-ssh-bridge" --stdio']
proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
try:
    packet = osc_string('/aoo/src/0/invite') + osc_string(',ii') + struct.pack('>ii', 12346, 0)
    proc.stdin.write(struct.pack('>I', len(packet)) + packet)
    proc.stdin.flush()
    data = b''
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if not select.select([proc.stdout], [], [], 1)[0]:
            continue
        chunk = proc.stdout.read1(65536)
        if not chunk:
            raise RuntimeError('SSH/audio bridge closed before a response')
        data += chunk
        if len(data) >= 4:
            size, = struct.unpack('>I', data[:4])
            if not 0 < size <= 65507:
                raise RuntimeError('invalid bridge frame')
            if len(data) >= 4 + size:
                response = data[4:4+size]
                if not response.startswith(b'/aoo/'):
                    raise RuntimeError('response was not SonoBus audio protocol')
                print('PASS: SSH password login -> existing FRPC -> audio bridge -> SonoBus -> framed reply', size, 'bytes')
                break
    else:
        raise TimeoutError('no audio protocol response')
    proc.stdin.close()
    if proc.wait(timeout=5) != 0:
        raise RuntimeError('SSH bridge did not close cleanly')
finally:
    if proc.poll() is None:
        proc.terminate()
        proc.wait(timeout=5)
