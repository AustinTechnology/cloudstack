#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
# file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
"""Hyper-V console helper: serves a Hyper-V VM's console (RDP on the node's port 2179, the VMConnect channel) as VNC,
for CloudStack's console proxy.

The Hyper-V extension asks over a local Unix socket for a session to one VM. The helper starts, for that session only:

    Xvfb :N          a private X display
    xfreerdp3        on that display, /vmconnect:<VM GUID> to <node>:2179, full screen
    x11vnc           serving the display on one port, one random password, one client, then exits

and answers {host, port, password}. A session that nobody connects to within `connect_timeout` seconds, or that
reaches `max_session` seconds, or whose VNC client disconnects, or whose RDP connection ends, is torn down.

Nothing secret is put on a command line: the RDP credentials go to xfreerdp on stdin (/from-stdin), the VNC password
to x11vnc in a file it deletes when read (rm:).
"""
import argparse
import json
import os
import secrets
import shutil
import signal
import socket
import string
import subprocess
import sys
import tempfile
import threading
import time
import uuid

DEFAULTS = {
    "socket": "/run/hyperv-console/helper.sock",
    "socket_group": "cloud",
    "listen_address": "",          # address the console proxy connects to; required
    "port_first": 5900,
    "port_last": 5999,
    "display_first": 100,
    "connect_timeout": 60,
    "max_session": 14400,
    "width": 1024,
    "height": 768,
    "rdp_port": 2179,
    "rdp_certificate": "tofu",     # FreeRDP /cert: tofu (trust on first use, remembered) or ignore
    "xfreerdp": "xfreerdp3",
    "xvfb": "Xvfb",
    "x11vnc": "x11vnc",
    "max_sessions": 50,
}


def log(message):
    print(time.strftime("%Y-%m-%dT%H:%M:%S ") + message, file=sys.stderr, flush=True)


