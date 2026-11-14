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
package com.cloud.hypervisor;

import java.util.List;

import com.cloud.template.VirtualMachineTemplate;
import com.cloud.vm.VirtualMachine;

/**
 * The copies of an ISO that External orchestrators keep on their own storage. A copy is made the first time an
 * ISO is used on a cluster; nothing in CloudStack records it.
 */
public interface ExternalIsoCopies {
    /** Whether the orchestrator of an External instance can change what is in its CD drives. */
    boolean supported(VirtualMachine vm);

    /**
     * Removes the copies of an ISO that has been deleted, from one zone or from every zone when zoneId is null.
     * An instance that still has the copy in a CD drive at its orchestrator has it ejected first.
     *
     * @return one line for every copy that was kept and why; empty when none is left
     */
    List<String> removeCopies(VirtualMachineTemplate iso, Long zoneId);
}
