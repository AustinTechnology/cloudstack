// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

import java.util.Locale;

public final class ObservationValidation {
    private ObservationValidation() { }

    /**
     * A NIC's VLAN as CloudStack records it. The NIC keeps a copy of its network's broadcast URI only while it is
     * reserved: a stop through CloudStack releases it and clears the copy, and NICs recorded by the legacy sync never
     * had one. The network's URI then stands. Null when the two disagree or the network has none.
     */
    public static String vlanUri(Object nicUri, Object networkUri) {
        if (networkUri == null) return null;
        String network = networkUri.toString();
        return nicUri == null || nicUri.toString().equals(network) ? network : null;
    }
    public static String mac(String value) {
        if (value == null || !value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            throw new IllegalArgumentException("invalid MAC");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if ((Integer.parseInt(normalized.substring(0, 2), 16) & 1) != 0 || normalized.equals("00:00:00:00:00:00")) {
            throw new IllegalArgumentException("invalid MAC");
        }
        return normalized;
    }
    public static boolean usableIp(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
            return false;
        }
        String[] parts = value.split("\\.");
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) { return false; }
        }
        return n[0] != 0 && n[0] != 127 && n[0] < 224 && !(n[0] == 169 && n[1] == 254);
    }
}