class Session:
    def __init__(self, sid, display, port):
        self.sid, self.display, self.port = sid, display, port
        self.procs = []
        self.started = time.monotonic()
        self.done = threading.Event()

    def stop(self):
        for p in reversed(self.procs):
            if p.poll() is None:
                p.terminate()
        deadline = time.monotonic() + 5
        for p in self.procs:
            try:
                p.wait(max(0.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                p.kill()
        self.done.set()


class Helper:
    def __init__(self, config, launcher=None):
        self.c = config
        self.launch = launcher or subprocess.Popen
        self.real = launcher is None
        self.lock = threading.Lock()
        self.sessions = {}

    # ---- allocation ---------------------------------------------------------------------------------
    def allocate(self):
        with self.lock:
            if len(self.sessions) >= self.c["max_sessions"]:
                raise RuntimeError("Too many console sessions")
            ports = {s.port for s in self.sessions.values()}
            displays = {s.display for s in self.sessions.values()}
            port = next((p for p in range(self.c["port_first"], self.c["port_last"] + 1)
                         if p not in ports and self.port_free(p)), None)
            display = next((d for d in range(self.c["display_first"], self.c["display_first"] + 1000)
                            if d not in displays and not os.path.exists("/tmp/.X11-unix/X%d" % d)), None)
            if port is None or display is None:
                raise RuntimeError("No free console port")
            session = Session(uuid.uuid4().hex[:12], display, port)
            self.sessions[session.sid] = session
            return session

    def port_free(self, port):
        with socket.socket() as s:
            try:
                s.bind((self.c["listen_address"], port))
                return True
            except OSError:
                return False

    # ---- a session ----------------------------------------------------------------------------------
    def open(self, request):
        for key in ("node", "vmid", "username", "password"):
            if not isinstance(request.get(key), str) or not request[key]:
                raise ValueError("Missing " + key)
        vm = request["vmid"].lower()
        uuid.UUID(vm)
        node = request["node"]
        if not all(ch.isalnum() or ch in ".-:[]" for ch in node):
            raise ValueError("Invalid node address")
        session = self.allocate()
        try:
            password = "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(8))
            env = dict(os.environ, DISPLAY=":%d" % session.display)
            xvfb = self.launch([self.c["xvfb"], ":%d" % session.display, "-screen", "0",
                                "%dx%dx24" % (self.c["width"], self.c["height"]), "-nolisten", "tcp"],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            session.procs.append(xvfb)
            self.wait_display(session)
            user = request["username"]
            domain = request.get("domain") or ""
            if "\\" in user and not domain:
                domain, user = user.split("\\", 1)
            if not all(ch.isalnum() or ch in "._-@ " for ch in user + domain):
                raise ValueError("Invalid user or domain")
            # User and domain are not secret and go on the command line; FreeRDP then asks only for the password on
            # stdin (asked for all three, it read none of them). VMConnect never finishes activation with the
            # graphics pipeline on (FreeRDP 3.32 against Windows Server 2025), hence -gfx.
            rdp = self.launch([self.c["xfreerdp"], "/v:%s:%d" % (node, self.c["rdp_port"]), "/vmconnect:" + vm,
                               "/u:" + user, "/d:" + domain, "/from-stdin:force", "/sec:nla", "-gfx",
                               "/cert:" + self.c["rdp_certificate"], "/f", "-decorations", "/smart-sizing",
                               "/size:%dx%d" % (self.c["width"], self.c["height"]), "/log-level:ERROR"],
                              stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
            session.procs.append(rdp)
            assert rdp.stdin is not None
            rdp.stdin.write(("%s\n" % request["password"]).encode())
            rdp.stdin.close()
            fd, pwfile = tempfile.mkstemp(prefix="vncpw-")
            with os.fdopen(fd, "w") as f:
                f.write(password + "\n")
            vnc = self.launch([self.c["x11vnc"], "-display", ":%d" % session.display, "-rfbport", str(session.port),
                               "-listen", self.c["listen_address"], "-passwdfile", "rm:" + pwfile, "-once",
                               "-timeout", str(self.c["connect_timeout"]), "-noxdamage", "-quiet",
                               "-norc", "-nossl"],
                              stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
            session.procs.append(vnc)
            threading.Thread(target=self.watch, args=(session, rdp, vnc), daemon=True).start()
        except Exception:
            self.end(session, "failed to start")
            raise
        log("session %s: VM %s on %s, port %d" % (session.sid, vm, node, session.port))
        return {"host": self.c["listen_address"], "port": session.port, "password": password, "session": session.sid}

    def wait_display(self, session):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if os.path.exists("/tmp/.X11-unix/X%d" % session.display) or not self.real:
                return
            if session.procs[0].poll() is not None:
                raise RuntimeError("Xvfb did not start")
            time.sleep(0.1)
        raise RuntimeError("Xvfb did not start")

    def watch(self, session, rdp, vnc):
        while not session.done.is_set():
            if vnc.poll() is not None:
                return self.end(session, "VNC client gone or never came")
            if rdp.poll() is not None:
                return self.end(session, "RDP connection ended (%s)" % rdp.returncode)
            if time.monotonic() - session.started > self.c["max_session"]:
                return self.end(session, "maximum session time reached")
            time.sleep(1)

    def end(self, session, why):
        session.stop()
        with self.lock:
            self.sessions.pop(session.sid, None)
        log("session %s ended: %s" % (session.sid, why))

    # ---- socket server ------------------------------------------------------------------------------
    def handle(self, conn):
        with conn:
            data = b""
            while not data.endswith(b"\n") and len(data) < 65536:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                data += chunk
            try:
                answer = {"status": "success", "console": self.open(json.loads(data.decode()))}
            except Exception as e:  # the reason, never the request (it holds a password)
                answer = {"status": "error", "error": "%s: %s" % (type(e).__name__, e)}
            conn.sendall((json.dumps(answer) + "\n").encode())

    def serve(self):
        path = self.c["socket"]
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if os.path.exists(path):
            os.unlink(path)
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        old = os.umask(0o117)
        server.bind(path)
        os.umask(old)
        if self.c["socket_group"]:
            shutil.chown(path, group=self.c["socket_group"])
        os.chmod(path, 0o660)
        server.listen(16)
        log("listening on %s; consoles on %s:%d-%d" % (path, self.c["listen_address"], self.c["port_first"], self.c["port_last"]))
        try:
            while True:
                conn, _ = server.accept()
                threading.Thread(target=self.handle, args=(conn,), daemon=True).start()
        finally:
            for s in list(self.sessions.values()):
                self.end(s, "helper stopping")


def load_config(path):
    config = dict(DEFAULTS)
    if path and os.path.exists(path):
        with open(path) as f:
            config.update(json.load(f))
    if not config["listen_address"]:
        raise SystemExit("listen_address is required: the address CloudStack's console proxy reaches this helper on")
    return config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="/etc/hyperv-console-helper.json")
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    Helper(load_config(parser.parse_args().config)).serve()


if __name__ == "__main__":
    main()
