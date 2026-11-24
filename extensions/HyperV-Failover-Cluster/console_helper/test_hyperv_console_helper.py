"""Tests of the console helper with fake processes: what it starts, that no secret is on a command line, and teardown."""
import json
import os
import socket
import tempfile
import threading
import time
import unittest

import hyperv_console_helper as h


class FakeProc:
    def __init__(self, args, **kw):
        self.args = args
        self.kw = kw
        self.returncode = None
        self.stdin = self
        self.written = b""
        self.terminated = False

    def write(self, data):
        self.written += data

    def close(self):
        pass

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True
        self.returncode = -15

    def kill(self):
        self.returncode = -9

    def wait(self, timeout=None):
        return self.returncode


class Launcher:
    def __init__(self):
        self.procs = []

    def __call__(self, args, **kw):
        p = FakeProc(args, **kw)
        self.procs.append(p)
        return p


def config(**kw):
    c = dict(h.DEFAULTS, listen_address="127.0.0.1", port_first=45900, port_last=45905, connect_timeout=5)
    c.update(kw)
    return c


REQUEST = {"node": "hv01.lab.test", "vmid": "5D1C7E0A-9A52-4F7E-8D5B-2B1F6C0F9A11", "username": "LAB\\svc-cloudstack",
           "password": "S3cret-pw"}


class HelperTest(unittest.TestCase):
    def setUp(self):
        self.launch = Launcher()
        self.helper = h.Helper(config(), launcher=self.launch)

    def test_open_starts_xvfb_freerdp_and_x11vnc(self):
        answer = self.helper.open(dict(REQUEST))
        self.assertEqual(answer["host"], "127.0.0.1")
        self.assertEqual(answer["port"], 45900)
        self.assertRegex(answer["password"], r"^[A-Za-z0-9]{8}$")
        xvfb, rdp, vnc = self.launch.procs
        self.assertEqual(xvfb.args[0], "Xvfb")
        self.assertIn("/v:hv01.lab.test:2179", rdp.args)
        self.assertIn("/vmconnect:5d1c7e0a-9a52-4f7e-8d5b-2b1f6c0f9a11", rdp.args)
        self.assertIn("/u:svc-cloudstack", rdp.args)
        self.assertIn("/d:LAB", rdp.args)
        self.assertIn("-gfx", rdp.args)
        self.assertEqual(rdp.written, b"S3cret-pw\n")
        self.assertIn("-once", vnc.args)
        self.assertIn("127.0.0.1", vnc.args)
        for p in self.launch.procs:  # no secret on any command line
            self.assertNotIn("S3cret-pw", " ".join(p.args))
            self.assertNotIn(answer["password"], " ".join(p.args))
        pwfile = vnc.args[vnc.args.index("-passwdfile") + 1]
        self.assertTrue(pwfile.startswith("rm:"))
        with open(pwfile[3:]) as f:
            self.assertEqual(f.read().strip(), answer["password"])
        os.unlink(pwfile[3:])

    def test_session_ends_when_vnc_ends_and_frees_its_port(self):
        a = self.helper.open(dict(REQUEST))
        b = self.helper.open(dict(REQUEST))
        self.assertEqual((a["port"], b["port"]), (45900, 45901))
        vnc = self.launch.procs[2]
        vnc.returncode = 0
        deadline = time.time() + 5
        while len(self.helper.sessions) > 1 and time.time() < deadline:
            time.sleep(0.1)
        self.assertEqual(len(self.helper.sessions), 1)
        self.assertTrue(self.launch.procs[0].terminated and self.launch.procs[1].terminated)
        c = self.helper.open(dict(REQUEST))
        self.assertEqual(c["port"], 45900)

    def test_session_ends_when_rdp_ends(self):
        self.helper.open(dict(REQUEST))
        self.launch.procs[1].returncode = 131
        deadline = time.time() + 5
        while self.helper.sessions and time.time() < deadline:
            time.sleep(0.1)
        self.assertEqual(self.helper.sessions, {})

    def test_bad_requests(self):
        for bad in ({"vmid": "not-a-guid"}, {"node": "hv01;rm -rf"}, {"password": ""}):
            with self.assertRaises(ValueError):
                self.helper.open(dict(REQUEST, **bad))
        self.assertEqual(self.launch.procs, [])

    def test_pool_exhaustion(self):
        helper = h.Helper(config(max_sessions=1), launcher=self.launch)
        helper.open(dict(REQUEST))
        with self.assertRaises(RuntimeError):
            helper.open(dict(REQUEST))

    def test_socket_round_trip_never_echoes_the_password(self):
        path = os.path.join(tempfile.mkdtemp(), "helper.sock")
        helper = h.Helper(config(socket=path, socket_group=""), launcher=self.launch)
        threading.Thread(target=helper.serve, daemon=True).start()
        deadline = time.time() + 5
        while not os.path.exists(path) and time.time() < deadline:
            time.sleep(0.05)
        self.assertEqual(oct(os.stat(path).st_mode & 0o777), "0o660")

        def ask(request):
            with socket.socket(socket.AF_UNIX) as s:
                s.connect(path)
                s.sendall((json.dumps(request) + "\n").encode())
                return json.loads(s.makefile().readline())
        ok = ask(REQUEST)
        self.assertEqual(ok["status"], "success")
        bad = ask(dict(REQUEST, vmid="x"))
        self.assertEqual(bad["status"], "error")
        self.assertNotIn("S3cret-pw", json.dumps(bad))


if __name__ == "__main__":
    unittest.main()
