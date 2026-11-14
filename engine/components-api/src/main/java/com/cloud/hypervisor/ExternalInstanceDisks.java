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

import com.cloud.offering.DiskOffering;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.vm.VirtualMachine;

/**
 * Changes to the disks of an External hypervisor instance. A disk of such an instance exists only as part of
 * the instance at its orchestrator, so each change is made there first and recorded when it has succeeded.
 */
public interface ExternalInstanceDisks {
    /** Creates the disk of a data volume that has never been created, on the instance, and records it attached. */
    Volume add(VirtualMachine vm, Volume volume);

    /** Makes the disk larger. */
    Volume grow(Volume volume, long newSizeBytes);

    /** Deletes a data disk at the orchestrator and leaves its record detached, for the caller to delete. */
    void remove(VirtualMachine vm, Volume volume);

    /** Moves the disk to other storage the instance's cluster reaches. The volume takes that storage's disk offering. */
    Volume move(Volume volume, StoragePool destination);

    /** Whether a disk offering is one the disk can change to: the offering of one External storage of its zone. */
    boolean offered(Volume volume, long diskOfferingId);

    /**
     * Moves the disk to the storage of the offering if it is not there, and makes it larger if a larger size is given.
     * Refused when the disk would have to move and moveAllowed is false.
     */
    Volume changeOffering(Volume volume, DiskOffering offering, Long newSizeBytes, boolean moveAllowed);
}
