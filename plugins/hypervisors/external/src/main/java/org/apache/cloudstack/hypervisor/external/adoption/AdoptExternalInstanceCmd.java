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

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import com.cloud.user.Account;

@APICommand(name = "adoptExternalInstance", description = "Adopt an instance that already exists at the external provider. "
        + "Only CloudStack records are written; the provider instance is not changed, stopped or restarted",
        responseObject = ExternalAdoptionResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin}, httpMethod = "POST", since = "4.23.0")
public class AdoptExternalInstanceCmd extends BaseCmd {
    @Inject ExternalAdoptionManager manager;
    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, required = true,
            description = "Any External host of the provider cluster that holds the instance")
    private Long hostId;
    @Parameter(name = "vmid", type = CommandType.INTEGER, required = true, description = "The provider ID of the instance")
    private Integer vmid;
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class, required = true,
            description = "A custom offering, or a fixed offering with exactly the CPU and memory of the instance")
    private Long serviceOfferingId;
    @Parameter(name = ApiConstants.TEMPLATE_ID, type = CommandType.UUID, entityType = TemplateResponse.class, required = true,
            description = "An External template to record for the instance")
    private Long templateId;
    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "Owner account; requires domainid. Defaults to the caller")
    private String accountName;
    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class, description = "Domain of the owner account")
    private Long domainId;
    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "Display name; defaults to the provider name")
    private String name;
    @Parameter(name = "cpuspeed", type = CommandType.INTEGER, description = "CPU speed in MHz, for a custom offering that has none")
    private Integer cpuSpeed;
    @Parameter(name = "dryrun", type = CommandType.BOOLEAN, description = "Report what would be adopted without writing; defaults to true")
    private Boolean dryRun;

    @Override public long getEntityOwnerId() { return Account.ACCOUNT_ID_SYSTEM; }
    @Override public void execute() {
        NativeInstanceAdoption.Request request = new NativeInstanceAdoption.Request(hostId, vmid == null ? 0 : vmid, serviceOfferingId,
                templateId, accountName, domainId, name, cpuSpeed, dryRun == null || dryRun);
        ExternalAdoptionResponse response = manager.adopt(request);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
