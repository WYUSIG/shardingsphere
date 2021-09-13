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

package org.apache.shardingsphere.sharding.route.engine.condition.engine.impl;

import com.google.common.base.Preconditions;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.infra.exception.ShardingSphereException;
import org.apache.shardingsphere.infra.spi.required.RequiredSPIRegistry;
import org.apache.shardingsphere.infra.datetime.DatetimeService;
import org.apache.shardingsphere.sharding.route.engine.condition.ExpressionConditionUtils;
import org.apache.shardingsphere.sharding.route.engine.condition.ShardingCondition;
import org.apache.shardingsphere.sharding.route.engine.condition.engine.ShardingConditionEngine;
import org.apache.shardingsphere.sharding.route.engine.condition.value.ListShardingConditionValue;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.apache.shardingsphere.infra.metadata.schema.ShardingSphereSchema;
import org.apache.shardingsphere.infra.binder.segment.insert.keygen.GeneratedKeyContext;
import org.apache.shardingsphere.infra.binder.segment.insert.values.InsertValueContext;
import org.apache.shardingsphere.infra.binder.statement.dml.InsertStatementContext;
import org.apache.shardingsphere.infra.binder.statement.dml.SelectStatementContext;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.expr.ExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.expr.simple.LiteralExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.expr.simple.ParameterMarkerExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.expr.simple.SimpleExpressionSegment;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Sharding condition engine for insert clause.
 */
@RequiredArgsConstructor
public final class InsertClauseShardingConditionEngine implements ShardingConditionEngine<InsertStatementContext> {
    
    private final ShardingRule shardingRule;
    
    private final ShardingSphereSchema schema;
    
    @Override
    public List<ShardingCondition> createShardingConditions(final InsertStatementContext sqlStatementContext, final List<Object> parameters) {
        List<ShardingCondition> result = null == sqlStatementContext.getInsertSelectContext()
                ? createShardingConditionsWithInsertValues(sqlStatementContext, parameters) : createShardingConditionsWithInsertSelect(sqlStatementContext, parameters);
        //自动生成主键是否是分片
        appendGeneratedKeyConditions(sqlStatementContext, result);
        return result;
    }

    /**
     * 单纯insert没有select语句的创建分片条件ShardingCondition
     * @param sqlStatementContext sql上下文
     * @param parameters 参数
     * @return ShardingCondition列表
     */
    private List<ShardingCondition> createShardingConditionsWithInsertValues(final InsertStatementContext sqlStatementContext, final List<Object> parameters) {
        List<ShardingCondition> result = new LinkedList<>();
        //获取sql表名
        String tableName = sqlStatementContext.getSqlStatement().getTable().getTableName().getIdentifier().getValue();
        //获取sql插入的全部列名
        Collection<String> columnNames = getColumnNames(sqlStatementContext);
        //遍历插入行集合，insert语句可以插入多行(),()
        for (InsertValueContext each : sqlStatementContext.getInsertValueContexts()) {
            //把表名、列名、参数传递下去，创建ShardingCondition，并添加到result中
            result.add(createShardingCondition(tableName, columnNames.iterator(), each, parameters));
        }
        return result;
    }

    /**
     * 获取insert语句中插入的列名
     * @param insertStatementContext insert sql 上下文
     * @return 列名集合
     */
    private Collection<String> getColumnNames(final InsertStatementContext insertStatementContext) {
        //判断是否有自动生成主键，有的话，列名集合去掉该列
        Optional<GeneratedKeyContext> generatedKey = insertStatementContext.getGeneratedKeyContext();
        if (generatedKey.isPresent() && generatedKey.get().isGenerated()) {
            Collection<String> result = new LinkedList<>(insertStatementContext.getColumnNames());
            result.remove(generatedKey.get().getColumnName());
            return result;
        }
        //没有自动生成主键，直接获取sql插入的全部列名
        return insertStatementContext.getColumnNames();
    }
    
