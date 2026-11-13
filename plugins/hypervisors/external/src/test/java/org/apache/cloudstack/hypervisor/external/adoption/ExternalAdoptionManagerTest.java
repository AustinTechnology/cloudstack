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

import org.junit.Test;
import static org.junit.Assert.*;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;

public class ExternalAdoptionManagerTest {
    private static ExternalAdoptionManager manager(boolean root, RuntimeException failure) {
        return new ExternalAdoptionManager() {
            @Override boolean callerIsRootAdmin() { return root; }
            @Override LegacyAdoptionImport importer() {
                if (failure != null) throw failure;
                fail("importer must not be reached");
                return null;
            }
        };
    }
    @Test public void nonRootIsRejectedBeforeAnyRegistryAccess() {
        assertThrows(PermissionDeniedException.class, () -> manager(false, null).importExport("{}", false));
        assertThrows(PermissionDeniedException.class, () -> manager(false, null).handover());
    }
    @Test public void registryRejectionsBecomeParameterErrors() {
        assertThrows(InvalidParameterValueException.class,
                () -> manager(true, new AdoptionRegistry.Conflict("target registry is not empty")).importExport("{}", false));
        assertThrows(InvalidParameterValueException.class,
                () -> manager(true, new IllegalArgumentException("invalid export envelope")).handover());
    }
    @Test public void exposesAllCommands() {
        assertEquals(3, new ExternalAdoptionManager().getCommands().size());
    }
    @Test public void leaseReleaseIsRootOnlyAndRejectsMalformedIdentityBeforeTheDatabase() {
        String id = "f8e69503-33a5-4093-a336-7c11116b4cc3";
        assertThrows(PermissionDeniedException.class, () -> manager(false, null).releaseOperationLease(id, 1, id));
        assertThrows(InvalidParameterValueException.class, () -> manager(true, null).releaseOperationLease("not-a-uuid", 1, id));
        assertThrows(InvalidParameterValueException.class, () -> manager(true, null).releaseOperationLease(id, 1, id.toUpperCase()));
        assertThrows(InvalidParameterValueException.class, () -> manager(true, null).releaseOperationLease(id, null, id));
    }
}
