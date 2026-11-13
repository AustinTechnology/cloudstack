// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

/** Fresh native and provider binding. This is a metadata hint, not ownership authority. */
public interface ObservationBindingResolver {
    Binding current(long cloudStackVmId, String exactMac);
    Binding source(long clusterId, long hostId, int vmid, String exactMac);

    final class Binding {
        public final long cloudStackVmId, clusterId, hostId;
        public final int vmid;
        public final String mac, fingerprint;
        public Binding(long cloudStackVmId, long clusterId, long hostId, int vmid, String mac, String fingerprint) {
            this.cloudStackVmId = cloudStackVmId; this.clusterId = clusterId; this.hostId = hostId;
            this.vmid = vmid; this.mac = mac; this.fingerprint = fingerprint;
        }
    }
}
