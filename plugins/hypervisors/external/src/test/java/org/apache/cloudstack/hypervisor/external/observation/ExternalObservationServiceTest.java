// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

import static org.junit.Assert.*;
import org.junit.Test;

public class ExternalObservationServiceTest {
    @Test public void unpublishedBindingFailsClosedBeforeAnyDatabaseAccess() throws Exception {
        ExternalObservationService service = new ExternalObservationService();
        assertTrue(service.getCommands().contains(SubmitExternalIpObservationsCmd.class));
        try {
            service.submit(1, 2, "00000000-0000-0000-0000-000000000001", "[]");
            fail("unfenced observation submit must not be accepted");
        } catch (IllegalStateException expected) { }
        assertNull(service.fallback(1, "02:00:00:00:00:01", null));
    }
    @Test public void validatesUnicastAndNormalizesMac() {
        assertEquals("02:aa:00:00:00:01", ObservationValidation.mac("02:AA:00:00:00:01"));
        assertTrue(ObservationValidation.usableIp("10.0.0.9"));
        assertFalse(ObservationValidation.usableIp("169.254.1.1"));
        assertFalse(ObservationValidation.usableIp("224.0.0.1"));
        assertFalse(ObservationValidation.usableIp("10.0.0.999"));
        try { ObservationValidation.mac("01:00:00:00:00:01"); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void parseRetainsConflictsAndScopesByVmid() {
        String input = "[{\"vmid\":1,\"mac\":\"02:00:00:00:00:01\",\"ips\":[\"10.1.1.1\",\"10.1.1.2\"]},"
                + "{\"vmid\":2,\"mac\":\"02:00:00:00:00:01\",\"ips\":[\"10.1.1.3\"]}]";
        assertNull(ExternalObservationService.parse(input).get(0).ip);
        assertEquals("10.1.1.3", ExternalObservationService.parse(input).get(1).ip);
        String duplicate = "[{\"vmid\":1,\"mac\":\"02:00:00:00:00:01\",\"ips\":[\"10.1.1.1\"]},"
                + "{\"vmid\":1,\"mac\":\"02:00:00:00:00:01\",\"ips\":[\"10.1.1.2\"]}]";
        try { ExternalObservationService.parse(duplicate); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void countRequiresNewMatchingFreshHostObservation() {
        assertEquals(1, ObservationSightings.advance("10.1.1.1", 1, 1, null, 0, true));
        assertEquals(2, ObservationSightings.advance("10.1.1.1", 1, 1, "10.1.1.1", 1, true));
        assertEquals(1, ObservationSightings.advance("10.1.1.1", 2, 1, "10.1.1.1", 2, true));
        assertEquals(1, ObservationSightings.advance("10.1.1.1", 1, 1, "10.1.1.1", 2, false));
        assertEquals(1, ObservationSightings.advance("10.1.1.2", 1, 1, "10.1.1.1", 2, true));
        assertEquals(0, ObservationSightings.advance(null, 1, 1, "10.1.1.1", 2, true));
    }
}
