"""Tests of hyperv_cluster.py through its entry point, with a simulated Hyper-V failover cluster in place of WinRM.

The simulator implements each PowerShell script's documented behaviour (including its refusals before a write), so
these tests exercise the extension's identity, validation, result and exit-status logic. They do not prove the
PowerShell itself; that needs a real cluster.
"""
import copy
import hashlib
import io
import json
import os
import re
import tempfile
import time
import unittest
import uuid

import hyperv_cluster as hv

GIB = 1024 ** 3


class Unchanged(Exception):
    pass


class LostAnswer(Exception):
    pass


def disk(ctype, cnum, loc, path, size=GIB, base=None):
    return {"ctype": ctype, "cnum": cnum, "loc": loc, "path": path, "base": base or path, "size": size}


def nic(name, mac, switch="vSwitch", vlan=100, ips=(), mode="Access"):
    return {"name": name, "id": "Microsoft:" + mac, "mac": mac, "switch": switch, "mode": mode, "vlan": vlan, "ips": list(ips)}


class FakeCluster:
    """A two-node cluster 'hvc01' with CSVs Volume1 and Volume2."""

    def __init__(self):
        self.name = "HVC01"
        self.nodes = [{"name": "HV01", "state": "Up"}, {"name": "HV02", "state": "Up"}]
        self.vms = {}
        self.files = {}  # full path -> size
        self.calls = []
        self.unreachable = set()
        self.lose = set()  # scripts whose answer is lost after they ran
        self.fail = {}  # script -> error raised after running (unknown) unless Unchanged
        self.storages = [{"storage": "Volume1", "kind": "csv", "root": "C:\\ClusterStorage\\Volume1", "state": "Online", "total": 10 * GIB * 100, "used": GIB},
                         {"storage": "Volume2", "kind": "csv", "root": "C:\\ClusterStorage\\Volume2", "state": "Online", "total": 10 * GIB * 100, "used": 0}]

    def add_vm(self, name, node="HV01", state="Running", generation=2, notes="", clustered=False, guid=None, **kw):
        guid = guid or str(uuid.uuid4())
        vm = {"guid": guid, "name": name, "state": state, "ops": ["Ok"], "node": node, "generation": generation,
              "version": "12.0", "cpus": 2, "memory": 2048 * 1024 * 1024, "dynamic": False, "clustered": clustered,
              "uptime": 100, "notes": notes, "kvp": True, "shutdown": True, "checkpointtype": "Production",
              "current": "", "checkpoints": [], "nics": [], "disks": [], "dvds": [], "boot": [], "secure": "On",
              "sbtemplate": "MicrosoftWindows", "tpm": False, "heartbeat": "OK"}
        vm.update(kw)
        self.vms[guid] = vm
        for d in vm["disks"]:
            self.files[d["path"]] = d["size"]
        return vm

    # --- transport ---
    def run(self, node, script, args, timeout):
        self.calls.append((node, script, copy.deepcopy(args)))
        try:
            result = getattr(self, "s_" + script)(node, args)
        except Unchanged as e:
            return {"ok": False, "unchanged": True, "error": str(e)}
        except RuntimeError as e:
            return {"ok": False, "unchanged": False, "error": str(e)}
        if script in self.fail:
            return {"ok": False, "unchanged": False, "error": self.fail[script]}
        if script in self.lose:
            raise LostAnswer()
        return {"ok": True, "result": copy.deepcopy(result)}

    def path(self, args, vol):
        name, rel = vol.split(":", 1)
        root = args["roots"].get(name)
        if not root:
            raise Unchanged("The cluster has no storage " + name)
        return root + "\\" + rel.replace("/", "\\")

    def the_vm(self, node, args):
        vm = self.vms.get(args["guid"])
        if not vm or vm["node"].lower() != node:
            raise Unchanged("VM_ABSENT")
        if vm["name"] != args["name"]:
            raise Unchanged("The VM at this identity has another name")
        return vm

    def settled(self, vm):
        if vm["state"] not in ("Running", "Off"):
            raise Unchanged("The VM is " + vm["state"])
        if vm["ops"][0] != "Ok":
            raise Unchanged("Hyper-V has an operation in progress on the VM")

    def s_cluster(self, node, args):
        return {"cluster": self.name, "nodes": self.nodes,
                "groups": [{"guid": vm["guid"], "group": vm["name"], "state": "Online" if vm["state"] == "Running" else "Offline", "owner": vm["node"]}
                           for vm in self.vms.values() if vm["clustered"]]}

    def s_nodes(self, node, args):
        if node in self.unreachable:
            raise ConnectionError("no route")
        return {"cluster": self.name, "local": node.upper(), "nodes": self.nodes}

    def s_inventory(self, node, args):
        return {"node": node.upper(), "vms": [{k: vm[k] for k in ("guid", "name", "state", "notes", "clustered", "ops")}
                                              for vm in self.vms.values() if vm["node"].lower() == node]}

    def s_hoststats(self, node, args):
        return {"node": node.upper(), "cpu_utilization": 25, "cpus": 4, "cpu_speed_mhz": 2100, "cpu_sockets": 1, "total_memory_kb": 10000,
                "free_memory_kb": 5000, "network_rx_bytes": 100, "network_tx_bytes": 200, "load_average": None}

    def s_vmstats(self, node, args):
        return {"node": node.upper(), "vms": [dict(guid=v["guid"], name=v["name"], notes=v["notes"],
                cpus=v["cpus"], cpu_utilization=12, memory_kb=v["memory"] // 1024, memory_free_kb=None,
                memory_target_kb=None, network_rx_bytes=123, network_tx_bytes=456, disk_read_bytes=None,
                disk_write_bytes=None, disk_read_ios=None, disk_write_ios=None)
                for v in self.vms.values() if v["node"].lower() == node and v["state"] == "Running"]}

    def s_describe(self, node, args):
        vm = self.the_vm(node, args)
        return {"first": vm, "second": vm}

    def s_storage(self, node, args):
        return self.storages

    def s_overview(self, node, args):
        return {"cluster": self.s_cluster(node, args), "storage": self.s_storage(node, args), "inventory": self.s_inventory(node, args)}

    def s_isofind(self, node, args):
        want = self.path(args, args["volume"])
        return [{"guid": vm["guid"], "name": vm["name"], "slots": ["SCSI0:1"]} for vm in self.vms.values()
                if vm["node"].lower() == node and any(d["path"] == want for d in vm["dvds"])]

    def s_isostat(self, node, args):
        p = self.path(args, args["volume"])
        return {"present": p in self.files, "size": self.files.get(p, 0)}

    def s_vm_new(self, node, args):
        if any(vm["name"] == args["name"] for vm in self.vms.values()):
            raise Unchanged("A VM of that name already exists on the node")
        vm = self.add_vm(args["name"], node=node.upper(), state="Off", generation=args["generation"], cpus=args["cpus"], memory=args["memory"])
        vm["notes"] = "[cloudstack-vmid:%d:%s]" % (args["vmid"], vm["guid"])
        return {"guid": vm["guid"]}

    def s_vm_build(self, node, args):
        vm = self.the_vm(node, args)
        if "vm_build" in self.fail:
            vm["disks"].append(disk("SCSI", 0, 0, "C:\\ClusterStorage\\Volume1\\%s\\half.vhdx" % vm["name"]))
            return None
        root = self.path(args, "%s:%s/%s-disk-0.vhdx" % (args["storage"], vm["name"], vm["name"]))
        ctype = "SCSI" if vm["generation"] == 2 else "IDE"
        vm["disks"].append(disk(ctype, 0, 0, root, args["sizebytes"] or GIB))
        self.files[root] = args["sizebytes"] or GIB
        for i, v in enumerate(args["dvds"]):
            vm["dvds"].append({"ctype": "SCSI", "cnum": 0, "loc": 1 + i, "path": self.path(args, v) if v else ""})
        vm["boot"] = [{"kind": "HardDiskDrive", "ctype": ctype, "cnum": 0, "loc": 0}]
        for n in args["nics"]:
            vm["nics"].append(nic(n["name"], n["mac"], args["switch"], n["vlan"] or 0, mode="Access" if n["vlan"] else "Untagged"))
        return vm

    def s_vm_destroy(self, node, args):
        vm = self.vms.get(args["guid"])
        if not vm:
            raise Unchanged("VM_ABSENT")
        if args.get("requireoff") and vm["state"] != "Off":
            raise Unchanged("The VM is not stopped")
        for d in vm["disks"]:
            self.files.pop(d["path"], None)
        del self.vms[vm["guid"]]
        return {"removed": len(vm["disks"])}

    def s_power(self, node, args):
        vm = self.the_vm(node, args)
        self.settled(vm)
        if args["op"] == "start":
            vm["state"] = "Running"
        elif args["op"] == "stop":
            vm["state"] = "Off"
        elif args["op"] == "reboot":
            if vm["state"] != "Running":
                raise Unchanged("The VM is not running")
            if not vm["shutdown"]:
                raise Unchanged("The guest cannot be asked to restart")
        return vm

    def s_setha(self, node, args):
        vm = self.the_vm(node, args)
        self.settled(vm)
        vm["clustered"] = args["enabled"]
        return {"clustered": vm["clustered"], "state": vm["state"]}

    def s_scale(self, node, args):
        vm = self.the_vm(node, args)
        self.settled(vm)
        if vm["state"] != "Off":
            raise Unchanged("The instance is not stopped in Hyper-V")
        vm["cpus"], vm["memory"] = args["cpus"], args["memory"]
        return vm

    def s_nic(self, node, args):
        vm = self.the_vm(node, args)
        self.settled(vm)
        mac = args["mac"].replace(":", "").upper()
        have = [n for n in vm["nics"] if n["mac"] == mac]
        if args["expected"] and (args["expected"] == "running") != (vm["state"] == "Running"):
            raise Unchanged("The instance power state differs from what CloudStack expects")
        if args["op"] == "plug":
            if have:
                return vm
            vm["nics"].append(nic(args["devname"], mac, args["switch"], args["vlan"]))
        elif have:
            vm["nics"].remove(have[0])
        return vm

    def s_disk(self, node, args):
        vm = self.the_vm(node, args)
        self.settled(vm)
        if vm["checkpoints"]:
            raise Unchanged("The instance has checkpoints")
        if args["op"] == "add":
            p = self.path(args, args["volume"])
            if p in self.files:
                raise Unchanged("The disk file already exists")
            vm["disks"].append(disk("SCSI", args["cnum"], args["loc"], p, args["sizebytes"]))
            self.files[p] = args["sizebytes"]
            return vm
        found = [d for d in vm["disks"] if (d["ctype"], d["cnum"], d["loc"]) == (args["ctype"], args["cnum"], args["loc"])]
        if not found:
            if args["op"] == "delete":
                return vm
            raise Unchanged("The disk is not in that slot")
        d = found[0]
        if d["path"] != self.path(args, args["volume"]):
            raise Unchanged("The slot holds another disk")
        if args["op"] == "resize":
            if d["size"] > args["sizebytes"]:
                raise Unchanged("A disk cannot be made smaller")
            d["size"] = args["sizebytes"]
        elif args["op"] == "delete":
            vm["disks"].remove(d)
            self.files.pop(d["path"], None)
        elif args["op"] == "move":
            dest = self.path(args, args["target"])
            self.files[dest] = self.files.pop(d["path"])
            d["path"] = d["base"] = dest
        return vm

    def s_cd(self, node, args):
        vm = self.the_vm(node, args)
        self.settled(vm)
        if args["op"] == "attach":
            if vm["state"] != "Running":
                raise Unchanged("The instance is not running")
            p = self.path(args, args["volume"])
            if any(d["path"] == p for d in vm["dvds"]):
                return vm
            if not vm["dvds"]:
                raise Unchanged("Stop the instance to give it another CD drive")
            empty = [d for d in vm["dvds"] if not d["path"]]
            if not empty:
                raise Unchanged("The instance has no empty CD drive: each holds a disc.")
            pick = vm["dvds"][args["drive"]] if args["drive"] < len(vm["dvds"]) and not vm["dvds"][args["drive"]]["path"] else empty[0]
            pick["path"] = p
        elif args["op"] == "detach":
            p = self.path(args, args["volume"])
            for d in vm["dvds"]:
                if d["path"] == p:
                    d["path"] = ""
        elif args["op"] == "sync" and vm["state"] == "Off":
            while len(vm["dvds"]) < len(args["wanted"]):
                vm["dvds"].append({"ctype": "SCSI", "cnum": 0, "loc": 10 + len(vm["dvds"]), "path": ""})
            for i, d in enumerate(vm["dvds"]):
                w = args["wanted"][i] if i < len(args["wanted"]) else ""
                d["path"] = self.path(args, w) if w else ""
        return vm

    def s_iso_fetch(self, node, args):
        p = self.path(args, args["volume"])
        if self.iso_checksum and self.iso_checksum != args["checksum"]:
            raise Unchanged("The ISO could not be copied: The downloaded ISO does not match its checksum")
        self.files[p] = args["size"] or 1000
        return {"present": True, "size": self.files[p]}

    iso_checksum = ""

    def s_iso_remove(self, node, args):
        self.files.pop(self.path(args, args["volume"]), None)
        return {"present": False}

    def s_checkpoint(self, node, args):
        vm = self.the_vm(node, args)
        self.settled(vm)
        cp = [c for c in vm["checkpoints"] if c["name"] == args["snap"]]
        if args["op"] == "create":
            if cp:
                raise Unchanged("A checkpoint of that name already exists")
            if args["memory"] and vm["state"] != "Running":
                raise Unchanged("A checkpoint with memory needs the instance running")
            vm["checkpoints"].append({"name": args["snap"], "parent": vm["current"], "state": vm["state"] if args["memory"] else "Off"})
            vm["current"] = args["snap"]
        elif args["op"] == "revert":
            if not cp:
                raise Unchanged("There is no checkpoint of that name")
            memory = cp[0]["state"] == "Running"
            if memory != args["memory"]:
                raise Unchanged("The checkpoint is not of the kind CloudStack recorded")
            if not memory and vm["state"] != "Off":
                raise Unchanged("Stop the instance")
            vm["current"] = args["snap"]
            vm["state"] = "Running" if memory else "Off"
        elif cp:
            vm["checkpoints"].remove(cp[0])
        return vm


