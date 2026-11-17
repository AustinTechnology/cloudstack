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
package org.apache.cloudstack.hypervisor.external.resource;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.agent.api.GetVmStatsAnswer;
import com.cloud.agent.api.HostStatsEntry;
import com.cloud.agent.api.VmStatsEntry;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.framework.extensions.command.ExtensionRoutingUpdateCommand;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.CheckHealthAnswer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.CleanupNetworkRulesCmd;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotBaseCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.GetExternalConsoleAnswer;
import com.cloud.agent.api.GetExternalConsoleCommand;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.GetVmIpAddressCommand;
import com.cloud.agent.api.GetVmStatsCommand;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.MaintainAnswer;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.agent.api.PrepareExternalProvisioningAnswer;
import com.cloud.agent.api.PrepareExternalProvisioningCommand;
import com.cloud.agent.api.SetupPersistentNetworkAnswer;
import com.cloud.agent.api.SetupPersistentNetworkCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.RunCustomActionAnswer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.host.Host;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Networks;
import com.cloud.resource.ServerResource;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.ComponentContext;

public class ExternalResource implements ServerResource {
    protected Logger logger = LogManager.getLogger(getClass());
    protected static final int CPU = 0;
    protected static final long CPU_SPEED = 0;
    protected static final long RAM = 0;
    protected static final long DOM0_RAM = 0;
    protected static final String CAPABILITIES = "hvm";

    protected ExternalProvisioner externalProvisioner;

    protected String url;
    protected String dcId;
    protected String pod;
    protected String cluster;
    protected String name;
    protected String guid;
    private final Host.Type type;

    private String extensionName;
    private String extensionRelativePath;
    private Extension.State extensionState;
    private boolean extensionPathReady;

    protected boolean isExtensionDisconnected() {
        return StringUtils.isAnyBlank(extensionName, extensionRelativePath);
    }

    protected boolean isExtensionNotEnabled() {
        return !Extension.State.Enabled.equals(extensionState);
    }

    protected boolean isExtensionPathNotReady() {
        return !extensionPathReady;
    }

    public ExternalResource() {
        type = Host.Type.Routing;
    }

    @Override
    public Host.Type getType() {
        return type;
    }

