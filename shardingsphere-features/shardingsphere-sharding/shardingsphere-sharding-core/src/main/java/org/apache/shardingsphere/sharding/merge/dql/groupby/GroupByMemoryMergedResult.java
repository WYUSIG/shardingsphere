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

package org.apache.shardingsphere.sharding.merge.dql.groupby;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.shardingsphere.infra.executor.sql.execute.result.query.QueryResult;
import org.apache.shardingsphere.infra.merge.result.MergedResult;
import org.apache.shardingsphere.infra.merge.result.impl.memory.MemoryMergedResult;
import org.apache.shardingsphere.infra.merge.result.impl.memory.MemoryQueryResultRow;
import org.apache.shardingsphere.sharding.merge.dql.groupby.aggregation.AggregationUnit;
import org.apache.shardingsphere.sharding.merge.dql.groupby.aggregation.AggregationUnitFactory;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.apache.shardingsphere.infra.metadata.schema.model.ColumnMetaData;
import org.apache.shardingsphere.infra.metadata.schema.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.schema.model.TableMetaData;
import org.apache.shardingsphere.infra.binder.segment.select.projection.Projection;
import org.apache.shardingsphere.infra.binder.segment.select.projection.impl.AggregationDistinctProjection;
import org.apache.shardingsphere.infra.binder.segment.select.projection.impl.AggregationProjection;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.statement.dml.SelectStatementContext;
import org.apache.shardingsphere.sql.parser.sql.common.constant.AggregationType;
import org.apache.shardingsphere.sql.parser.sql.common.segment.generic.table.SimpleTableSegment;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Memory merged result for group by.
 */
public final class GroupByMemoryMergedResult extends MemoryMergedResult<ShardingRule> {
    
    public GroupByMemoryMergedResult(final List<QueryResult> queryResults, final SelectStatementContext selectStatementContext, final ShardingSphereSchema schema) throws SQLException {
        super(null, schema, selectStatementContext, queryResults);
    }
    
    @Override
    protected List<MemoryQueryResultRow> init(final ShardingRule shardingRule, final ShardingSphereSchema schema, 
                                              final SQLStatementContext sqlStatementContext, final List<QueryResult> queryResults, final MergedResult mergedResult) throws SQLException {
        SelectStatementContext selectStatementContext = (SelectStatementContext) sqlStatementContext;
        //一组group by数据 -> 一行数据
        Map<GroupByValue, MemoryQueryResultRow> dataMap = new HashMap<>(1024);
        //一组group by数据 -> 该组的聚合函数
        Map<GroupByValue, Map<AggregationProjection, AggregationUnit>> aggregationMap = new HashMap<>(1024);
        for (QueryResult each : queryResults) {
            while (each.next()) {
                //每一行数据的group by字段的值
                GroupByValue groupByValue = new GroupByValue(each, selectStatementContext.getGroupByContext().getItems());
                //把数据存储到dataMap、aggregationMap
                initForFirstGroupByValue(selectStatementContext, each, groupByValue, dataMap, aggregationMap);
                //聚合函数数值归并
                aggregate(selectStatementContext, each, groupByValue, aggregationMap);
            }
        }
        //遍历每一组数据，把归并好的集合函数数值放到MemoryQueryResultRow
        setAggregationValueToMemoryRow(selectStatementContext, dataMap, aggregationMap);
        //判断是否区分大小写
        List<Boolean> valueCaseSensitive = queryResults.isEmpty() ? Collections.emptyList() : getValueCaseSensitive(queryResults.iterator().next(), selectStatementContext, schema);
        //返回排好序的归并好的每一组数据
        return getMemoryResultSetRows(selectStatementContext, dataMap, valueCaseSensitive);
    }
    
    private void initForFirstGroupByValue(final SelectStatementContext selectStatementContext, final QueryResult queryResult,
                                          final GroupByValue groupByValue, final Map<GroupByValue, MemoryQueryResultRow> dataMap,
                                          final Map<GroupByValue, Map<AggregationProjection, AggregationUnit>> aggregationMap) throws SQLException {
        if (!dataMap.containsKey(groupByValue)) {
            //如果group by的值作为key，还没有存到dataMap，则put进去
            dataMap.put(groupByValue, new MemoryQueryResultRow(queryResult));
        }
        if (!aggregationMap.containsKey(groupByValue)) {
            //拿到所有聚和函数（min、max、avg、sum、count）
            Map<AggregationProjection, AggregationUnit> map = Maps.toMap(selectStatementContext.getProjectionsContext().getAggregationProjections(), 
                input -> AggregationUnitFactory.create(input.getType(), input instanceof AggregationDistinctProjection));
            //aggregationMap存进去
            aggregationMap.put(groupByValue, map);
        }
    }
    