CLUSTER = "hvc01"


def payload(vm=None, vmid=None, params=None, adopted=None, host=None, **top):
    p = {"externaldetails": {
        "extension": {"iso_storage": "Volume1"},
        "resourcemap": {"username": "LAB\\svc-cloudstack", "secret": "pw", "dns_suffix": "lab.test"},
        "host": dict({"url": "hv01.lab.test", "node": "hv01", "proxmox_cluster": CLUSTER, "network_bridge": "vSwitch",
                      "native_adoption_status": "false", "native_adoption_lifecycle": "true"}, **(host or {})),
        "virtualmachine": {}}}
    if vmid is not None:
        p["externaldetails"]["virtualmachine"]["proxmox_vmid"] = str(vmid)
    if vm is not None:
        p["virtualmachinename"] = vm
        p["virtualmachineid"] = "6a3c4ab1-5f3e-4e5a-9b2d-1c8f0e7d6a5b"
        p["cloudstack.vm.details"] = {"name": vm, "uuid": p["virtualmachineid"], "cpus": 2, "minRam": 2048 * 1024 * 1024,
                                      "enableHA": False, "details": {}, "nics": [{"mac": "02:00:00:00:01:01", "broadcastUri": "vlan://100", "deviceId": 0}]}
    if params is not None:
        p["parameters"] = params
    if adopted:
        p["externaldetails"]["virtualmachine"].update(adopted["details"])
        p["externaldetails"]["virtualmachine"]["proxmox_vmid"] = str(adopted["context"]["vmid"])
        p["native_adoption_context"] = adopted["context"]
    p.update(top)
    return p


