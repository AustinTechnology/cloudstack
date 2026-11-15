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

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.cloud.user.Account;

@APICommand(name = "refreshExternalVirtualMachine", description = "Read fresh external instance configuration and optionally reconcile supported metadata",
        responseObject = ExternalVmRefreshResponse.class, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin}, since = "4.23.0")
public class RefreshExternalVirtualMachineCmd extends BaseCmd {
    @Inject ExternalVmRefreshManager manager;
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = UserVmResponse.class, required = true,
            description = "The external virtual machine")
    private Long id;
    @Parameter(name = "dryrun", type = CommandType.BOOLEAN, description = "Report changes without applying them; defaults to true")
    private Boolean dryRun;

    @Override public long getEntityOwnerId() {
        com.cloud.uservm.UserVm vm = id == null ? null : _responseGenerator.findUserVmById(id);
        return vm == null ? Account.ACCOUNT_ID_SYSTEM : vm.getAccountId();
    }
    @Override public void execute() {
        ExternalVmRefreshResponse response = manager.refresh(id, dryRun == null || dryRun);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
