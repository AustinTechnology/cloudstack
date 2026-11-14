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
package org.apache.cloudstack.hypervisor.external.adoption;

import java.util.List;
import org.apache.cloudstack.api.BaseResponse;
import com.google.gson.annotations.SerializedName;

public class ExternalAdoptionResponse extends BaseResponse {
    @SerializedName("dryrun") private boolean dryRun;
    @SerializedName("cluster") private String cluster;
    @SerializedName("vmid") private int vmid;
    @SerializedName("node") private String node;
    @SerializedName("name") private String name;
    @SerializedName("power") private String power;
    @SerializedName("cpunumber") private int cpus;
    @SerializedName("memory") private int memoryMb;
    @SerializedName("nics") private List<String> nics;
    @SerializedName("virtualmachineid") private String vmUuid;
    @SerializedName("instancename") private String instanceName;
    @SerializedName("claimid") private String claimId;
    @SerializedName("generation") private Integer generation;
    @SerializedName("volumechanges") private List<org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeSync.Change> volumes;
    @SerializedName("volumenotes") private List<String> volumeNotes;

    ExternalAdoptionResponse(boolean dryRun, String cluster, int vmid, String node, String name, String power, int cpus, int memoryMb, List<String> nics) {
        this.dryRun = dryRun; this.cluster = cluster; this.vmid = vmid; this.node = node; this.name = name; this.power = power;
        this.cpus = cpus; this.memoryMb = memoryMb; this.nics = nics;
        setObjectName("externaladoption");
    }
    void adopted(String vmUuid, String instanceName, String claimId, int generation) {
        this.vmUuid = vmUuid; this.instanceName = instanceName; this.claimId = claimId; this.generation = generation;
    }
    void volumes(List<org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeSync.Change> volumes, List<String> notes) {
        this.volumes = volumes; this.volumeNotes = notes;
    }
    public boolean isDryRun() { return dryRun; }
    public String getVmUuid() { return vmUuid; }
    public List<String> getNics() { return nics; }
}
