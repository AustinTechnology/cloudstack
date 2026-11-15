"""Live smoke test of the installed helper without Hyper-V: the RDP target is a black hole, so the VNC side stays up long
enough to check that the console proxy could connect and authenticate with the one-time password, and that the session
is torn down after. Run as root on the helper's server."""
import json
import socket
import sys
import time

from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
from cryptography.hazmat.primitives.ciphers import Cipher, modes


def vnc_auth(host, port, password):
    with socket.create_connection((host, port), timeout=10) as s:
        f = s.makefile("rwb")
        banner = f.read(12)
        f.write(b"RFB 003.008\n"); f.flush()
        n = f.read(1)[0]
        types = f.read(n)
        assert 2 in types, types
        f.write(b"\x02"); f.flush()
        challenge = f.read(16)
        # VNC authentication is single DES over the challenge, as the RFB protocol defines it (DES = 3DES with K1=K2=K3).
        key = bytes(int("{:08b}".format(b)[::-1], 2) for b in password.encode().ljust(8, b"\0")[:8])
        enc = Cipher(TripleDES(key * 3), modes.ECB()).encryptor()
        f.write(enc.update(challenge) + enc.finalize()); f.flush()
        result = int.from_bytes(f.read(4), "big")
        return banner, result


def ask(path, request):
    with socket.socket(socket.AF_UNIX) as s:
        s.connect(path)
        s.sendall((json.dumps(request) + "\n").encode())
        return json.loads(s.makefile().readline())


path = "/run/hyperv-console/helper.sock"
req = {"node": "192.0.2.1", "vmid": "5d1c7e0a-9a52-4f7e-8d5b-2b1f6c0f9a11", "username": "LAB\\nobody", "password": "x"}
a = ask(path, req)
print("helper:", {k: (v if k != "console" else {kk: ("<8 chars>" if kk == "password" else vv) for kk, vv in v.items()}) for k, v in a.items()})
c = a["console"]
time.sleep(1.5)
banner, wrong = vnc_auth(c["host"], c["port"], "wrongpw1")
print("banner", banner, "wrong password result", wrong)
b = ask(path, req)["console"]
time.sleep(1.5)
banner, right = vnc_auth(b["host"], b["port"], b["password"])
print("right password result", right)
time.sleep(3)
for port in (c["port"], b["port"]):
    try:
        socket.create_connection((b["host"], port), timeout=2).close()
        print("port", port, "still open")
    except OSError:
        print("port", port, "closed")
sys.exit(0 if wrong != 0 and right == 0 else 1)