    @Override
    public StartupCommand[] initialize() {
        // Do not (re)connect a known host whose node is not up: the host stays Down/Disconnected/Alert instead of
        // flapping to Up until the next missed ping. New hosts (no GUID match yet) and extensions that do not
        // declare host health support connect as before.
        if (!isExtensionDisconnected()) {
            String health = externalProvisioner.getHostHealth(guid, null);
            if (health != null && !ExternalProvisioner.HOST_HEALTH_UP.equals(health)) {
                logger.warn("Not connecting External host {}: its node health is {}", name, health);
                return null;
            }
        }
        // Real node capacity when the extension reports host statistics; zero (as before) otherwise. The totals are
        // read on each (re)connect, so a hardware change shows after the host reconnects.
        int cpus = CPU;
        long cpuSpeed = CPU_SPEED;
        long ram = RAM;
        int sockets = 0;
        if (!isExtensionDisconnected()) {
            try {
                JsonObject values = metricsResponse(externalProvisioner.getMetrics(guid, extensionName, extensionRelativePath, false));
                if (values != null) {
                    double count = required(values, "cpus");
                    double memoryKb = required(values, "total_memory_kb");
                    if (count >= 1 && count == Math.floor(count) && memoryKb > 0) {
                        cpus = (int) count;
                        ram = (long) memoryKb * 1024L;
                        cpuSpeed = (long) number(values, "cpu_speed_mhz", 0);
                        sockets = (int) number(values, "cpu_sockets", 0);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not read the capacity of External host {}", name);
            }
        }
        final StartupRoutingCommand cmd =
                new StartupRoutingCommand(cpus, cpuSpeed, ram, DOM0_RAM, CAPABILITIES,
                        Hypervisor.HypervisorType.External,Networks.RouterPrivateIpStrategy.HostLocal);
        if (sockets > 0) {
            cmd.setCpuSockets(sockets);
        }
        cmd.setDataCenter(dcId);
        cmd.setPod(pod);
        cmd.setCluster(cluster);
        cmd.setHostType(type);
        cmd.setName(name);
        cmd.setPrivateIpAddress(Hypervisor.HypervisorType.External.toString());
        cmd.setGuid(guid);
        cmd.setIqn(guid);
        cmd.setVersion(ExternalResource.class.getPackage().getImplementationVersion());
        // As a KVM host does: how many CD drives an instance can have here. vm.iso.max.count decides within it.
        Map<String, String> hostDetails = cmd.getHostDetails() == null ? new java.util.HashMap<>() : cmd.getHostDetails();
        hostDetails.put(Host.HOST_CDROM_MAX_COUNT, "2");
        cmd.setHostDetails(hostDetails);
        return new StartupCommand[] {cmd};
    }

    @Override
    public PingCommand getCurrentStatus(long id) {
        if (isExtensionDisconnected()) {
            return new PingRoutingCommand(Host.Type.Routing, id, new HashMap<>());
        }
        final Map<String, HostVmStateReportEntry> vmStates = externalProvisioner.getHostVmStateReport(id, extensionName,
                extensionRelativePath);
        return new PingRoutingCommand(Host.Type.Routing, id, vmStates);
    }

    @Override
    public Answer executeRequest(Command cmd) {
        try {
            if (cmd instanceof ExtensionRoutingUpdateCommand) {
                return execute((ExtensionRoutingUpdateCommand)cmd);
            } else if (cmd instanceof PingTestCommand) {
                return execute((PingTestCommand) cmd);
            } else if (cmd instanceof ReadyCommand) {
                return execute((ReadyCommand)cmd);
            } else if (cmd instanceof CheckHealthCommand) {
                return execute((CheckHealthCommand) cmd);
            } else if (cmd instanceof CheckNetworkCommand) {
                return execute((CheckNetworkCommand)cmd);
            } else if (cmd instanceof SetupPersistentNetworkCommand) {
                return new SetupPersistentNetworkAnswer((SetupPersistentNetworkCommand)cmd, false,
                        "External network setup is managed outside CloudStack");
            }else if (cmd instanceof CleanupNetworkRulesCmd) {
                return execute((CleanupNetworkRulesCmd) cmd);
            } else if (cmd instanceof GetVmStatsCommand) {
                return execute((GetVmStatsCommand) cmd);
            } else if (cmd instanceof MaintainCommand) {
                return execute((MaintainCommand) cmd);
            } else if (cmd instanceof StartCommand) {
                return execute((StartCommand) cmd);
            } else if (cmd instanceof StopCommand) {
                return execute((StopCommand) cmd);
            } else if (cmd instanceof RebootCommand) {
                return execute((RebootCommand) cmd);
            } else if (cmd instanceof GetExternalConsoleCommand) {
                return execute((GetExternalConsoleCommand) cmd);
            } else if (cmd instanceof PrepareExternalProvisioningCommand) {
                return execute((PrepareExternalProvisioningCommand) cmd);
            } else if (cmd instanceof GetHostStatsCommand) {
                return execute((GetHostStatsCommand) cmd);
            } else if (cmd instanceof RunCustomActionCommand) {
                return execute((RunCustomActionCommand) cmd);
            } else if (cmd instanceof CheckVirtualMachineCommand) {
                return execute((CheckVirtualMachineCommand) cmd);
            } else if (cmd instanceof PlugNicCommand) {
                return execute((PlugNicCommand) cmd);
            } else if (cmd instanceof UnPlugNicCommand) {
                return execute((UnPlugNicCommand) cmd);
            } else if (cmd instanceof GetVmIpAddressCommand) {
                return execute((GetVmIpAddressCommand) cmd);
            } else if (cmd instanceof AttachCommand && isIso(((AttachCommand) cmd).getDisk())) {
                return execute((AttachCommand) cmd);
            } else if (cmd instanceof DettachCommand && isIso(((DettachCommand) cmd).getDisk())) {
                return execute((DettachCommand) cmd);
            } else if (cmd instanceof CreateVMSnapshotCommand || cmd instanceof RevertToVMSnapshotCommand || cmd instanceof DeleteVMSnapshotCommand) {
                return execute((VMSnapshotBaseCommand) cmd);
            } else {
                return execute(cmd);
            }
        } catch (IllegalArgumentException e) {
            // The exception may have occurred after dispatch. Preserve allocations on an
            // ambiguous NIC result, and return the typed answer expected by orchestration.
            if (cmd instanceof PlugNicCommand) {
                return new PlugNicAnswer((PlugNicCommand)cmd, false, "NIC_OUTCOME_UNKNOWN: " + e.getMessage());
            }
            if (cmd instanceof UnPlugNicCommand) {
                return new UnPlugNicAnswer((UnPlugNicCommand)cmd, false, "NIC_OUTCOME_UNKNOWN: " + e.getMessage());
            }
            if (cmd instanceof VMSnapshotBaseCommand) {
                return ExternalProvisioner.snapshotAnswer((VMSnapshotBaseCommand) cmd, false, e.getMessage(), null);
            }
            return new Answer(cmd, false, e.getMessage());
        }
    }

    protected String logAndGetExtensionNotConnectedOrDisabledError() {
        if (isExtensionDisconnected()) {
            logger.error("Extension not connected to host: {}", name);
            return "Extension not connected";
        }
        if (isExtensionNotEnabled()) {
            logger.error("Extension: {} connected to host: {} is not in Enabled state", extensionName, name);
            return "Extension is disabled";
        }
        logger.error("Extension: {} connected to host: {} is not having path in Ready state", extensionName, name);
        return "Extension is not ready";
    }

    private Answer execute(ExtensionRoutingUpdateCommand cmd) {
        if (StringUtils.isNotBlank(extensionName) && !extensionName.equals(cmd.getExtensionName())) {
            return new Answer(cmd, false, "Not same extension");
        }
        if (cmd.isRemoved()) {
            extensionName = null;
            extensionRelativePath = null;
            extensionState = Extension.State.Disabled;
            return new Answer(cmd);
        }
        extensionName = cmd.getExtensionName();
        extensionRelativePath = cmd.getExtensionRelativePath();
        extensionState = cmd.getExtensionState();
        return new Answer(cmd);
    }

    private Answer execute(PingTestCommand cmd) {
        // An External host is not a machine CloudStack can ping from; claiming success made the ping investigator
        // report every unreachable External host as merely "disconnected" (it "pinged" the address "External").
        return new Answer(cmd, false, "An External host cannot run ping tests");
    }

    private Answer execute(ReadyCommand cmd) {
        return new ReadyAnswer(cmd);
    }

    private Answer execute(CheckHealthCommand cmd) {
        if (isExtensionDisconnected()) {
            logAndGetExtensionNotConnectedOrDisabledError();
            return new CheckHealthAnswer(cmd, false);
        }
        // Without host health support this only says the extension is connected, as before.
        String health = externalProvisioner.getHostHealth(guid, null);
        return new CheckHealthAnswer(cmd, health == null || ExternalProvisioner.HOST_HEALTH_UP.equals(health));
    }

    private Answer execute(CheckNetworkCommand cmd) {
        return new CheckNetworkAnswer(cmd, true, "Network Setup check by names is done");
    }

    private Answer execute(CleanupNetworkRulesCmd cmd) {
        if (isExtensionDisconnected()) {
            return new Answer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return new Answer(cmd, false, "Not supported");
    }

    private static class VmSample {
        final double timestamp;
        final JsonObject counters;
        VmSample(double timestamp, JsonObject counters) {
            this.timestamp = timestamp;
            this.counters = counters;
        }
    }

    private final Map<String, VmSample> vmSamples = new HashMap<>();

    private static double number(JsonObject obj, String key, double unknown) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return unknown;
        }
        double value = obj.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Invalid metrics value");
        }
        return value;
    }

