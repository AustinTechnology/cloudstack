// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Canonical, length-delimited binding provenance; not a provider incarnation fence. */
final class ObservationProvenance {
    private ObservationProvenance() { }
    static String fingerprint(String... fields) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String s : fields) {
                byte[] bytes = Objects.requireNonNull(s).getBytes(StandardCharsets.UTF_8);
                d.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); d.update(bytes);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : d.digest()) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static boolean providerMatches(String expectedCluster, String expectedVmid, String expectedNode, String expectedName,
            String expectedMac, String expectedBridge, Integer expectedVlan, String cluster, String vmid, String node,
            String name, String mac, String bridge, Integer vlan) {
        return expectedCluster != null && expectedVmid != null && expectedNode != null && expectedName != null
                && expectedMac != null && expectedBridge != null && expectedVlan != null
                && expectedCluster.equals(cluster) && expectedVmid.equals(vmid) && expectedNode.equals(node)
                && expectedName.equals(name) && expectedMac.equals(mac) && expectedBridge.equals(bridge)
                && expectedVlan.equals(vlan);
    }
}