    private void aggregate(final SelectStatementContext selectStatementContext, final QueryResult queryResult,
                           final GroupByValue groupByValue, final Map<GroupByValue, Map<AggregationProjection, AggregationUnit>> aggregationMap) throws SQLException {
        for (AggregationProjection each : selectStatementContext.getProjectionsContext().getAggregationProjections()) {
            //聚合函数sql改写新增的两个聚合函数
            List<Comparable<?>> values = new ArrayList<>(2);
            if (each.getDerivedAggregationProjections().isEmpty()) {
                //如果没有改写sql，则value直接加上该聚合函数值
                values.add(getAggregationValue(queryResult, each));
            } else {
                for (AggregationProjection derived : each.getDerivedAggregationProjections()) {
                    //否则加上改写新增的两个聚合函数值
                    values.add(getAggregationValue(queryResult, derived));
                }
            }
            //聚合函数数值归并
            aggregationMap.get(groupByValue).get(each).merge(values);
        }
    }
    
    private Comparable<?> getAggregationValue(final QueryResult queryResult, final AggregationProjection aggregationProjection) throws SQLException {
        Object result = queryResult.getValue(aggregationProjection.getIndex(), Object.class);
        Preconditions.checkState(null == result || result instanceof Comparable, "Aggregation value must implements Comparable");
        return (Comparable<?>) result;
    }
    
    private void setAggregationValueToMemoryRow(final SelectStatementContext selectStatementContext, 
                                                final Map<GroupByValue, MemoryQueryResultRow> dataMap, final Map<GroupByValue, Map<AggregationProjection, AggregationUnit>> aggregationMap) {
        //遍历每一组数据，把归并好的集合函数数值放到MemoryQueryResultRow
        for (Entry<GroupByValue, MemoryQueryResultRow> entry : dataMap.entrySet()) {
            for (AggregationProjection each : selectStatementContext.getProjectionsContext().getAggregationProjections()) {
                entry.getValue().setCell(each.getIndex(), aggregationMap.get(entry.getKey()).get(each).getResult());
            }
        }
    }
    
    private List<Boolean> getValueCaseSensitive(final QueryResult queryResult, final SelectStatementContext selectStatementContext, final ShardingSphereSchema schema) throws SQLException {
        List<Boolean> result = Lists.newArrayList(false);
        for (int columnIndex = 1; columnIndex <= queryResult.getMetaData().getColumnCount(); columnIndex++) {
            result.add(getValueCaseSensitiveFromTables(queryResult, selectStatementContext, schema, columnIndex));
        }
        return result;
    }
    
    private boolean getValueCaseSensitiveFromTables(final QueryResult queryResult, 
                                                    final SelectStatementContext selectStatementContext, final ShardingSphereSchema schema, final int columnIndex) throws SQLException {
        for (SimpleTableSegment each : selectStatementContext.getAllSimpleTableSegments()) {
            String tableName = each.getTableName().getIdentifier().getValue();
            TableMetaData tableMetaData = schema.get(tableName);
            Map<String, ColumnMetaData> columns = tableMetaData.getColumns();
            String columnName = queryResult.getMetaData().getColumnName(columnIndex);
            if (columns.containsKey(columnName)) {
                return columns.get(columnName).isCaseSensitive();
            }
        }
        return false;
    }
    
    private List<MemoryQueryResultRow> getMemoryResultSetRows(final SelectStatementContext selectStatementContext,
                                                              final Map<GroupByValue, MemoryQueryResultRow> dataMap, final List<Boolean> valueCaseSensitive) {
        if (dataMap.isEmpty()) {
            Object[] data = generateReturnData(selectStatementContext);
            return Collections.singletonList(new MemoryQueryResultRow(data));
        }
        //每一组数据
        List<MemoryQueryResultRow> result = new ArrayList<>(dataMap.values());
        //排序，有order by按order by排序，没有按group by字段排序，valueCaseSensitive为是否区分大小写
        result.sort(new GroupByRowComparator(selectStatementContext, valueCaseSensitive));
        return result;
    }
    
    private Object[] generateReturnData(final SelectStatementContext selectStatementContext) {
        List<Projection> projections = new LinkedList<>(selectStatementContext.getProjectionsContext().getProjections());
        Object[] result = new Object[projections.size()];
        for (int i = 0; i < projections.size(); i++) {
            if (projections.get(i) instanceof AggregationProjection && AggregationType.COUNT == ((AggregationProjection) projections.get(i)).getType()) {
                result[i] = 0;
            }
        }
        return result;
    }
}