def adoption(action, operation, vmid, name="legacy-web01", lease=True, expires=None, **override):
    manifest = json.dumps({"name": name, "vmid": vmid, "placement": {"cluster": CLUSTER, "node": "hv01"}}, sort_keys=True, separators=(",", ":"))
    sha = hashlib.sha256(manifest.encode()).hexdigest()
    ctx = {"action": action, "operation": operation, "authorization_from_state": "managed", "claim_id": "c1", "generation": 1,
           "cluster": CLUSTER, "vmid": vmid, "manifest_sha256": sha, "cloudstack_vm_ref": "6a3c4ab1-5f3e-4e5a-9b2d-1c8f0e7d6a5b",
           "cloudstack_instance_name": "i-2-9-VM", "stored_state": "operating" if lease else "managed"}
    if lease:
        ctx["lease_id"] = "1b4e28ba-2fa1-41d2-883f-0016d3cca427"
        ctx["expires_epoch"] = expires if expires is not None else time.time() + 3600
    ctx.update(override)
    return {"details": {"adopt_claim_id": "c1", "adopt_claim_generation": "1", "adopt_manifest_json": manifest,
                        "adopt_manifest_sha256": sha}, "context": ctx}


class Base(unittest.TestCase):
    def setUp(self):
        self.c = FakeCluster()

    def run_action(self, action, p, wait=600):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(p, f)
        out = io.StringIO()
        try:
            rc = hv.main(["hyperv_cluster.py", action, f.name, str(wait)], transport=self.c, out=out)
        finally:
            os.unlink(f.name)
        text = out.getvalue().strip()
        return rc, (json.loads(text) if text else None)

    def ordinary(self, name="i-2-5-VM", **kw):
        vm = self.c.add_vm(name, **kw)
        vmid = hv.vmid_of(vm["guid"], vm["notes"])
        return vm, vmid

    def writes(self):
        return [c[1] for c in self.c.calls if c[1] not in hv.READ_ONLY_SCRIPTS]


