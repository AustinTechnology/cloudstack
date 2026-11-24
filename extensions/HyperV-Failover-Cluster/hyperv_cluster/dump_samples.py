"""Writes sample discover/getconfig/liststorage answers from the simulated cluster, for checking with CloudStack's own parsers."""
import json
import os
import sys

import test_hyperv_cluster as t

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)


class Dump(t.Base):
    def runTest(self):
        pass


d = Dump()
d.setUp()
g = t.GetConfig()
g.c = d.c
vm, vmid = g.make()
d.c.add_vm("db01", node="HV02", state="Off", clustered=True)
samples = {}
rc, samples["discover"] = d.run_action("discover", t.payload())
rc, samples["getconfig"] = d.run_action("getconfig", t.payload(vmid=vmid))
rc, samples["liststorage"] = d.run_action("liststorage", t.payload())
for name, body in samples.items():
    with open(os.path.join(out_dir, name + ".json"), "w") as f:
        f.write(json.dumps(body, separators=(",", ":")))
print(json.dumps({"vmid": vmid, "cluster": t.CLUSTER}))
