/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.transaction.context;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.transaction.ShardingTransactionManagerEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * Transaction contexts.
 */
@RequiredArgsConstructor
@Getter
public final class TransactionContexts implements AutoCloseable {
    
    private final Map<String, ShardingTransactionManagerEngine> engines;
    
    public TransactionContexts() {
        this(new HashMap<>());
    }
    
    @Override
    public void close() throws Exception {
        for (ShardingTransactionManagerEngine each : engines.values()) {
            each.close();
        }
    }
}