# Java parsers' rules (ExternalVmConfiguration, ExternalDiscoveryManager, ExternalStorageInventory), restated.
def assert_getconfig(test, cfg, vmid):
    test.assertEqual(cfg["status"], "success")
    test.assertEqual(cfg["identity"]["cluster"], CLUSTER)
    test.assertEqual(cfg["identity"]["vmid"], vmid)
    test.assertIsInstance(cfg["identity"]["vmid"], int)
    test.assertIn(cfg["power"], ("running", "stopped"))
    test.assertRegex(cfg["config_digest"], r"^[0-9a-f]{40}$")
    test.assertIsInstance(cfg["agent_available"], bool)
    devices, macs = set(), set()
    for n in cfg["nics"]:
        test.assertIsInstance(n["device_id"], int)
        test.assertNotIn(n["device_id"], devices)
        devices.add(n["device_id"])
        test.assertRegex(n["mac"], r"^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        test.assertNotIn(n["mac"], macs)
        macs.add(n["mac"])
        test.assertTrue(n["vlan"] is None or 1 <= n["vlan"] <= 4094)
    roots = 0
    for d in cfg["disks"]:
        test.assertRegex(d["device"], r"^(scsi|sata|virtio|ide)(0|[1-9][0-9]?)$")
        test.assertEqual("storage" in d, "volume" in d)
        if "storage" in d:
            test.assertRegex(d["storage"], r"^[A-Za-z][A-Za-z0-9._-]*$")
            test.assertNotIn(":", d["volume"])
            test.assertNotIn(",", d["volume"])
        roots += d["root"]
    test.assertLessEqual(roots, 1)


class IdentityAndInventory(Base):
    def test_vmid_from_guid_is_stable_and_in_range(self):
        guid = "5d1c7e0a-9a52-4f7e-8d5b-2b1f6c0f9a11"
        self.assertEqual(hv.vmid_of(guid, ""), hv.vmid_of(guid.upper(), ""))
        self.assertTrue(1 <= hv.vmid_of(guid, "") <= 2147483646)

    def test_marker_counts_only_for_its_own_guid(self):
        guid = "5d1c7e0a-9a52-4f7e-8d5b-2b1f6c0f9a11"
        self.assertEqual(hv.vmid_of(guid, "x [cloudstack-vmid:4242:%s] y" % guid.upper()), 4242)
        # A copy of the VM keeps the notes but has another GUID: the marker is not its identity.
        other = "0b1c7e0a-9a52-4f7e-8d5b-2b1f6c0f9a11"
        self.assertEqual(hv.vmid_of(other, "[cloudstack-vmid:4242:%s]" % guid), hv.vmid_of(other, ""))

    def test_discover(self):
        vm, vmid = self.ordinary("web01", clustered=True)
        self.c.add_vm("db01", node="HV02", state="Off")
        rc, out = self.run_action("discover", payload())
        self.assertEqual(rc, 0)
        self.assertEqual(out["cluster"], CLUSTER)
        rows = {r["name"]: r for r in out["instances"]}
        self.assertEqual(rows["web01"], {"vmid": vmid, "node": "hv01", "name": "web01", "type": "qemu", "power": "running",
                                         "template": False, "hastate": "started"})
        self.assertEqual(rows["db01"]["hastate"], "")
        self.assertEqual(rows["db01"]["node"], "hv02")

    def test_discover_leaves_out_both_of_a_colliding_pair(self):
        a = self.c.add_vm("a")
        self.c.add_vm("b", notes="[cloudstack-vmid:%d:%s]" % (hv.vmid_of(a["guid"], ""), "x"))
        b = [v for v in self.c.vms.values() if v["name"] == "b"][0]
        b["notes"] = "[cloudstack-vmid:%d:%s]" % (hv.vmid_of(a["guid"], ""), b["guid"])
        self.c.add_vm("c")
        rc, out = self.run_action("discover", payload())
        self.assertEqual([r["name"] for r in out["instances"]], ["c"])

    def test_cluster_identity_mismatch_refuses(self):
        rc, out = self.run_action("discover", payload(host={"proxmox_cluster": "other"}))
        self.assertEqual(rc, 90)
        self.assertIn("does not match", out["error"])

    def test_a_down_node_fails_discovery(self):
        self.c.nodes[1]["state"] = "Down"
        rc, out = self.run_action("discover", payload())
        self.assertEqual(rc, 90)

    def test_health_own_node_up(self):
        rc, out = self.run_action("health", payload())
        self.assertEqual((rc, out["health"]), (0, "up"))
        self.assertEqual(self.writes(), [])

    def test_health_own_node_unreachable_is_unknown_not_failure(self):
        self.c.unreachable.add("hv01")
        rc, out = self.run_action("health", payload())
        self.assertEqual((rc, out["health"]), (0, "unknown"))

    def test_health_peer_reports_down_node(self):
        self.c.nodes[1]["state"] = "Down"
        rc, out = self.run_action("health", payload(params={"node": "HV02"}))
        self.assertEqual((rc, out["health"], out["node"]), (0, "down", "hv02"))

    def test_health_peer_reports_up_and_joining(self):
        rc, out = self.run_action("health", payload(params={"node": "hv02"}))
        self.assertEqual(out["health"], "up")
        self.c.nodes[1]["state"] = "Joining"
        rc, out = self.run_action("health", payload(params={"node": "hv02"}))
        self.assertEqual(out["health"], "unknown")

    def test_health_refuses_wrong_cluster_and_unknown_node(self):
        rc, out = self.run_action("health", payload(host={"proxmox_cluster": "other"}))
        self.assertEqual(rc, 90)
        rc, out = self.run_action("health", payload(params={"node": "hv09"}))
        self.assertEqual(rc, 90)

    def test_missing_credentials(self):
        p = payload()
        p["externaldetails"]["resourcemap"] = {}
        rc, out = self.run_action("discover", p)
        self.assertEqual(rc, 90)


class GetConfig(Base):
    def make(self):
        return self.ordinary("legacy-web01", nics=[
            nic("Network Adapter", "00155D010203", vlan=100, ips=["10.0.0.5", "fe80::1", "169.254.1.1", "127.0.0.1"]),
            nic("net3", "00155D010204", vlan=0, mode="Untagged"),
            nic("trunk", "00155D010205", mode="Trunk")],
            disks=[disk("SCSI", 0, 1, "C:\\ClusterStorage\\Volume1\\web\\data.vhdx", 2 * GIB),
                   disk("SCSI", 0, 0, "C:\\ClusterStorage\\Volume1\\web\\root_ABC.avhdx", 40 * GIB, base="C:\\ClusterStorage\\Volume1\\web\\root.vhdx"),
                   disk("SCSI", 1, 0, "D:\\Local\\scratch.vhdx", GIB)],
            boot=[{"kind": "NetworkAdapter"}, {"kind": "HardDiskDrive", "ctype": "SCSI", "cnum": 0, "loc": 0}])

    def test_pre_adoption_config(self):
        vm, vmid = self.make()
        rc, cfg = self.run_action("getconfig", payload(vmid=vmid))
        self.assertEqual(rc, 0, cfg)
        assert_getconfig(self, cfg, vmid)
        self.assertEqual(cfg["identity"], {"cluster": CLUSTER, "vmid": vmid, "name": "legacy-web01", "node": "hv01", "uuid": vm["guid"]})
        self.assertEqual(cfg["memory_mb"], 2048)
        nics = {n["mac"]: n for n in cfg["nics"]}
        self.assertEqual(nics["00:15:5d:01:02:03"]["ip_addresses"], ["10.0.0.5"])
        self.assertEqual(nics["00:15:5d:01:02:03"]["vlan"], 100)
        self.assertEqual(nics["00:15:5d:01:02:04"], {"device_id": 3, "mac": "00:15:5d:01:02:04", "bridge": "vSwitch", "vlan": None, "ip_addresses": []})
        self.assertIsNone(nics["00:15:5d:01:02:05"]["bridge"])  # trunk: not representable, so not mappable
        disks = {d["device"]: d for d in cfg["disks"]}
        # The disk with a checkpoint is reported by its own file, not the checkpoint's differencing file.
        self.assertEqual(disks["scsi0"], {"device": "scsi0", "root": True, "storage": "Volume1", "volume": "web/root.vhdx", "size_bytes": 40 * GIB})
        self.assertEqual(disks["scsi25"], {"device": "scsi25", "root": False, "size_bytes": GIB})  # local disk: no volume

    def test_digest_tracks_configuration_not_power(self):
        vm, vmid = self.make()
        _, a = self.run_action("getconfig", payload(vmid=vmid))
        vm["state"] = "Off"
        _, b = self.run_action("getconfig", payload(vmid=vmid))
        self.assertEqual(a["config_digest"], b["config_digest"])
        vm["cpus"] = 4
        _, c = self.run_action("getconfig", payload(vmid=vmid))
        self.assertNotEqual(a["config_digest"], c["config_digest"])

    def test_refuses_busy_saved_and_dynamic(self):
        vm, vmid = self.make()
        for change, word in ((("ops", ["Migrating"]), "in progress"), (("state", "Saved"), "Saved"), (("dynamic", True), "dynamic")):
            saved = vm[change[0]]
            vm[change[0]] = change[1]
            rc, out = self.run_action("getconfig", payload(vmid=vmid))
            self.assertEqual(rc, 90)
            self.assertIn(word, out["error"])
            vm[change[0]] = saved

    def test_dynamic_memory_recorded_per_cpu_when_configured(self):
        vm, vmid = self.make()
        vm["dynamic"] = True
        p = payload(vmid=vmid)
        p["externaldetails"]["resourcemap"]["dynamic_memory_mb_per_cpu"] = "4096"
        rc, out = self.run_action("getconfig", p)
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["memory_mb"], 4096 * vm["cpus"])

    def test_unrepresentable_slot_refuses(self):
        vm, vmid = self.ordinary("x", disks=[disk("SCSI", 0, 40, "C:\\ClusterStorage\\Volume1\\x.vhdx")])
        rc, out = self.run_action("getconfig", payload(vmid=vmid))
        self.assertEqual(rc, 90)

    def test_generation1_root_is_ide00(self):
        vm, vmid = self.ordinary("g1", generation=1, disks=[disk("SCSI", 0, 0, "C:\\ClusterStorage\\Volume1\\d.vhdx"),
                                                            disk("IDE", 0, 0, "C:\\ClusterStorage\\Volume1\\r.vhdx")])
        _, cfg = self.run_action("getconfig", payload(vmid=vmid))
        roots = [d["device"] for d in cfg["disks"] if d["root"]]
        self.assertEqual(roots, ["ide0"])

    def test_wrong_name_refuses_for_a_created_instance(self):
        vm, vmid = self.ordinary("renamed-by-admin")
        rc, out = self.run_action("getconfig", payload("i-2-5-VM", vmid=vmid))
        self.assertEqual(rc, 90)


