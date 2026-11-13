// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.external.refresh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A report only: callers must still prove network/subnet/IP ownership under a lock. */
public final class ExternalVmTopologyDiff {
    public static final class NativeNic {
        public final long id;
        public final long networkId;
        public final String mac;
        public final boolean networkIdentityKnown;
        public final String bridge;
        public final Integer vlan;
        public final String ipAddress;

        public NativeNic(long id, long networkId, String mac, boolean networkIdentityKnown,
                String bridge, Integer vlan, String ipAddress) {
            this.id = id;
            this.networkId = networkId;
            this.mac = mac;
            this.networkIdentityKnown = networkIdentityKnown;
            this.bridge = bridge;
            this.vlan = vlan;
            this.ipAddress = ipAddress;
        }
    }

    public static final class IpCandidate {
        public final long nicId;
        public final long networkId;
        public final String previous;
        public final String observed;

        private IpCandidate(NativeNic nic, String observed) {
            this.nicId = nic.id;
            this.networkId = nic.networkId;
            this.previous = nic.ipAddress;
            this.observed = observed;
        }
    }

    public final List<String> unresolved;
    public final List<IpCandidate> ipCandidates;

    private ExternalVmTopologyDiff(List<String> unresolved, List<IpCandidate> ipCandidates) {
        this.unresolved = Collections.unmodifiableList(unresolved);
        this.ipCandidates = Collections.unmodifiableList(ipCandidates);
    }

    private static String normalizeMac(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            throw new IllegalArgumentException("Native NIC has a malformed MAC");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** Exclude unspecified, loopback, link-local, multicast and reserved addresses. */
    private static boolean usable(String ip) {
        String[] parts = ip.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first != 0 && first != 127 && first < 224 && !(first == 169 && second == 254);
    }

    public static ExternalVmTopologyDiff compare(ExternalVmConfiguration snapshot, List<NativeNic> nativeNics) {
        Map<String, NativeNic> byMac = new HashMap<>();
        for (NativeNic nic : nativeNics) {
            String mac = normalizeMac(nic.mac);
            if (byMac.put(mac, nic) != null) {
                throw new IllegalArgumentException("Ambiguous native NIC MAC");
            }
        }
        Set<String> providerMacs = new HashSet<>();
        Map<String, Integer> addressUse = new HashMap<>();
        for (ExternalVmConfiguration.Nic nic : snapshot.nics) {
            providerMacs.add(nic.mac);
            if (snapshot.agentAvailable) {
                for (String ip : nic.ipAddresses) {
                    if (usable(ip)) {
                        addressUse.put(ip, addressUse.getOrDefault(ip, 0) + 1);
                    }
                }
            }
        }
        List<String> unresolved = new ArrayList<>();
        List<IpCandidate> candidates = new ArrayList<>();
        for (ExternalVmConfiguration.Nic nic : snapshot.nics) {
            NativeNic nativeNic = byMac.get(nic.mac);
            if (nativeNic == null) {
                unresolved.add("provider-only NIC " + nic.deviceId + " (" + nic.mac + ")");
                continue;
            }
            if (!nativeNic.networkIdentityKnown || nativeNic.bridge == null || nic.bridge == null) {
                unresolved.add("network identity unknown for NIC " + nic.mac);
                continue;
            }
            if (!Objects.equals(nativeNic.bridge, nic.bridge) || !Objects.equals(nativeNic.vlan, nic.vlan)) {
                unresolved.add("network topology differs for NIC " + nic.mac);
                continue;
            }
            if (!snapshot.agentAvailable) {
                unresolved.add("guest agent unavailable for NIC " + nic.mac);
                continue;
            }
            List<String> usableAddresses = new ArrayList<>();
            for (String ip : nic.ipAddresses) {
                if (usable(ip)) {
                    usableAddresses.add(ip);
                }
            }
            if (usableAddresses.size() != 1 || addressUse.get(usableAddresses.get(0)) != 1) {
                unresolved.add("no unique usable guest IPv4 for NIC " + nic.mac);
                continue;
            }
            String observed = usableAddresses.get(0);
            if (!Objects.equals(nativeNic.ipAddress, observed)) {
                candidates.add(new IpCandidate(nativeNic, observed));
            }
        }
        for (Map.Entry<String, NativeNic> entry : byMac.entrySet()) {
            if (!providerMacs.contains(entry.getKey())) {
                unresolved.add("native-only NIC " + entry.getValue().id + " (" + entry.getKey() + ")");
            }
        }
        Collections.sort(unresolved);
        return new ExternalVmTopologyDiff(unresolved, candidates);
    }
}
