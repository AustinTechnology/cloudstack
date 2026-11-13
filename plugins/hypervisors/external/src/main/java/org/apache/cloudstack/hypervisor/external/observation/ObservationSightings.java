// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

final class ObservationSightings {
    private ObservationSightings() { }
    // Called only after a new, unique capture has been durably inserted in this transaction.
    static int advance(String newIp, long host, long oldHost, String oldIp, int oldCount, boolean fresh) {
        if (newIp == null) { return 0; }
        if (host == oldHost && newIp.equals(oldIp) && fresh) { return Math.min(2, oldCount + 1); }
        return 1;
    }
}
