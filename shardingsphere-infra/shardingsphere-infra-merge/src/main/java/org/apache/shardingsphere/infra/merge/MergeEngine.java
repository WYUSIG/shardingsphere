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

package org.apache.shardingsphere.infra.merge;

import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.executor.sql.execute.result.query.QueryResult;
import org.apache.shardingsphere.infra.merge.engine.ResultProcessEngine;
import org.apache.shardingsphere.infra.merge.engine.decorator.ResultDecorator;
import org.apache.shardingsphere.infra.merge.engine.decorator.ResultDecoratorEngine;
import org.apache.shardingsphere.infra.merge.engine.merger.ResultMerger;
import org.apache.shardingsphere.infra.merge.engine.merger.ResultMergerEngine;
import org.apache.shardingsphere.infra.merge.result.MergedResult;
import org.apache.shardingsphere.infra.merge.result.impl.transparent.TransparentMergedResult;
import org.apache.shardingsphere.infra.metadata.schema.ShardingSphereSchema;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.infra.spi.ordered.OrderedSPIRegistry;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Merge engine.
 */
public final class MergeEngine {
    
    static {
        //SPI 加载ResultProcessEngine类
        ShardingSphereServiceLoader.register(ResultProcessEngine.class);
    }
    
    private final DatabaseType databaseType;
    
    private final ShardingSphereSchema schema;
    
    private final ConfigurationProperties props;
    
    @SuppressWarnings("rawtypes")
    private final Map<ShardingSphereRule, ResultProcessEngine> engines;
    
    public MergeEngine(final DatabaseType databaseType, final ShardingSphereSchema schema, final ConfigurationProperties props, final Collection<ShardingSphereRule> rules) {
        this.databaseType = databaseType;
        this.schema = schema;
        this.props = props;
        //所有SPI ResultProcessEngine实现类
        engines = OrderedSPIRegistry.getRegisteredServices(rules, ResultProcessEngine.class);
    }
    
    /**
     * Merge.
     *
     * @param queryResults query results
     * @param sqlStatementContext SQL statement context
     * @return merged result
     * @throws SQLException SQL exception
     */
    public MergedResult merge(final List<QueryResult> queryResults, final SQLStatementContext<?> sqlStatementContext) throws SQLException {
        //归并后的结果
        Optional<MergedResult> mergedResult = executeMerge(queryResults, sqlStatementContext);
        //如果归并后的结果不为空，则调用decorate选择进一步装饰，否则直接取执行结果集的第一位进行进一步装饰
        Optional<MergedResult> result = mergedResult.isPresent() ? Optional.of(decorate(mergedResult.get(), sqlStatementContext)) : decorate(queryResults.get(0), sqlStatementContext);
        //如果装饰后的结果为空，则直接取执行结果集的第一位
        return result.orElseGet(() -> new TransparentMergedResult(queryResults.get(0)));
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<MergedResult> executeMerge(final List<QueryResult> queryResults, final SQLStatementContext<?> sqlStatementContext) throws SQLException {
        //遍历SPI注册的所有结果处理引擎ResultProcessEngine
        for (Entry<ShardingSphereRule, ResultProcessEngine> entry : engines.entrySet()) {
            //如果是结果归并引擎则执行
            if (entry.getValue() instanceof ResultMergerEngine) {
                //结果处理引擎创建ResultMerger实例，会根据sqlStatementContext类型创建不同的ResultMerger子类对象
                ResultMerger resultMerger = ((ResultMergerEngine) entry.getValue()).newInstance(databaseType, entry.getKey(), props, sqlStatementContext);
                //调用ResultMerger的merge方法
                return Optional.of(resultMerger.merge(queryResults, sqlStatementContext, schema));
            }
        }
        return Optional.empty();
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private MergedResult decorate(final MergedResult mergedResult, final SQLStatementContext<?> sqlStatementContext) throws SQLException {
        MergedResult result = null;
        for (Entry<ShardingSphereRule, ResultProcessEngine> entry : engines.entrySet()) {
            if (entry.getValue() instanceof ResultDecoratorEngine) {
                ResultDecorator resultDecorator = ((ResultDecoratorEngine) entry.getValue()).newInstance(databaseType, schema, entry.getKey(), props, sqlStatementContext);
                result = null == result ? resultDecorator.decorate(mergedResult, sqlStatementContext, entry.getKey()) : resultDecorator.decorate(result, sqlStatementContext, entry.getKey());
            }
        }
        return null == result ? mergedResult : result;
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<MergedResult> decorate(final QueryResult queryResult, final SQLStatementContext<?> sqlStatementContext) throws SQLException {
        MergedResult result = null;
        for (Entry<ShardingSphereRule, ResultProcessEngine> entry : engines.entrySet()) {
            if (entry.getValue() instanceof ResultDecoratorEngine) {
                ResultDecorator resultDecorator = ((ResultDecoratorEngine) entry.getValue()).newInstance(databaseType, schema, entry.getKey(), props, sqlStatementContext);
                result = null == result ? resultDecorator.decorate(queryResult, sqlStatementContext, entry.getKey()) : resultDecorator.decorate(result, sqlStatementContext, entry.getKey());
            }
        }
        return Optional.ofNullable(result);
    }
}
