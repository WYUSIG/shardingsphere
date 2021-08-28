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

package org.apache.shardingsphere.driver.jdbc.core.datasource;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.driver.jdbc.core.connection.ShardingSphereConnection;
import org.apache.shardingsphere.driver.jdbc.unsupported.AbstractUnsupportedOperationDataSource;
import org.apache.shardingsphere.infra.config.RuleConfiguration;
import org.apache.shardingsphere.infra.config.properties.ConfigurationPropertyKey;
import org.apache.shardingsphere.infra.context.metadata.MetaDataContexts;
import org.apache.shardingsphere.infra.context.metadata.MetaDataContextsBuilder;
import org.apache.shardingsphere.infra.database.DefaultSchema;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.transaction.ShardingTransactionManagerEngine;
import org.apache.shardingsphere.transaction.context.TransactionContexts;
import org.apache.shardingsphere.transaction.context.impl.StandardTransactionContexts;
import org.apache.shardingsphere.transaction.core.TransactionTypeHolder;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

/**
 * ShardingSphere data source.
 */
@RequiredArgsConstructor
@Getter
public final class ShardingSphereDataSource extends AbstractUnsupportedOperationDataSource implements AutoCloseable {

    /**
     * 元信息上下文，记录了DataSources、Rules、props
     */
    private final MetaDataContexts metaDataContexts;

    /**
     * 事务上下文
     */
    private final TransactionContexts transactionContexts;
    
    public ShardingSphereDataSource(final Map<String, DataSource> dataSourceMap, final Collection<RuleConfiguration> configurations, final Properties props) throws SQLException {
        //使用DataSources、Rules、props构造出元信息上下文
        metaDataContexts = new MetaDataContextsBuilder(
                Collections.singletonMap(DefaultSchema.LOGIC_NAME, dataSourceMap), Collections.singletonMap(DefaultSchema.LOGIC_NAME, configurations), props).build();
        //从元信息上下文获取xa分布式事务类型(有Atomikos、Narayana、Bitronix)
        String xaTransactionMangerType = metaDataContexts.getProps().getValue(ConfigurationPropertyKey.XA_TRANSACTION_MANAGER_TYPE);
        //构造出事务上下文
        transactionContexts = createTransactionContexts(metaDataContexts.getDefaultMetaData().getResource().getDatabaseType(), dataSourceMap, xaTransactionMangerType);
    }

    /**
     * 构造出事务上下文
     * @param databaseType 数据库类型
     * @param dataSourceMap 所有数据库
     * @param xaTransactionMangerType xa分布式事务类型，有Atomikos、Narayana、Bitronix
     * @return 事务上下文
     */
    private TransactionContexts createTransactionContexts(final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap, final String xaTransactionMangerType) {
        ShardingTransactionManagerEngine engine = new ShardingTransactionManagerEngine();
        engine.init(databaseType, dataSourceMap, xaTransactionMangerType);
        return new StandardTransactionContexts(Collections.singletonMap(DefaultSchema.LOGIC_NAME, engine));
    }

    /**
     * 实现jdbc的getConnection方法
     * @return 返回一个ShardingSphereConnection
     * 我们可以看到，把所有数据库、元信息上下文、事务上下文等继续传到下一层Connection
     */
    @Override
    public ShardingSphereConnection getConnection() {
        return new ShardingSphereConnection(getDataSourceMap(), metaDataContexts, transactionContexts, TransactionTypeHolder.get());
    }

    /**
     * 实现jdbc的getConnection方法
     * @param username 没有用到
     * @param password 没有用到
     * @return 调用上面getConnection无参方法
     */
    @Override
    public ShardingSphereConnection getConnection(final String username, final String password) {
        return getConnection();
    }

    /**
     * 从元信息上下文中获取到数据库map, key为数据库名，value为DataSource
     * @return 所有数据库构成的map
     */
    public Map<String, DataSource> getDataSourceMap() {
        return metaDataContexts.getDefaultMetaData().getResource().getDataSources();
    }

    /**
     * 实现jdbc的close方法，需要关闭所有数据库资源
     */
    @Override
    public void close() throws Exception {
        //获取所有数据库，并关闭
        close(getDataSourceMap().keySet());
    }
    
    /**
     * Close dataSources.
     * 
     * @param dataSourceNames data source names
     * @throws Exception exception
     */
    public void close(final Collection<String> dataSourceNames) throws Exception {
        for (String each : dataSourceNames) {
            close(getDataSourceMap().get(each));
        }
        //元信息上下文也需要关闭
        metaDataContexts.close();
    }

    /**
     * 关闭单个数据库
     */
    private void close(final DataSource dataSource) throws Exception {
        if (dataSource instanceof AutoCloseable) {
            ((AutoCloseable) dataSource).close();
        }
    }
}