    private static double required(JsonObject obj, String key) {
        double value = number(obj, key, -1);
        if (value < 0) {
            throw new IllegalArgumentException("Missing metrics value");
        }
        return value;
    }

    private static JsonObject metricsResponse(String response) {
        if (response == null) {
            return null;
        }
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        if (!obj.has("status") || !"success".equals(obj.get("status").getAsString())) {
            return null;
        }
        required(obj, "timestamp");
        return obj;
    }

    private static double delta(JsonObject current, JsonObject previous, String field, double divisor) {
        double now = number(current, field, -1);
        double before = previous == null ? -1 : number(previous, field, -1);
        return now < 0 || before < 0 || now < before ? 0 : (now - before) / divisor;
    }

    private synchronized Answer execute(GetVmStatsCommand cmd) {
        if (isExtensionDisconnected()) {
            return new Answer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        try {
            JsonObject response = metricsResponse(externalProvisioner.getMetrics(guid, extensionName, extensionRelativePath, true));
            if (response != null && response.has("vms") && response.get("vms").isJsonObject()) {
                double timestamp = required(response, "timestamp");
                JsonObject vms = response.getAsJsonObject("vms");
                HashMap<String, VmStatsEntry> result = new HashMap<>();
                Map<String, VmSample> next = new HashMap<>();
                for (String vm : cmd.getVmNames()) {
                    if (!vms.has(vm) || !vms.get(vm).isJsonObject()) {
                        continue;
                    }
                    JsonObject values = vms.getAsJsonObject(vm);
                    VmSample previous = vmSamples.get(vm);
                    if (previous != null && timestamp <= previous.timestamp) {
                        // The clock went back: this sample is a new baseline, as after a restart.
                        previous = null;
                    }
                    VmStatsEntry stats = new VmStatsEntry();
                    try {
                        double cpus = required(values, "cpus");
                        double cpu = required(values, "cpu_utilization");
                        if (cpus < 1 || cpus > Integer.MAX_VALUE || cpus != Math.floor(cpus) || cpu < 0) {
                            throw new IllegalArgumentException("Invalid CPU metrics");
                        }
                        stats.setNumCPUs((int) cpus);
                        // A provider can count the whole guest process (I/O threads included) against its vCPUs.
                        stats.setCPUUtilization(Math.min(cpu, 100));
                        stats.setMemoryKBs(required(values, "memory_kb"));
                    } catch (RuntimeException invalid) {
                        // One instance's bad values do not void the others'.
                        logger.debug("Skipping invalid metrics of instance {} on host {}: {}", vm, name, invalid.getMessage());
                        continue;
                    }
                    stats.setIntFreeMemoryKBs(number(values, "memory_free_kb", -1));
                    stats.setTargetMemoryKBs(number(values, "memory_target_kb", -1));
                    stats.setEntityType("vm");
                    JsonObject old = previous == null ? null : previous.counters;
                    stats.setNetworkReadKBs(delta(values, old, "network_rx_bytes", 1024));
                    stats.setNetworkWriteKBs(delta(values, old, "network_tx_bytes", 1024));
                    stats.setDiskReadKBs(delta(values, old, "disk_read_bytes", 1024));
                    stats.setDiskWriteKBs(delta(values, old, "disk_write_bytes", 1024));
                    stats.setDiskReadIOs(delta(values, old, "disk_read_ios", 1));
                    stats.setDiskWriteIOs(delta(values, old, "disk_write_ios", 1));
                    result.put(vm, stats);
                    next.put(vm, new VmSample(timestamp, values));
                }
                // Missing requested VMs lose their baseline so a later restart starts at zero.
                cmd.getVmNames().forEach(vmSamples::remove);
                vmSamples.putAll(next);
                return new GetVmStatsAnswer(cmd, result);
            }
        } catch (Exception e) {
            logger.debug("Could not collect External instance metrics for host {}: {}", name, e.getMessage());
        }
        return new Answer(cmd, false, "Not supported");
    }

    private MaintainAnswer execute(MaintainCommand cmd) {
        return new MaintainAnswer(cmd, false);
    }

    public GetHostStatsAnswer execute(GetHostStatsCommand cmd) {
        if (isExtensionDisconnected()) {
            logAndGetExtensionNotConnectedOrDisabledError();
        } else {
            try {
                JsonObject values = metricsResponse(externalProvisioner.getMetrics(guid, extensionName, extensionRelativePath, false));
                if (values != null) {
                    double cpu = required(values, "cpu_utilization");
                    double cpus = required(values, "cpus");
                    double total = required(values, "total_memory_kb");
                    double free = required(values, "free_memory_kb");
                    if (cpu > 100 || cpus < 1 || cpus != Math.floor(cpus) || free > total) {
                        throw new IllegalArgumentException("Invalid host metrics");
                    }
                    HostStatsEntry stats = new HostStatsEntry(cmd.getHostId(), cpu,
                            number(values, "network_rx_bytes", 0) / 1024,
                            number(values, "network_tx_bytes", 0) / 1024, "host", total, free, 0,
                            number(values, "load_average", -1));
                    return new GetHostStatsAnswer(cmd, stats);
                }
            } catch (Exception e) {
                logger.debug("Could not collect External host metrics for host {}", name);
            }
        }
        return new GetHostStatsAnswer(cmd, null);
    }

    public StartAnswer execute(StartCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new StartAnswer(cmd, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.startInstance(guid, extensionName, extensionRelativePath, cmd);
    }

    public StopAnswer execute(StopCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new StopAnswer(cmd, logAndGetExtensionNotConnectedOrDisabledError(), false);
        }
        if (cmd.isExpungeVM()) {
            return externalProvisioner.expungeInstance(guid, extensionName, extensionRelativePath, cmd);
        }
        return externalProvisioner.stopInstance(guid, extensionName, extensionRelativePath, cmd);
    }

    public RebootAnswer execute(RebootCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new RebootAnswer(cmd, logAndGetExtensionNotConnectedOrDisabledError(), false);
        }
        return externalProvisioner.rebootInstance(guid, extensionName, extensionRelativePath, cmd);
    }