class NativeAdoption(Base):
    def test_read_and_leased_operation(self):
        vm, vmid = self.ordinary("legacy-web01")
        a = adoption("getconfig", "", vmid, lease=False)
        rc, cfg = self.run_action("getconfig", payload("i-2-9-VM", params={}, adopted=a))
        self.assertEqual(rc, 0, cfg)
        a = adoption("stop", "stop", vmid)
        rc, out = self.run_action("stop", payload("i-2-9-VM", adopted=a))
        self.assertEqual(rc, 0, out)
        self.assertEqual(vm["state"], "Off")

    def test_context_mismatches_are_known_failures(self):
        vm, vmid = self.ordinary("legacy-web01")
        cases = [adoption("stop", "stop", vmid, expires=time.time() - 1), adoption("stop", "stop", vmid, claim_id="other"),
                 adoption("stop", "reboot", vmid), adoption("stop", "stop", vmid, lease_id="nope")]
        bad_hash = adoption("stop", "stop", vmid)
        bad_hash["details"]["adopt_manifest_sha256"] = "0" * 64
        cases.append(bad_hash)
        for a in cases:
            rc, out = self.run_action("stop", payload("i-2-9-VM", adopted=a))
            self.assertEqual(rc, 90, out)
        self.assertEqual(self.writes(), [])
        self.assertEqual(vm["state"], "Running")

    def test_provider_name_must_match_manifest(self):
        vm, vmid = self.ordinary("renamed")
        rc, out = self.run_action("stop", payload("i-2-9-VM", adopted=adoption("stop", "stop", vmid)))
        self.assertEqual(rc, 90)
        self.assertIn("renamed", out["error"])

    def test_lifecycle_not_enabled(self):
        vm, vmid = self.ordinary("legacy-web01")
        p = payload("i-2-9-VM", adopted=adoption("stop", "stop", vmid), host={"native_adoption_lifecycle": "false"})
        rc, out = self.run_action("stop", p)
        self.assertEqual(rc, 90)

    def test_unlisted_custom_action(self):
        vm, vmid = self.ordinary("legacy-web01")
        rc, out = self.run_action("RefreshDetails", payload("i-2-9-VM", adopted=adoption("RefreshDetails", "", vmid)))
        self.assertEqual(rc, 90)

    def test_adopted_delete_requires_stopped_and_deletes(self):
        vm, vmid = self.ordinary("legacy-web01", disks=[disk("SCSI", 0, 0, "C:\\ClusterStorage\\Volume1\\w.vhdx")])
        rc, out = self.run_action("delete", payload("i-2-9-VM", adopted=adoption("delete", "delete", vmid)))
        self.assertEqual(rc, 90)
        vm["state"] = "Off"
        rc, out = self.run_action("delete", payload("i-2-9-VM", adopted=adoption("delete", "delete", vmid)))
        self.assertEqual(rc, 0)
        self.assertEqual(self.c.vms, {})

    def test_lost_answer_after_write_is_unknown(self):
        vm, vmid = self.ordinary("legacy-web01")
        self.c.lose.add("power")
        rc, out = self.run_action("stop", payload("i-2-9-VM", adopted=adoption("stop", "stop", vmid)))
        self.assertEqual(rc, 91)

    def test_statuses_with_bindings(self):
        a, va = self.ordinary("legacy-web01")
        b, vb = self.ordinary("legacy-db01", state="Off")
        c, vc = self.ordinary("i-2-5-VM")
        self.c.add_vm("elsewhere", node="HV02")
        bindings = {str(va): {"cloudstack_instance_name": "i-2-9-VM", "expected_proxmox_name": "legacy-web01"},
                    str(vb): {"cloudstack_instance_name": "i-2-10-VM", "expected_proxmox_name": "was-renamed"}}
        rc, out = self.run_action("statuses", payload(host={"native_adoption_status": "true"}, native_adoption_status_bindings=bindings))
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["power_state"], {"i-2-9-VM": "poweron", "i-2-10-VM": "unknown", "i-2-5-VM": "poweron"})

    def test_hoststats_single_read(self):
        rc, out = self.run_action("hoststats", payload())
        self.assertEqual(rc, 0, out)
        self.assertEqual(out["cpus"], 4)
        self.assertEqual((out["cpu_speed_mhz"], out["cpu_sockets"]), (2100, 1))
        self.assertEqual(out["network_rx_bytes"], 100)
        self.assertIsNone(out["load_average"])
        self.assertEqual([c[1] for c in self.c.calls], ["hoststats"])
        self.assertGreater(out["timestamp"], 0)

    def test_vmstats_bindings_local_and_nulls(self):
        a, va = self.ordinary("legacy-web01")
        b, vb = self.ordinary("renamed")
        self.ordinary("off", state="Off")
        self.ordinary("ordinary")
        self.c.add_vm("elsewhere", node="HV02")
        bindings = {str(va): {"cloudstack_instance_name": "i-2-9-VM", "expected_proxmox_name": "legacy-web01"},
                    str(vb): {"cloudstack_instance_name": "i-2-10-VM", "expected_proxmox_name": "old"}}
        rc, out = self.run_action("vmstats", payload(host={"native_adoption_status": "true"}, native_adoption_status_bindings=bindings))
        self.assertEqual(rc, 0, out)
        self.assertEqual(set(out["vms"]), {"i-2-9-VM", "ordinary"})
        self.assertEqual(out["vms"]["i-2-9-VM"]["network_rx_bytes"], 123)
        self.assertIsNone(out["vms"]["i-2-9-VM"]["disk_read_bytes"])
        self.assertIsNone(out["vms"]["i-2-9-VM"]["memory_free_kb"])
        self.assertEqual([c[1] for c in self.c.calls], ["vmstats"])

    def test_vmstats_bad_bindings_and_read_failure(self):
        rc, out = self.run_action("vmstats", payload(host={"native_adoption_status": "true"}))
        self.assertEqual(rc, 90)
        self.assertFalse(self.c.calls)
        self.c.lose.add("vmstats")
        rc, out = self.run_action("vmstats", payload())
        self.assertEqual(rc, 90)

    def test_vmstats_duplicate_key_fails_closed(self):
        self.ordinary("same")
        self.ordinary("same")
        rc, out = self.run_action("vmstats", payload())
        self.assertEqual(rc, 90)
        self.assertIn("Duplicate VM stats key", out["error"])

    def test_statuses_without_native(self):
        self.ordinary("i-2-5-VM", state="Off")
        rc, out = self.run_action("statuses", payload())
        self.assertEqual(out, {"status": "success", "power_state": {"i-2-5-VM": "poweroff"}})


