// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

import static org.junit.Assert.*;
import org.junit.Test;

public class ObservationProvenanceTest {
    @Test public void providerIdentityAndTopologyMustAllAgree() {
        String[] a = {"cluster", "100", "node1", "i-2-3-VM", "02:00:00:00:00:01", "vmbr0", "100"};
        assertTrue(matches(a, a));
        for (int i = 0; i < a.length; i++) {
            String[] changed = a.clone(); changed[i] = i == 6 ? "101" : a[i] + "-changed";
            assertFalse("field " + i, matches(a, changed));
        }
        String[] missing = a.clone(); missing[5] = null;
        assertFalse(matches(a, missing));
    }
    private boolean matches(String[] a, String[] b) {
        return ObservationProvenance.providerMatches(a[0], a[1], a[2], a[3], a[4], a[5], Integer.valueOf(a[6]),
                b[0], b[1], b[2], b[3], b[4], b[5], Integer.valueOf(b[6]));
    }
    @Test public void everyBindingFieldAndFieldBoundaryChangesFingerprint() {
        String[] fields = {"vm-uuid", "7", "cluster", "host", "node", "100", "name", "nic-uuid", "network-uuid", "mac", "bridge", "vlan"};
        String original = ObservationProvenance.fingerprint(fields);
        assertEquals(64, original.length());
        assertEquals(original, ObservationProvenance.fingerprint(fields));
        for (int i = 0; i < fields.length; i++) {
            String[] changed = fields.clone(); changed[i] += "x";
            assertNotEquals("field " + i, original, ObservationProvenance.fingerprint(changed));
        }
        assertNotEquals(ObservationProvenance.fingerprint("ab", "c"), ObservationProvenance.fingerprint("a", "bc"));
    }
}
