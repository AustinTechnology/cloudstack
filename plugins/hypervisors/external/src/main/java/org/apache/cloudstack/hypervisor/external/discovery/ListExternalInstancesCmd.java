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

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;
import java.util.List;

@APICommand(name="listExternalInstances", description="Read fresh external cluster inventory without adopting or changing guests",
    responseObject=ExternalInstanceResponse.class, responseHasSensitiveInfo=false, authorized={RoleType.Admin}, since="4.23.0")
public class ListExternalInstancesCmd extends BaseCmd {
    @Inject ExternalDiscoveryManager manager;
    @Parameter(name=ApiConstants.HOST_ID, type=CommandType.UUID, entityType=HostResponse.class,
        required=true, description="External host used to query its entire orchestrator cluster")
    private Long hostId;
    @Override public long getEntityOwnerId() { return com.cloud.user.Account.ACCOUNT_ID_SYSTEM; }
    @Override public void execute() {
        List<ExternalInstanceResponse> instances=manager.discover(hostId);
        ListResponse<ExternalInstanceResponse> response=new ListResponse<>();
        response.setResponses(instances, instances.size());
        response.setResponseName(getCommandName()); setResponseObject(response);
    }
}