    private ShardingCondition createShardingCondition(final String tableName, final Iterator<String> columnNames, final InsertValueContext insertValueContext, final List<Object> parameters) {
        ShardingCondition result = new ShardingCondition();
        //ShardingSphere自己的时间服务
        DatetimeService datetimeService = RequiredSPIRegistry.getRegisteredService(DatetimeService.class);
        //遍历每一项插入的value
        for (ExpressionSegment each : insertValueContext.getValueExpressions()) {
            //对应插入的字段名
            String columnName = columnNames.next();
            //如果该字段被配置用来分片
            if (shardingRule.isShardingColumn(columnName, tableName)) {
                if (each instanceof SimpleExpressionSegment) {
                    //拿到这个字段的参数值，添加到result
                    result.getValues().add(new ListShardingConditionValue<>(columnName, tableName, Collections.singletonList(getShardingValue((SimpleExpressionSegment) each, parameters))));
                } else if (ExpressionConditionUtils.isNowExpression(each)) {
                    //如果是now()，添加的是DatetimeService的时间
                    result.getValues().add(new ListShardingConditionValue<>(columnName, tableName, Collections.singletonList(datetimeService.getDatetime())));
                } else if (ExpressionConditionUtils.isNullExpression(each)) {
                    //分片键值不能为null
                    throw new ShardingSphereException("Insert clause sharding column can't be null.");
                }
            }
        }
        return result;
    }

    /**
     * 获取
     * @param expressionSegment
     * @param parameters
     * @return
     */
    private Comparable<?> getShardingValue(final SimpleExpressionSegment expressionSegment, final List<Object> parameters) {
        Object result;
        if (expressionSegment instanceof ParameterMarkerExpressionSegment) {
            result = parameters.get(((ParameterMarkerExpressionSegment) expressionSegment).getParameterMarkerIndex());
        } else {
            result = ((LiteralExpressionSegment) expressionSegment).getLiterals();
        }
        Preconditions.checkArgument(result instanceof Comparable, "Sharding value must implements Comparable.");
        return (Comparable) result;
    }
    
    private List<ShardingCondition> createShardingConditionsWithInsertSelect(final InsertStatementContext sqlStatementContext, final List<Object> parameters) {
        //获取insert中的select sql上下文
        SelectStatementContext selectStatementContext = sqlStatementContext.getInsertSelectContext().getSelectStatementContext();
        //创建WhereClauseShardingConditionEngine，调用createShardingConditions方法，
        return new LinkedList<>(new WhereClauseShardingConditionEngine(shardingRule, schema).createShardingConditions(selectStatementContext, parameters));
    }
    
    private void appendGeneratedKeyConditions(final InsertStatementContext sqlStatementContext, final List<ShardingCondition> shardingConditions) {
        //自动生成主键
        Optional<GeneratedKeyContext> generatedKey = sqlStatementContext.getGeneratedKeyContext();
        //表名
        String tableName = sqlStatementContext.getSqlStatement().getTable().getTableName().getIdentifier().getValue();
        //如果有自动生成主键，已经生成、且是分片键
        if (generatedKey.isPresent() && generatedKey.get().isGenerated()) {
            generatedKey.get().getGeneratedValues().addAll(generateKeys(tableName, sqlStatementContext.getValueListCount()));
            if (shardingRule.isShardingColumn(generatedKey.get().getColumnName(), tableName)) {
                appendGeneratedKeyCondition(generatedKey.get(), tableName, shardingConditions);
            }
        }
    }
    
    private Collection<Comparable<?>> generateKeys(final String tableName, final int valueListCount) {
        return IntStream.range(0, valueListCount).mapToObj(i -> shardingRule.generateKey(tableName)).collect(Collectors.toList());
    }
    
    private void appendGeneratedKeyCondition(final GeneratedKeyContext generatedKey, final String tableName, final List<ShardingCondition> shardingConditions) {
        Iterator<Comparable<?>> generatedValuesIterator = generatedKey.getGeneratedValues().iterator();
        for (ShardingCondition each : shardingConditions) {
            each.getValues().add(new ListShardingConditionValue<>(generatedKey.getColumnName(), tableName, Collections.<Comparable<?>>singletonList(generatedValuesIterator.next())));
        }
    }
}
