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

package org.apache.shardingsphere.infra.executor.sql.prepare;

import com.google.common.collect.Lists;
import org.apache.shardingsphere.infra.executor.kernel.model.ExecutionGroup;
import org.apache.shardingsphere.infra.executor.kernel.model.ExecutionGroupContext;
import org.apache.shardingsphere.infra.executor.sql.context.ExecutionUnit;
import org.apache.shardingsphere.infra.executor.sql.context.SQLUnit;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.infra.spi.ordered.OrderedSPIRegistry;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Abstract execution prepare engine.
 * 
 * @param <T> type of input value
 */
public abstract class AbstractExecutionPrepareEngine<T> implements ExecutionPrepareEngine<T> {
    
    static {
        ShardingSphereServiceLoader.register(ExecutionPrepareDecorator.class);
    }
    
    private final int maxConnectionsSizePerQuery;
    
    @SuppressWarnings("rawtypes")
    private final Map<ShardingSphereRule, ExecutionPrepareDecorator> decorators;
    
    protected AbstractExecutionPrepareEngine(final int maxConnectionsSizePerQuery, final Collection<ShardingSphereRule> rules) {
        this.maxConnectionsSizePerQuery = maxConnectionsSizePerQuery;
        decorators = OrderedSPIRegistry.getRegisteredServices(rules, ExecutionPrepareDecorator.class);
    }
    
    @Override
    public final ExecutionGroupContext<T> prepare(final RouteContext routeContext, final Collection<ExecutionUnit> executionUnits) throws SQLException {
        Collection<ExecutionGroup<T>> result = new LinkedList<>();
        //aggregateSQLUnitGroups方法是把执行单元按是否是同一数据源分组，然后遍历每个组
        for (Entry<String, List<SQLUnit>> entry : aggregateSQLUnitGroups(executionUnits).entrySet()) {
            //数据源名称
            String dataSourceName = entry.getKey();
            //sql执行单元
            List<SQLUnit> sqlUnits = entry.getValue();
            //按照最大连接数分成一份一份的
            List<List<SQLUnit>> sqlUnitGroups = group(sqlUnits);
            ////自动选择内存限制模式还是连接限制模式![连接模式计算公式](https://shardingsphere.apache.org/document/current/img/sharding/connection_mode_cn.png)
            ConnectionMode connectionMode = maxConnectionsSizePerQuery < sqlUnits.size() ? ConnectionMode.CONNECTION_STRICTLY : ConnectionMode.MEMORY_STRICTLY;

            result.addAll(group(dataSourceName, sqlUnitGroups, connectionMode));
        }
        return decorate(routeContext, result);
    }
    
    private List<List<SQLUnit>> group(final List<SQLUnit> sqlUnits) {
        //按照最大连接数分成一份一份的
        int desiredPartitionSize = Math.max(0 == sqlUnits.size() % maxConnectionsSizePerQuery ? sqlUnits.size() / maxConnectionsSizePerQuery : sqlUnits.size() / maxConnectionsSizePerQuery + 1, 1);
        return Lists.partition(sqlUnits, desiredPartitionSize);
    }
    
    protected abstract List<ExecutionGroup<T>> group(String dataSourceName, List<List<SQLUnit>> sqlUnitGroups, ConnectionMode connectionMode) throws SQLException;
    
    private Map<String, List<SQLUnit>> aggregateSQLUnitGroups(final Collection<ExecutionUnit> executionUnits) {
        Map<String, List<SQLUnit>> result = new LinkedHashMap<>(executionUnits.size(), 1);
        for (ExecutionUnit each : executionUnits) {
            if (!result.containsKey(each.getDataSourceName())) {
                result.put(each.getDataSourceName(), new LinkedList<>());
            }
            result.get(each.getDataSourceName()).add(each.getSqlUnit());
        }
        return result;
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ExecutionGroupContext decorate(final RouteContext routeContext, final Collection<ExecutionGroup<T>> executionGroups) {
        Collection<ExecutionGroup<T>> result = executionGroups;
        for (Entry<ShardingSphereRule, ExecutionPrepareDecorator> each : decorators.entrySet()) {
            result = each.getValue().decorate(routeContext, each.getKey(), result);
        }
        return new ExecutionGroupContext(result);
    }
}
