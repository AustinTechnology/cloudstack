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
package org.apache.cloudstack.hypervisor.external.storage;

import java.util.function.Supplier;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.GlobalLock;

/**
 * One disk change or refresh of an External instance at a time, across management servers. A disk change is
 * made at the orchestrator first and recorded after, so anything that reads the instance in between would see
 * the disk without its record.
 */
public class ExternalInstanceLock {
    public <T> T hold(long vmId, int waitSeconds, Supplier<T> work) {
        GlobalLock lock = GlobalLock.getInternLock("external.instance." + vmId);
        try {
            // Never 0: a lock held by another thread of this server would then be waited for without end.
            if (!lock.lock(Math.max(1, waitSeconds))) {
                throw new InvalidParameterValueException("A disk change or a refresh of the instance is in progress; try again when it has finished");
            }
            try {
                return work.get();
            } finally {
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }
}