    public GetExternalConsoleAnswer execute(GetExternalConsoleCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new GetExternalConsoleAnswer(cmd, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.getInstanceConsole(guid, extensionName, extensionRelativePath, cmd);
    }

    public PrepareExternalProvisioningAnswer execute(PrepareExternalProvisioningCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new PrepareExternalProvisioningAnswer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.prepareExternalProvisioning(guid, extensionName, extensionRelativePath, cmd);
    }

    public RunCustomActionAnswer execute(RunCustomActionCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new RunCustomActionAnswer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.runCustomAction(guid, extensionName, extensionRelativePath, cmd);
    }

    public CheckVirtualMachineAnswer execute(CheckVirtualMachineCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new CheckVirtualMachineAnswer(cmd, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.checkInstance(guid, extensionName, extensionRelativePath, cmd);
    }

    public Answer execute(GetVmIpAddressCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new Answer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.getInstanceIpAddress(guid, extensionName, extensionRelativePath, cmd);
    }

    private static boolean isIso(com.cloud.agent.api.to.DiskTO disk) {
        return disk != null && disk.getType() == com.cloud.storage.Volume.Type.ISO;
    }

    public Answer execute(AttachCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new Answer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.attachInstanceIso(guid, extensionName, extensionRelativePath, cmd);
    }

    public Answer execute(DettachCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new Answer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.detachInstanceIso(guid, extensionName, extensionRelativePath, cmd);
    }

    public Answer execute(VMSnapshotBaseCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return ExternalProvisioner.snapshotAnswer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError(), null);
        }
        return externalProvisioner.changeInstanceSnapshot(guid, extensionName, extensionRelativePath, cmd);
    }

