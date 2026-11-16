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
package org.apache.cloudstack.hypervisor.external.ha;

import java.util.List;

import javax.inject.Inject;

import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VirtualMachine;

/**
 * Host and instance liveness for External hosts. The orchestrator (for example a Proxmox or Hyper-V failover
 * cluster) owns instance HA, so instances are never declared dead here. A host is reported Down only when its
 * extension declares host health support, CloudStack cannot reach the node, and another up node of the same
 * cluster confirms the node is down; otherwise Up if the node answers, else Disconnected (Alert after alert.wait).
 */
public class ExternalInvestigator extends AdapterBase implements Investigator {

    @Inject
    HostDao hostDao;
    @Inject
    ExternalProvisioner externalProvisioner;

    @Override
    public Status getHostAgentStatus(Host agent) {
        if (agent.getHypervisorType() != HypervisorType.External) {
            return null;
        }
        if (externalProvisioner == null || hostDao == null || agent.getClusterId() == null) {
            return Status.Disconnected;
        }
        String own = externalProvisioner.getHostHealth(agent.getGuid(), null);
        if (own == null) {
            return Status.Disconnected;
        }
        if (ExternalProvisioner.HOST_HEALTH_UP.equals(own)) {
            return Status.Up;
        }
        List<HostVO> peers = hostDao.findByClusterId(agent.getClusterId());
        for (HostVO peer : peers == null ? List.<HostVO>of() : peers) {
            if (peer.getId() == agent.getId() || peer.getRemoved() != null || peer.getStatus() != Status.Up
                    || peer.getType() != Host.Type.Routing) {
                continue;
            }
            String seen = externalProvisioner.getHostHealth(peer.getGuid(), agent.getId());
            if (ExternalProvisioner.HOST_HEALTH_DOWN.equals(seen)) {
                logger.info("Host {} is down according to its cluster peer {}", agent, peer);
                return Status.Down;
            }
        }
        return Status.Disconnected;
    }

    @Override
    public boolean isVmAlive(VirtualMachine vm, Host host) throws UnknownVM {
        if (vm.getHypervisorType() == HypervisorType.External) {
            return true;
        }
        throw new UnknownVM();
    }
}
