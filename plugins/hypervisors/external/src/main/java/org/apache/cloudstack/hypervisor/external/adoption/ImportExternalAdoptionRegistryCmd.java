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
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import com.cloud.user.Account;

@APICommand(name = "importExternalAdoptionRegistry", description = "Import the legacy adoption registry export, read from "
        + ExternalAdoptionManager.EXPORT_FILE + " on this management server, into the native registry, fenced until handover",
        responseObject = ExternalAdoptionRegistryResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin}, httpMethod = "POST", since = "4.23.0")
public class ImportExternalAdoptionRegistryCmd extends BaseCmd {
    @Inject ExternalAdoptionManager manager;
    @Parameter(name = "dryrun", type = CommandType.BOOLEAN, description = "Validate without importing; defaults to true")
    private Boolean dryRun;

    @Override public long getEntityOwnerId() { return Account.ACCOUNT_ID_SYSTEM; }
    @Override public void execute() {
        ExternalAdoptionRegistryResponse response = manager.importExportFile(dryRun == null || dryRun);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
