package org.apache.cloudstack.hypervisor.external.storage;

/** Access to the package-private storage inventory parser. Lab check only. */
public class HypervStorageCheck {
    public static int parse(String payload, String cluster) {
        return ExternalStorageInventory.parse(payload, cluster).size();
    }
}