class CreateAndLifecycle(Base):
    def test_prepare_then_create_from_iso(self):
        rc, patch = self.run_action("prepare", payload("i-2-5-VM"))
        self.assertEqual(rc, 0)
        vmid = int(patch["details"]["proxmox_vmid"])
        p = payload("i-2-5-VM", vmid=vmid, cdroms=[{"drive": 0, "volume": "Volume1:iso/cloudstack-a.iso", "bootable": True}])
        p["externaldetails"]["virtualmachine"].update(template_type="ISO", storage="Volume2", disk_size_gb="20", firmware="uefi", iso_os_type="win11")
        rc, out = self.run_action("create", p)
        self.assertEqual(rc, 0, out)
        assert_getconfig(self, out["config"], vmid)
        vm = list(self.c.vms.values())[0]
        self.assertEqual(vm["state"], "Running")
        self.assertEqual(hv.vmid_of(vm["guid"], vm["notes"]), vmid)
        self.assertEqual(out["config"]["disks"], [{"device": "scsi0", "root": True, "storage": "Volume2", "volume": "i-2-5-VM/i-2-5-VM-disk-0.vhdx", "size_bytes": 20 * GIB}])
        self.assertEqual(len(vm["dvds"]), 2)
        self.assertEqual(out["config"]["nics"][0]["vlan"], 100)

    def test_secure_boot_needs_shared_guardian(self):
        p = payload("i-2-5-VM", vmid=77)
        p["externaldetails"]["virtualmachine"].update(template_type="ISO", storage="Volume2", disk_size_gb="20", firmware="uefi-secure")
        rc, out = self.run_action("create", p)
        self.assertEqual(rc, 90)
        self.assertEqual(self.writes(), [])

    def test_failed_build_removes_the_new_vm(self):
        p = payload("i-2-5-VM", vmid=77)
        p["externaldetails"]["virtualmachine"].update(template_volume="Volume1:templates/w2025.vhdx", storage="Volume1")
        self.c.fail["vm_build"] = "Copy failed"
        rc, out = self.run_action("create", p)
        self.assertEqual(rc, 91)
        self.assertIn("removed", out["error"])
        self.assertEqual(self.c.vms, {})

    def test_create_refuses_a_taken_vmid(self):
        vm, vmid = self.ordinary("x")
        p = payload("i-2-5-VM", vmid=vmid)
        p["externaldetails"]["virtualmachine"].update(template_volume="Volume1:t.vhdx", storage="Volume1")
        rc, out = self.run_action("create", p)
        self.assertEqual(rc, 90)

    def test_start_syncs_cds_and_ha_when_stopped(self):
        vm, vmid = self.ordinary("i-2-5-VM", state="Off", dvds=[{"ctype": "SCSI", "cnum": 0, "loc": 1, "path": "C:\\ClusterStorage\\Volume1\\iso\\unknown.iso"}])
        p = payload("i-2-5-VM", vmid=vmid, cdroms=[{"drive": 0, "volume": "", "bootable": False}, {"drive": 1, "volume": "Volume1:iso/cloudstack-b.iso", "bootable": False}])
        p["cloudstack.vm.details"]["enableHA"] = True
        rc, out = self.run_action("start", p)
        self.assertEqual(rc, 0, out)
        self.assertEqual([d["path"] for d in vm["dvds"]], ["", "C:\\ClusterStorage\\Volume1\\iso\\cloudstack-b.iso"])
        self.assertTrue(vm["clustered"])
        self.assertEqual(vm["state"], "Running")

    def test_stop_and_delete_of_absent_instance_succeed(self):
        for action in ("stop", "delete"):
            rc, out = self.run_action(action, payload("i-2-5-VM", vmid=12345))
            self.assertEqual(rc, 0)

    def test_status(self):
        vm, vmid = self.ordinary("i-2-5-VM", state="Off")
        rc, out = self.run_action("status", payload("i-2-5-VM", vmid=vmid))
        self.assertEqual(out["power_state"], "poweroff")

    def test_reboot_without_shutdown_service_is_known(self):
        vm, vmid = self.ordinary("i-2-5-VM", shutdown=False)
        rc, out = self.run_action("reboot", payload("i-2-5-VM", vmid=vmid))
        self.assertEqual(rc, 90)

    def test_getconsole_through_the_helper(self):
        vm, vmid = self.ordinary("i-2-5-VM")
        seen = {}

        def helper(path, request, timeout):
            seen.update(request)
            return {"status": "success", "console": {"host": "10.0.0.5", "port": 5901, "password": "Ab3dE6gH", "session": "x"}}
        hv.console_request, saved = helper, hv.console_request
        try:
            rc, out = self.run_action("getconsole", payload("i-2-5-VM", vmid=vmid))
        finally:
            hv.console_request = saved
        self.assertEqual(out, {"status": "success", "console": {"host": "10.0.0.5", "port": 5901, "password": "Ab3dE6gH",
                                                                "passwordonetimeuseonly": True, "protocol": "vnc"}})
        self.assertEqual((seen["node"], seen["vmid"]), ("hv01.lab.test", vm["guid"]))

    def test_getconsole_refusals(self):
        vm, vmid = self.ordinary("i-2-5-VM", state="Off")
        rc, out = self.run_action("getconsole", payload("i-2-5-VM", vmid=vmid))
        self.assertEqual(rc, 90)
        vm["state"] = "Running"
        p = payload("i-2-5-VM", vmid=vmid)
        p["externaldetails"]["extension"]["console_helper_socket"] = "/nonexistent/helper.sock"
        rc, out = self.run_action("getconsole", p)
        self.assertEqual(rc, 90)
        self.assertIn("helper is not available", out["error"])

    def test_setha(self):
        vm, vmid = self.ordinary("i-2-5-VM")
        rc, out = self.run_action("setha", payload("i-2-5-VM", vmid=vmid, params={"enabled": True}))
        self.assertEqual(out, {"status": "success", "ha_enabled": True})
        rc, out = self.run_action("setha", payload("i-2-5-VM", vmid=vmid, params={"enabled": False}))
        self.assertEqual(out, {"status": "success", "ha_enabled": False})  # running: the role goes, the VM stays up
        self.assertEqual(vm["state"], "Running")

    def test_scale(self):
        vm, vmid = self.ordinary("i-2-5-VM")
        rc, out = self.run_action("scale", payload("i-2-5-VM", vmid=vmid, params={"cpunumber": 2, "memory": 2048}))
        self.assertEqual((rc, self.writes()), (0, []))  # already so: no write, even running
        rc, out = self.run_action("scale", payload("i-2-5-VM", vmid=vmid, params={"cpunumber": 4, "memory": 4096}))
        self.assertEqual(rc, 90)
        vm["state"] = "Off"
        rc, out = self.run_action("scale", payload("i-2-5-VM", vmid=vmid, params={"cpunumber": 4, "memory": 4096}))
        self.assertEqual(out, {"status": "success", "cpus": 4, "memory_mb": 4096})


