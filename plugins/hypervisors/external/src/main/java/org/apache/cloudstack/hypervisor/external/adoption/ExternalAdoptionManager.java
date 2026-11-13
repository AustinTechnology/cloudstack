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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import javax.inject.Inject;
import org.apache.cloudstack.context.CallContext;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.AccountService;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;

/** Root-admin entry points for the one-time legacy registry migration.
 * The operator must have stopped every legacy writer before import and keep
 * them stopped until handover; nothing here can fence the legacy service. */
public class ExternalAdoptionManager extends ManagerBase implements PluggableService {
    @Inject AccountService accountService;
    @Inject NativeManagedAdoptionVerifier verifier;

    @Override public List<Class<?>> getCommands() {
        return List.of(ImportExternalAdoptionRegistryCmd.class, HandoverExternalAdoptionRegistryCmd.class,
                ReleaseExternalAdoptionOperationLeaseCmd.class);
    }

    LegacyAdoptionImport importer() { return new LegacyAdoptionImport(); }

    boolean callerIsRootAdmin() {
        return accountService.isRootAdmin(CallContext.current().getCallingAccount().getId());
    }

    private void requireRootAdmin() {
        if (!callerIsRootAdmin()) {
            throw new PermissionDeniedException("External adoption migration requires root administrator access");
        }
    }

    /** Messages come from the importer and never contain export content. */
    public ExternalAdoptionRegistryResponse importExport(String exportJson, boolean dryRun) {
        requireRootAdmin();
        final LegacyAdoptionImport.Report report;
        try {
            report = importer().importExport(exportJson, dryRun);
        } catch (IllegalArgumentException | AdoptionRegistry.Conflict e) {
            throw new InvalidParameterValueException("Legacy adoption import rejected: " + e.getMessage());
        }
        return new ExternalAdoptionRegistryResponse(report.claims, report.leases, report.executions,
                report.authorities, dryRun, !dryRun);
    }

    /** Verifies every imported managed claim against the live instance, then clears the fence. */
    public ExternalAdoptionRegistryResponse handover() {
        requireRootAdmin();
        try {
            importer().handover(verifier);
        } catch (IllegalArgumentException | AdoptionRegistry.Conflict e) {
            throw new InvalidParameterValueException("Legacy adoption handover rejected: " + e.getMessage());
        }
        return state();
    }

    AdoptionRegistry registry() { return new AdoptionRegistry(); }

    /** Operator recovery of one retained lease after inspecting the guest; never replays the operation. */
    public ExternalAdoptionRegistryResponse releaseOperationLease(String claimId, Integer generation, String leaseId) {
        requireRootAdmin();
        try {
            registry().releaseUnknownOperation(claimId, generation == null ? 0 : generation, leaseId);
        } catch (IllegalArgumentException | AdoptionRegistry.Conflict e) {
            throw new InvalidParameterValueException("External adoption lease release rejected: " + e.getMessage());
        }
        return state();
    }

    ExternalAdoptionRegistryResponse state() {
        return AdoptionRegistry.transaction(c -> {
            int[] n = new int[4];
            String[] sql = {"SELECT COUNT(*) FROM external_adoption_claims",
                    "SELECT COUNT(*) FROM external_adoption_operation_leases",
                    "SELECT COUNT(*) FROM external_adoption_executions",
                    "SELECT COUNT(*) FROM external_adoption_write_authority"};
            for (int i = 0; i < sql.length; i++) {
                try (PreparedStatement s = c.prepareStatement(sql[i]); ResultSet r = s.executeQuery()) { r.next(); n[i] = r.getInt(1); }
            }
            boolean fenced;
            try (PreparedStatement s = c.prepareStatement("SELECT legacy_fenced FROM external_adoption_control WHERE id=1");
                 ResultSet r = s.executeQuery()) { fenced = !r.next() || r.getBoolean(1); }
            return new ExternalAdoptionRegistryResponse(n[0], n[1], n[2], n[3], false, fenced);
        });
    }
}
