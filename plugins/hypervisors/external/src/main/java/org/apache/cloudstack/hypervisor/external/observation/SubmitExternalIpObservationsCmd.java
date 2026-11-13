// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

import java.sql.SQLException;
import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;
import com.cloud.user.Account;

@APICommand(name = "submitExternalIpObservations", description = "Submit a scoped passive guest IPv4 capture",
        responseObject = SuccessResponse.class, authorized = {RoleType.Admin},
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = false, httpMethod = "POST", since = "4.23.0")
public class SubmitExternalIpObservationsCmd extends BaseCmd {
    @Inject private ExternalObservationService observationsService;
    @Parameter(name = "hostid", type = CommandType.UUID, entityType = HostResponse.class, required = true,
            description = "CloudStack host UUID mapped to the calling API user")
    private Long hostId;
    @Parameter(name = "captureid", type = CommandType.STRING, required = true, description = "Unique canonical UUID for this capture")
    private String captureId;
    @Parameter(name = "observations", type = CommandType.STRING, required = true, length = 60000, description = "Bounded JSON array of VMID, MAC, and IPv4 address arrays")
    private String observations;

    @Override public void execute() {
        CallContext context = CallContext.current();
        if (context == null || context.getCallingAccount().getType() != Account.Type.ADMIN) {
            throw new ServerApiException(ApiErrorCode.ACCOUNT_ERROR, "Root administrator required");
        }
        try {
            observationsService.submit(context.getCallingUserId(), hostId, captureId, observations);
        } catch (IllegalArgumentException | com.google.gson.JsonParseException e) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Invalid observation capture");
        } catch (SecurityException e) {
            throw new ServerApiException(ApiErrorCode.ACCOUNT_ERROR, "Observer scope rejected");
        } catch (IllegalStateException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Observation binding unavailable");
        } catch (SQLException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Observation storage unavailable");
        }
        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setSuccess(true);
        setResponseObject(response);
    }
    @Override public long getEntityOwnerId() { return Account.ACCOUNT_ID_SYSTEM; }
}
