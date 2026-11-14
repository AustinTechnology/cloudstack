// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.external.refresh;

import java.util.List;
import org.apache.cloudstack.api.BaseResponse;
import com.google.gson.annotations.SerializedName;

public class ExternalVmRefreshResponse extends BaseResponse {
    @SerializedName("id") private String id;
    @SerializedName("dryrun") private boolean dryRun;
    @SerializedName("applied") private boolean applied;
    @SerializedName("cpus") private int cpus;
    @SerializedName("memorymb") private int memoryMb;
    @SerializedName("currentcpus") private int currentCpus;
    @SerializedName("currentmemorymb") private int currentMemoryMb;
    @SerializedName("power") private String power;
    @SerializedName("agentavailable") private boolean agentAvailable;
    @SerializedName("unresolved") private List<String> unresolved;
    @SerializedName("ipchanges") private List<IpChange> ipChanges;
    @SerializedName("nicimports") private List<String> nicImports;
    @SerializedName("volumechanges") private List<org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeSync.Change> volumeChanges;

    public static final class IpChange {
        @SerializedName("nicid") final String nicId;
        @SerializedName("previous") final String previous;
        @SerializedName("proposed") final String proposed;
        @SerializedName("source") final String source;
        @SerializedName("applied") final boolean applied;
        IpChange(String nicId, String previous, String proposed, String source, boolean applied) {
            this.nicId = nicId; this.previous = previous; this.proposed = proposed; this.source = source; this.applied = applied;
        }
    }

    ExternalVmRefreshResponse(String id, boolean dryRun, boolean applied, ExternalVmConfiguration snapshot,
            int currentCpus, int currentMemoryMb, List<String> unresolved, List<IpChange> ipChanges, List<String> nicImports,
            List<org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeSync.Change> volumeChanges) {
        this.nicImports = nicImports;
        this.volumeChanges = volumeChanges;
        this.id = id;
        this.dryRun = dryRun;
        this.applied = applied;
        this.cpus = snapshot.cpus;
        this.memoryMb = snapshot.memoryMb;
        this.currentCpus = currentCpus;
        this.currentMemoryMb = currentMemoryMb;
        this.power = snapshot.power;
        this.agentAvailable = snapshot.agentAvailable;
        this.unresolved = unresolved;
        this.ipChanges = ipChanges;
        setObjectName("externalvmrefresh");
    }
}
