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
package org.apache.cloudstack.hypervisor.external.discovery;

import org.apache.cloudstack.api.BaseResponse;
import com.google.gson.annotations.SerializedName;

public class ExternalInstanceResponse extends BaseResponse {
    @SerializedName("cluster") private String cluster;
    @SerializedName("vmid") private long vmid;
    @SerializedName("node") private String node;
    @SerializedName("name") private String name;
    @SerializedName("type") private String type;
    @SerializedName("power") private String power;
    @SerializedName("template") private boolean template;
    public ExternalInstanceResponse(String cluster, long vmid, String node, String name,
                                    String type, String power, boolean template) {
        this.cluster=cluster; this.vmid=vmid; this.node=node; this.name=name;
        this.type=type; this.power=power; this.template=template;
        setObjectName("externalinstance");
    }
    public long getVmid() { return vmid; }
}
