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

import org.junit.Test;
import static org.junit.Assert.*;
import com.cloud.exception.InvalidParameterValueException;

public class ExternalDiscoveryManagerTest {
    private String snapshot(String rows) { return "{\"status\":\"success\",\"cluster\":\"lab\",\"instances\":["+rows+"]}"; }
    private String row(String id) { return "{\"vmid\":"+id+",\"node\":\"pve1\",\"type\":\"qemu\",\"name\":\"guest\",\"power\":\"running\",\"template\":false}"; }
    @Test public void returnsSourceIdentityAndValidEmptyInventory() {
        assertEquals(100L, ExternalDiscoveryManager.parse(snapshot(row("100")),"lab").get(0).getVmid());
        assertTrue(ExternalDiscoveryManager.parse(snapshot(""),"lab").isEmpty());
    }
    @Test public void reportsHaStateWhenGiven() {
        String ha=row("101").replace("}",",\"hastate\":\"started\"}");
        assertEquals("started", ExternalDiscoveryManager.parse(snapshot(ha),"lab").get(0).getHaState());
        assertNull(ExternalDiscoveryManager.parse(snapshot(row("100")),"lab").get(0).getHaState());
        String bad=row("101").replace("}",",\"hastate\":\"Started; x\"}");
        assertThrows(InvalidParameterValueException.class,()->ExternalDiscoveryManager.parse(snapshot(bad),"lab"));
    }
    @Test public void rejectsDuplicateAndWrongCluster() {
        assertThrows(InvalidParameterValueException.class,()->ExternalDiscoveryManager.parse(snapshot(row("100")+","+row("100")),"lab"));
        assertThrows(InvalidParameterValueException.class,()->ExternalDiscoveryManager.parse(snapshot(row("100")),"other"));
    }
    @Test public void rejectsMalformedAndCoercedIdentity() {
        for (String id:new String[]{"\"100\"","1.5","0","-1"}) {
            assertThrows(InvalidParameterValueException.class,()->ExternalDiscoveryManager.parse(snapshot(row(id)),"lab"));
        }
        assertThrows(InvalidParameterValueException.class,()->ExternalDiscoveryManager.parse("{\"status\":\"success\"}","lab"));
    }
}