class Nics(Base):
    def test_plug_and_unplug(self):
        vm, vmid = self.ordinary("i-2-5-VM")
        params = {"nic": {"mac": "02:00:00:00:02:02", "deviceId": 1, "broadcastUri": "vlan://101"}, "expectedpowerstate": "running"}
        rc, out = self.run_action("plugnic", payload("i-2-5-VM", vmid=vmid, params=params))
        self.assertEqual(rc, 0, out)
        self.assertEqual(vm["nics"][0]["name"], "net1")
        _, cfg = self.run_action("getconfig", payload("i-2-5-VM", vmid=vmid))
        self.assertEqual(cfg["nics"][0]["device_id"], 1)
        rc, out = self.run_action("unplugnic", payload("i-2-5-VM", vmid=vmid, params=params))
        self.assertEqual((rc, vm["nics"]), (0, []))
        rc, out = self.run_action("unplugnic", payload("i-2-5-VM", vmid=vmid, params=params))  # already absent
        self.assertEqual(rc, 0)

    def test_power_disagreement_refused(self):
        vm, vmid = self.ordinary("i-2-5-VM", state="Off")
        params = {"nic": {"mac": "02:00:00:00:02:02", "deviceId": 1, "broadcastUri": "vlan://101"}, "expectedpowerstate": "running"}
        rc, out = self.run_action("plugnic", payload("i-2-5-VM", vmid=vmid, params=params))
        self.assertEqual(rc, 90)

    def test_untagged_network_refused(self):
        vm, vmid = self.ordinary("i-2-5-VM")
        params = {"nic": {"mac": "02:00:00:00:02:02", "deviceId": 1, "broadcastUri": "vxlan://5"}, "expectedpowerstate": "running"}
        rc, out = self.run_action("plugnic", payload("i-2-5-VM", vmid=vmid, params=params))
        self.assertEqual(rc, 90)

    def test_ip_address(self):
        vm, vmid = self.ordinary("i-2-5-VM", nics=[nic("net0", "020000000101", ips=["10.0.0.9", "192.168.1.4"])])
        rc, out = self.run_action("getipaddress", payload("i-2-5-VM", vmid=vmid, params={"macaddress": "02:00:00:00:01:01", "cidr": "192.168.1.0/24"}))
        self.assertEqual(out, {"status": "success", "ip_address": "192.168.1.4"})
        vm["kvp"] = False
        rc, out = self.run_action("getipaddress", payload("i-2-5-VM", vmid=vmid, params={"macaddress": "02:00:00:00:01:01"}))
        self.assertEqual(rc, 90)


class Disks(Base):
    def vm(self, **kw):
        return self.ordinary("i-2-5-VM", disks=[disk("SCSI", 0, 0, "C:\\ClusterStorage\\Volume1\\i-2-5-VM\\i-2-5-VM-disk-0.vhdx", 20 * GIB)],
                             boot=[{"kind": "HardDiskDrive", "ctype": "SCSI", "cnum": 0, "loc": 0}],
                             dvds=[{"ctype": "SCSI", "cnum": 0, "loc": 1, "path": ""}], **kw)

    def test_add_grow_move_delete(self):
        vm, vmid = self.vm()
        rc, out = self.run_action("adddisk", payload("i-2-5-VM", vmid=vmid, params={"storage": "Volume1", "sizegb": 5}))
        self.assertEqual(out, {"status": "success", "device": "scsi2", "volume": "Volume1:i-2-5-VM/i-2-5-VM-disk-1.vhdx", "size_bytes": 5 * GIB})
        rc, out = self.run_action("resizedisk", payload("i-2-5-VM", vmid=vmid, params={"device": "scsi2", "volume": out["volume"], "sizegb": 8}))
        self.assertEqual(out["size_bytes"], 8 * GIB)
        rc, out = self.run_action("movedisk", payload("i-2-5-VM", vmid=vmid, params={"device": "scsi2", "volume": out["volume"], "storage": "Volume2"}))
        self.assertEqual(out, {"status": "success", "device": "scsi2", "volume": "Volume2:i-2-5-VM/i-2-5-VM-disk-1.vhdx", "size_bytes": 8 * GIB})
        rc, out = self.run_action("deletedisk", payload("i-2-5-VM", vmid=vmid, params={"device": "scsi2", "volume": out["volume"]}))
        self.assertEqual(rc, 0)
        self.assertEqual(len(vm["disks"]), 1)

    def test_refusals(self):
        vm, vmid = self.vm()
        root = "Volume1:i-2-5-VM/i-2-5-VM-disk-0.vhdx"
        cases = [("deletedisk", {"device": "scsi0", "volume": root}, "root"),
                 ("resizedisk", {"device": "scsi0", "volume": root, "sizegb": 10}, "smaller"),
                 ("resizedisk", {"device": "scsi0", "volume": "Volume1:other.vhdx", "sizegb": 30}, "another disk"),
                 ("movedisk", {"device": "scsi0", "volume": root, "storage": "Nope"}, "target")]
        for action, params, word in cases:
            rc, out = self.run_action(action, payload("i-2-5-VM", vmid=vmid, params=params))
            self.assertEqual(rc, 90, (action, out))
            self.assertIn(word, out["error"])
        vm["checkpoints"] = [{"name": "c", "parent": "", "state": "Off"}]
        rc, out = self.run_action("adddisk", payload("i-2-5-VM", vmid=vmid, params={"storage": "Volume1", "sizegb": 5}))
        self.assertEqual(rc, 90)
        self.assertIn("checkpoints", out["error"])

    def test_lost_add_answer_is_unknown_and_a_rerun_does_not_duplicate(self):
        vm, vmid = self.vm()
        self.c.lose.add("disk")
        rc, out = self.run_action("adddisk", payload("i-2-5-VM", vmid=vmid, params={"storage": "Volume1", "sizegb": 5}))
        self.assertEqual(rc, 91)
        self.c.lose.clear()
        rc, out = self.run_action("adddisk", payload("i-2-5-VM", vmid=vmid, params={"storage": "Volume1", "sizegb": 5}))
        self.assertEqual(rc, 0)
        self.assertEqual(out["volume"], "Volume1:i-2-5-VM/i-2-5-VM-disk-2.vhdx")  # a new file name, never the lost one

    def test_liststorage(self):
        rc, out = self.run_action("liststorage", payload(host={"smb_storages": "{}"}))
        self.assertEqual(rc, 0)
        self.assertEqual(out["storages"][0], {"storage": "Volume1", "type": "csv", "shared": True, "enabled": True, "active": True,
                                              "total": 1000 * GIB, "used": GIB, "backing": "csv/@hvc01/volume1", "monitors": []})


