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

package org.apache.shardingsphere.readwritesplitting.route.impl;

import com.google.common.base.Strings;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.infra.aware.DataSourceNameAware;
import org.apache.shardingsphere.infra.aware.DataSourceNameAwareFactory;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.apache.shardingsphere.infra.transaction.TransactionHolder;
import org.apache.shardingsphere.readwritesplitting.rule.ReadwriteSplittingDataSourceRule;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.SelectStatement;
import org.apache.shardingsphere.sql.parser.sql.dialect.handler.dml.SelectStatementHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;

/**
 * Data source router for readwrite-splitting.
 */
@RequiredArgsConstructor
public final class ReadwriteSplittingDataSourceRouter {
    
    private final ReadwriteSplittingDataSourceRule rule;
    
    /**
     * Route.
     * 
     * @param sqlStatement SQL statement
     * @return data source name
     */
    public String route(final SQLStatement sqlStatement) {
        if (isPrimaryRoute(sqlStatement)) {
            //判断使用主库
            //PrimaryVisitedManager标记当前线程使用主库
            PrimaryVisitedManager.setPrimaryVisited();
            //首先尝试获取配置的autoAwareDataSourceName（autoAwareDataSource可以认为是一个回调，在回调实现里面可以根据PrimaryVisitedManager标志自由返回这次命中什么库）
            String autoAwareDataSourceName = rule.getAutoAwareDataSourceName();
            if (Strings.isNullOrEmpty(autoAwareDataSourceName)) {
                //autoAwareDataSource获取不到就直接返会配置的writeDataSource
                return rule.getWriteDataSourceName();
            }
            //如果autoAwareDataSource有值，则加载spi DataSourceNameAware
            Optional<DataSourceNameAware> dataSourceNameAware = DataSourceNameAwareFactory.getInstance().getDataSourceNameAware();
            if (dataSourceNameAware.isPresent()) {
                //回调DataSourceNameAware, 返回自定义的主库
                return dataSourceNameAware.get().getPrimaryDataSourceName(autoAwareDataSourceName);
            }
        }
        //如果不需要访问主库
        //首先尝试获取配置的autoAwareDataSourceName
        String autoAwareDataSourceName = rule.getAutoAwareDataSourceName();
        if (Strings.isNullOrEmpty(autoAwareDataSourceName)) {
            //autoAwareDataSourceName获取不到, 直接负载均衡一个从库
            return rule.getLoadBalancer().getDataSource(rule.getName(), rule.getWriteDataSourceName(), rule.getReadDataSourceNames());
        }
        //如果autoAwareDataSource有值，则加载spi DataSourceNameAware
        Optional<DataSourceNameAware> dataSourceNameAware = DataSourceNameAwareFactory.getInstance().getDataSourceNameAware();
        if (dataSourceNameAware.isPresent()) {
            //回调DataSourceNameAware, 返回自定义的所有从库
            Collection<String> replicaDataSourceNames = dataSourceNameAware.get().getReplicaDataSourceNames(autoAwareDataSourceName);
            return rule.getLoadBalancer().getDataSource(rule.getName(), rule.getWriteDataSourceName(), new ArrayList<>(replicaDataSourceNames));
        }
        return rule.getLoadBalancer().getDataSource(rule.getName(), rule.getWriteDataSourceName(), rule.getReadDataSourceNames());
    }

    /**
     * 判断是否主库(写库)路由
     * @param sqlStatement 解析出来的sql
     * @return true:主库，false：从库
     */
    private boolean isPrimaryRoute(final SQLStatement sqlStatement) {
        //如果sql有锁操作 或者 非select语句 或者 PrimaryVisitedManager已经标记访问主库 或者 HintManager已经标记只写 或者 是个事务
        return containsLockSegment(sqlStatement) || !(sqlStatement instanceof SelectStatement)
                || PrimaryVisitedManager.getPrimaryVisited() || HintManager.isWriteRouteOnly() || TransactionHolder.isTransaction();
    }
    
    private boolean containsLockSegment(final SQLStatement sqlStatement) {
        return sqlStatement instanceof SelectStatement && SelectStatementHandler.getLockSegment((SelectStatement) sqlStatement).isPresent();
    }
}
