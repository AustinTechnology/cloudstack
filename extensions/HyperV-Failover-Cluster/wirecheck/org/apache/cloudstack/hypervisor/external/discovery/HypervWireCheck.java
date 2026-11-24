package org.apache.cloudstack.hypervisor.external.discovery;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmConfiguration;

/** Runs CloudStack's own parsers over sample answers from the Hyper-V extension. Lab check only. */
public class HypervWireCheck {
    public static void main(String[] args) throws Exception {
        String dir = args[0], cluster = args[1];
        String discover = new String(Files.readAllBytes(Paths.get(dir, "discover.json")), "UTF-8");
        System.out.println("discover rows: " + ExternalDiscoveryManager.parse(discover, cluster).size());
        String config = new String(Files.readAllBytes(Paths.get(dir, "getconfig.json")), "UTF-8");
        ExternalVmConfiguration c = ExternalVmConfiguration.parse(config);
        System.out.println("getconfig ok: nics=" + c.nics.size() + " disks=" + c.disks.size());
        String storage = new String(Files.readAllBytes(Paths.get(dir, "liststorage.json")), "UTF-8");
        System.out.println("liststorage rows: " + org.apache.cloudstack.hypervisor.external.storage.HypervStorageCheck.parse(storage, cluster));
    }
}