class Isos(Base):
    VOL = "Volume1:iso/cloudstack-1.iso"

    def test_attach_detach(self):
        vm, vmid = self.ordinary("i-2-5-VM", dvds=[{"ctype": "SCSI", "cnum": 0, "loc": 1, "path": "C:\\x.iso"},
                                                   {"ctype": "SCSI", "cnum": 0, "loc": 2, "path": ""}])
        rc, out = self.run_action("attachiso", payload("i-2-5-VM", vmid=vmid, params={"volume": self.VOL, "drive": 0}))
        self.assertEqual(rc, 0, out)
        self.assertEqual(vm["dvds"][0]["path"], "C:\\x.iso")  # an unknown disc is never replaced
        self.assertEqual(vm["dvds"][1]["path"], "C:\\ClusterStorage\\Volume1\\iso\\cloudstack-1.iso")
        rc, out = self.run_action("detachiso", payload("i-2-5-VM", vmid=vmid, params={"volume": self.VOL}))
        self.assertEqual((rc, vm["dvds"][1]["path"]), (0, ""))

    def test_attach_with_no_empty_drive(self):
        vm, vmid = self.ordinary("i-2-5-VM", dvds=[{"ctype": "SCSI", "cnum": 0, "loc": 1, "path": "C:\\x.iso"}])
        rc, out = self.run_action("attachiso", payload("i-2-5-VM", vmid=vmid, params={"volume": self.VOL, "drive": 0}))
        self.assertEqual(rc, 90)
        self.assertIn("no empty CD drive", out["error"])

    def test_fetch(self):
        rc, out = self.run_action("fetchiso", payload(params={"volume": self.VOL}))
        self.assertEqual(out, {"status": "success", "present": False, "volume": self.VOL})
        params = {"volume": self.VOL, "size": 1234, "url": "http://ssvm/iso", "checksumalgorithm": "sha512", "checksum": "ab" * 64}
        rc, out = self.run_action("fetchiso", payload(params=params))
        self.assertEqual((rc, out["present"], out["size_bytes"]), (0, True, 1234))
        rc, out = self.run_action("fetchiso", payload(params={"volume": self.VOL, "size": 1234}))
        self.assertTrue(out["present"])

    def test_fetch_refusals(self):
        params = {"volume": self.VOL, "url": "http://ssvm/iso", "checksumalgorithm": "sha224", "checksum": "ab" * 28}
        rc, out = self.run_action("fetchiso", payload(params=params))
        self.assertEqual(rc, 90)
        self.c.iso_checksum = "cd" * 64
        params.update(checksumalgorithm="sha512", checksum="ab" * 64)
        rc, out = self.run_action("fetchiso", payload(params=params))
        self.assertEqual(rc, 90)
        self.assertIn("checksum", out["error"])
        self.assertNotIn("ssvm", json.dumps(out))

    def test_find_and_remove(self):
        held = "C:\\ClusterStorage\\Volume1\\iso\\cloudstack-1.iso"
        self.c.files[held] = 10
        vm, vmid = self.ordinary("web", node="HV02", dvds=[{"ctype": "SCSI", "cnum": 0, "loc": 1, "path": held}])
        rc, out = self.run_action("findiso", payload(params={"volume": self.VOL}))
        self.assertEqual(out["guests"], [{"vmid": vmid, "node": "hv02", "name": "web", "slots": ["SCSI0:1"]}])
        rc, out = self.run_action("removeiso", payload(params={"volume": self.VOL}))
        self.assertEqual(rc, 90)
        vm["dvds"][0]["path"] = ""
        rc, out = self.run_action("removeiso", payload(params={"volume": self.VOL}))
        self.assertEqual(rc, 0)
        self.assertNotIn(held, self.c.files)

    def test_bad_volume(self):
        rc, out = self.run_action("fetchiso", payload(params={"volume": "Volume1:iso/../x.iso"}))
        self.assertEqual(rc, 90)


class Checkpoints(Base):
    def test_lifecycle(self):
        vm, vmid = self.ordinary("i-2-5-VM")
        mem = {"snap_name": "i-2-5-VM_VS_1", "snap_save_memory": "true", "snap_description": ""}
        rc, out = self.run_action("createsnapshot", payload("i-2-5-VM", vmid=vmid, params=mem))
        self.assertEqual(out["snapshot"], {"name": "i-2-5-VM_VS_1", "memory": True, "parent": ""})
        rc, out = self.run_action("createsnapshot", payload("i-2-5-VM", vmid=vmid, params=mem))
        self.assertEqual(rc, 90)  # a duplicate name is refused, not taken as success
        rc, out = self.run_action("revertsnapshot", payload("i-2-5-VM", vmid=vmid, params={"snap_name": "i-2-5-VM_VS_1", "snap_save_memory": "false"}))
        self.assertEqual(rc, 90)  # kind mismatch
        rc, out = self.run_action("revertsnapshot", payload("i-2-5-VM", vmid=vmid, params=mem))
        self.assertEqual(rc, 0)
        rc, out = self.run_action("deletesnapshot", payload("i-2-5-VM", vmid=vmid, params=mem))
        self.assertEqual((rc, vm["checkpoints"]), (0, []))
        rc, out = self.run_action("deletesnapshot", payload("i-2-5-VM", vmid=vmid, params=mem))
        self.assertEqual(rc, 0)  # already absent

    def test_memory_needs_running(self):
        vm, vmid = self.ordinary("i-2-5-VM", state="Off")
        rc, out = self.run_action("createsnapshot", payload("i-2-5-VM", vmid=vmid, params={"snap_name": "s", "snap_save_memory": "true"}))
        self.assertEqual(rc, 90)


if __name__ == "__main__":
    unittest.main()


class Firmware(Base):
    def create(self, firmware, **vmd):
        rc, patch = self.run_action("prepare", payload("i-2-6-VM"))
        vmid = int(patch["details"]["proxmox_vmid"])
        p = payload("i-2-6-VM", vmid=vmid, cdroms=[{"drive": 0, "volume": "Volume1:iso/cloudstack-a.iso", "bootable": True}])
        p["externaldetails"]["virtualmachine"].update(template_type="ISO", storage="Volume1", disk_size_gb="10", firmware=firmware, **vmd)
        rc, out = self.run_action("create", p)
        new = [c for c in self.c.calls if c[1] == "vm_new"]
        return rc, out, (new[-1][2] if new else None), [c[2] for c in self.c.calls if c[1] == "vm_build"]

    def test_bios_is_generation_1_and_uefi_generation_2(self):
        rc, out, new, build = self.create("bios")
        self.assertEqual((rc, new["generation"], build[0]["firmware"]), (0, 1, "bios"))
        self.assertEqual(out["config"]["disks"][0]["device"], "ide0")
        self.c = FakeCluster()
        rc, out, new, build = self.create("uefi")
        self.assertEqual((rc, new["generation"], build[0]["firmware"]), (0, 2, "uefi"))

    def test_secure_boot_template_follows_the_guest_os(self):
        rc, out, new, build = self.create("uefi-secure", iso_os_type="l26", )
        self.assertEqual(rc, 90)  # no shared guardian
        p_ext = {"hyperv_shared_guardian": "true"}
        self.c = FakeCluster()
        rc, patch = self.run_action("prepare", payload("i-2-6-VM"))
        p = payload("i-2-6-VM", vmid=int(patch["details"]["proxmox_vmid"]))
        p["externaldetails"]["extension"].update(p_ext)
        p["externaldetails"]["virtualmachine"].update(template_type="ISO", storage="Volume1", disk_size_gb="10", firmware="uefi-secure", iso_os_type="l26")
        rc, out = self.run_action("create", p)
        build = [c[2] for c in self.c.calls if c[1] == "vm_build"][0]
        self.assertEqual((rc, build["firmware"], build["sbtemplate"]), (0, "uefi-secure", "MicrosoftUEFICertificateAuthority"))

    def test_unknown_firmware(self):
        rc, out, new, build = self.create("efi")
        self.assertEqual((rc, new), (90, None))
