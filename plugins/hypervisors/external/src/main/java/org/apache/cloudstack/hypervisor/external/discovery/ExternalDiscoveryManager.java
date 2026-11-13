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

import java.util.*;
import javax.inject.Inject;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.AccountService;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.*;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.vo.ExtensionDetailsVO;

/** Read-only discovery: a source inventory is not an adoption or write authorization. */
public class ExternalDiscoveryManager extends ManagerBase implements PluggableService {
    @Inject HostDao hostDao;
    @Inject ExtensionHelper extensionHelper;
    @Inject ExtensionDetailsDao extensionDetailsDao;
    @Inject ExtensionsManager extensionsManager;
    @Inject AgentManager agentManager;
    @Inject AccountService accountService;
    @Override public List<Class<?>> getCommands() { return List.of(ListExternalInstancesCmd.class); }

    public List<ExternalInstanceResponse> discover(Long hostId) {
        if (!accountService.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
            throw new InvalidParameterValueException("External discovery requires root administrator access");
        }
        HostVO host=hostId==null ? null : hostDao.findById(hostId);
        if (host==null || host.getHypervisorType()!=HypervisorType.External || host.getClusterId()==null) {
            throw new InvalidParameterValueException("An External host with a cluster is required");
        }
        Extension ext=extensionHelper.getExtensionForCluster(host.getClusterId());
        ExtensionDetailsVO capability=ext==null ? null : extensionDetailsDao.findDetail(ext.getId(), "instancediscoversupported");
        if (ext==null || ext.getType()!=Extension.Type.Orchestrator || ext.getState()!=Extension.State.Enabled
                || capability==null || capability.getValue()==null || !"true".equalsIgnoreCase(capability.getValue().trim())) {
            throw new InvalidParameterValueException("The extension does not support native discovery");
        }
        Map<String,Map<String,String>> access=extensionsManager.getExternalAccessDetails(host,null);
        String cluster=access.getOrDefault("host",Collections.emptyMap()).get("proxmox_cluster");
        if (cluster==null || cluster.isEmpty() || !cluster.equals(cluster.trim())) {
            throw new InvalidParameterValueException("The host needs an exact external cluster identity");
        }
        RunCustomActionCommand cmd=new RunCustomActionCommand("discover");
        cmd.setExternalDetails(access); cmd.setWait(60);
        Answer answer=agentManager.easySend(hostId,cmd);
        if (answer==null || !answer.getResult()) throw new CloudRuntimeException("External inventory collection failed");
        return parse(answer.getDetails(),cluster);
    }

    static List<ExternalInstanceResponse> parse(String payload,String expectedCluster) {
        try {
            if (payload==null || payload.length()>8*1024*1024) throw new IllegalArgumentException();
            JsonObject obj=JsonParser.parseString(payload).getAsJsonObject();
            if (!"success".equals(text(obj,"status")) || !expectedCluster.equals(text(obj,"cluster"))) throw new IllegalArgumentException();
            JsonArray rows=obj.getAsJsonArray("instances");
            if (rows==null || rows.size()>100000) throw new IllegalArgumentException();
            Set<Long> ids=new HashSet<>(); List<ExternalInstanceResponse> result=new ArrayList<>();
            for (JsonElement element:rows) {
                JsonObject row=element.getAsJsonObject(); JsonPrimitive value=row.getAsJsonPrimitive("vmid");
                if (value==null || !value.isNumber()) throw new IllegalArgumentException();
                long vmid=value.getAsBigDecimal().longValueExact();
                String node=text(row,"node"), type=text(row,"type"), power=text(row,"power"), name=text(row,"name");
                if (vmid<=0 || !ids.add(vmid) || !node.matches("[A-Za-z0-9][A-Za-z0-9.-]*")
                    || !Set.of("qemu","lxc").contains(type) || !Set.of("running","stopped","unknown").contains(power)
                    || !row.get("template").isJsonPrimitive() || !row.getAsJsonPrimitive("template").isBoolean()) throw new IllegalArgumentException();
                result.add(new ExternalInstanceResponse(expectedCluster,vmid,node,name,type,power,row.get("template").getAsBoolean()));
            }
            return result;
        } catch (RuntimeException e) { throw new InvalidParameterValueException("Invalid or ambiguous external inventory snapshot"); }
    }
    private static String text(JsonObject obj,String key) {
        JsonPrimitive p=obj.getAsJsonPrimitive(key);
        if (p==null || !p.isString()) throw new IllegalArgumentException();
        return p.getAsString();
    }
}
