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
package com.cloud.agent.transport;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RequestSecretMaskTest {

    @Test
    public void credentialValuesKeepOnlyTheirEnds() {
        String logged = "{\"externalDetails\":{\"resourcemap\":{\"user\":\"cloudstack-ext@pve\",\"secret\":\"0308418c-b2d2-4fcb-8477-3254440e1166\",\"token\":\"acs\"},"
                + "\"host\":{\"url\":\"192.0.2.10\",\"secretKey\":\"abcdefghij\",\"api_key\":\"short\",\"Private_Key\":\"a\\\"quoted\\\\value-0123456789\"}}}";
        assertEquals("{\"externalDetails\":{\"resourcemap\":{\"user\":\"cloudstack-ext@pve\",\"secret\":\"0308***1166\",\"token\":\"acs\"},"
                + "\"host\":{\"url\":\"192.0.2.10\",\"secretKey\":\"ab***ij\",\"api_key\":\"***\",\"Private_Key\":\"a\\\"q***6789\"}}}", Request.maskSecrets(logged));
    }

    @Test
    public void otherValuesAndEmptyCredentialsAreLeftAlone() {
        String logged = "{\"name\":\"i-2-4-VM\",\"secretless\":\"\",\"ceph_keyring\":\"/etc/ceph/k.keyring\",\"details\":{\"cpuNumber\":\"1\"}}";
        assertEquals("{\"name\":\"i-2-4-VM\",\"secretless\":\"***\",\"ceph_keyring\":\"/etc/ceph/k.keyring\",\"details\":{\"cpuNumber\":\"1\"}}", Request.maskSecrets(logged));
        assertEquals(null, Request.maskSecrets(null));
    }
}