    public PlugNicAnswer execute(PlugNicCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new PlugNicAnswer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.plugInstanceNic(guid, extensionName, extensionRelativePath, cmd);
    }

    public UnPlugNicAnswer execute(UnPlugNicCommand cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new UnPlugNicAnswer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        return externalProvisioner.unplugInstanceNic(guid, extensionName, extensionRelativePath, cmd);
    }

    public Answer execute(Command cmd) {
        if (isExtensionDisconnected() || isExtensionNotEnabled() || isExtensionPathNotReady()) {
            return new Answer(cmd, false, logAndGetExtensionNotConnectedOrDisabledError());
        }
        // A command without a handler carries nothing the extension could act on, so it is not passed on.
        return Answer.createUnsupportedCommandAnswer(cmd);
    }

    @Override
    public void disconnected() {

    }

    @Override
    public IAgentControl getAgentControl() {
        return null;
    }

    @Override
    public void setAgentControl(IAgentControl agentControl) {

    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public void setName(String name) {

    }

    @Override
    public void setConfigParams(Map<String, Object> params) {

    }

    @Override
    public Map<String, Object> getConfigParams() {
        return null;
    }

    @Override
    public int getRunLevel() {
        return 0;
    }

    @Override
    public void setRunLevel(int level) {

    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        try {
            externalProvisioner = ComponentContext.getDelegateComponentOfType(ExternalProvisioner.class);
        } catch (NoSuchBeanDefinitionException e) {
            throw new ConfigurationException(
                    String.format("Unable to find an ExternalProvisioner for the external resource: %s", name)
            );
        }
        externalProvisioner.configure(name, params);
        dcId = (String)params.get("zone");
        pod = (String)params.get("pod");
        cluster = (String)params.get("cluster");
        this.name = name;
        guid = (String)params.get("guid");
        extensionName = (String)params.get("extensionName");
        extensionRelativePath = (String)params.get("extensionRelativePath");
        extensionState = (Extension.State)params.get("extensionState");
        extensionPathReady = (boolean)params.get("extensionPathReady");
        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }
}
