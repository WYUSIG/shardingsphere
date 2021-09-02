## ShardingSphere-5.0.0-beta源码学习



#### 前言

ShardingSphere的执行步骤更多的是考虑如何高效利用连接数，我们都知道数据库连接这种宝贵的资源一般需要设计资源池。

ShardingSphere也不例外，但在分库分表情况下，单个库下可能有多个分表。

假如每个sql执行单元都获取一个独立的数据库连接，那么执行效率一般会比单个连接要快。

但是假如有200个分表都要执行，也就是200个SQLUnit，那么就会非常耗数据库连接资源。

但是如果改成串行，多个SQLUnit一个数据库连接数，那么首先执行会变慢，结果归并需要加载到内存，才能去读取下一个SQLUnit结果（内存归并）。

如果是多个独立数据库连接，那么不同的ResultSet互补干扰，根据游标一个一个取，避免过早将所有数据加载到内存（流式归并）。

为此，ShardingSphere设计两种连接模式：内存限制模式 和 连接限制模式。



#### 内存限制模式

内存限制模式就是不对数据库连接做数量限制，比如只要操作10个分表，那么会分配10个数据库连接

这种适合一次需要数据库连接不多的情况，同时能使用流式归并合并结果



#### 连接数限制模式

ShardingSphere 严格控制对一次操作所耗费的数据库连接数量。
如果实际执行的 SQL 需要对某数据库实例中的 200 张表做操作，那么只会创建唯一的数据库连接，并对其 200 张表串行处理。

该模式只能使用内存合并合并结果。



#### ShardingSphere对两种模式的选择

我们可以看到，对应只需少量的连接适合使用内存限制模式，所以ShardingSphere是通过一条公式来决定使用哪种模式的

![连接模式计算公式](https://shardingsphere.apache.org/document/current/img/sharding/connection_mode_cn.png)

下面我们到代码里面看一下执行部分代码



### 1、代码入口

这次我们挑选ShardingSpherePreparedStatement#executeQuery0方法开始进行

```java
public class ShardingSpherePreparedStatement {
    private List<QueryResult> executeQuery0() throws SQLException {
        //如果元信息的rules里面有RawExecutionRule子类配置的，目前没有看到RawExecutionRule实现
        if (metaDataContexts.getDefaultMetaData().getRuleMetaData().getRules().stream().anyMatch(each -> each instanceof RawExecutionRule)) {
            return rawExecutor.execute(createRawExecutionGroupContext(), executionContext.getSqlStatementContext(),
                    new RawSQLExecutorCallback()).stream().map(each -> (QueryResult) each).collect(Collectors.toList());
        }
        //从执行上下文拿到路由上下文，如果路由命中多个库，使用federateExecutor执行
        if (executionContext.getRouteContext().isFederated()) {
            return executeFederatedQuery();
        }
        //按数据源将执行单元分组，并创建执行组上下文
        ExecutionGroupContext<JDBCExecutionUnit> executionGroupContext = createExecutionGroupContext();
        //对这次的PreparedStatement、sql参数进行缓存
        cacheStatements(executionGroupContext.getInputGroups());
        //执行
        return driverJDBCExecutor.executeQuery(executionGroupContext, executionContext.getSqlStatementContext(),
                new PreparedStatementExecuteQueryCallback(metaDataContexts.getDefaultMetaData().getResource().getDatabaseType(), sqlStatement, SQLExecutorExceptionHandler.isExceptionThrown()));
    }
}
```

这个方法会被executeQuery调用，没错，就是jdbc接口里面的executeQuery，其中执行单元分组和使用哪种连接模式就在创建ExecutionGroupContext的代码中



### 2、ExecutionGroupContext

```java
public class ShardingSpherePreparedStatement {
    
    private ExecutionGroupContext<JDBCExecutionUnit> createExecutionGroupContext() throws SQLException {
        //创建执行前置准备引擎DriverExecutionPrepareEngine(获取最大连接数)
        DriverExecutionPrepareEngine<JDBCExecutionUnit, Connection> prepareEngine = createDriverExecutionPrepareEngine();
        //使用执行前置准备引擎执行前置准备工作，创建执行组上下文，分组依据是实际sql操作是否是同一个数据源
        return prepareEngine.prepare(executionContext.getRouteContext(), executionContext.getExecutionUnits());
    }
    
    private DriverExecutionPrepareEngine<JDBCExecutionUnit, Connection> createDriverExecutionPrepareEngine() {
        //获取配置的查询最大连接数max-connections-size-per-query
        int maxConnectionsSizePerQuery = metaDataContexts.getProps().<Integer>getValue(ConfigurationPropertyKey.MAX_CONNECTIONS_SIZE_PER_QUERY);
        //构建DriverExecutionPrepareEngine
        return new DriverExecutionPrepareEngine<>(JDBCDriverType.PREPARED_STATEMENT, maxConnectionsSizePerQuery, connection,
                statementOption, metaDataContexts.getDefaultMetaData().getRuleMetaData().getRules());
    }
}
```

前面都是准备工作，关键就在于prepareEngine#prepare方法调用

```java
public abstract class AbstractExecutionPrepareEngine<T> implements ExecutionPrepareEngine<T> {
    
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
            //自动选择内存限制模式还是连接限制模式
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
}
```

ConnectionMode connectionMode = maxConnectionsSizePerQuery < sqlUnits.size() ? ConnectionMode.CONNECTION_STRICTLY : ConnectionMode.MEMORY_STRICTLY;

同一个数据源下

数据执行单元数量 > 配置的maxConnectionsSizePerQuery：使用连接数限制模式

数据执行单元数量 <= 配置的maxConnectionsSizePerQuery：使用内存限制模式



### 3、执行

前期工作都做完，其实执行已经完事具备，就是拿到真正数据库的Statement，调用指定方法，返回结果

```java
public class ShardingSpherePreparedStatement {
    private List<QueryResult> executeQuery0() throws SQLException {
        ...
        //执行
        return driverJDBCExecutor.executeQuery(executionGroupContext, executionContext.getSqlStatementContext(),
                new PreparedStatementExecuteQueryCallback(metaDataContexts.getDefaultMetaData().getResource().getDatabaseType(), sqlStatement, SQLExecutorExceptionHandler.isExceptionThrown()));
    }
}
```

追executeQuery

```java
public final class DriverJDBCExecutor {
    public List<QueryResult> executeQuery(final ExecutionGroupContext<JDBCExecutionUnit> executionGroupContext,
                                          final SQLStatementContext<?> sqlStatementContext, final ExecuteQueryCallback callback) throws SQLException {
        try {
            //初始化执行处理引擎
            ExecuteProcessEngine.initialize(sqlStatementContext, executionGroupContext, metaDataContexts.getProps());
            //执行返回QueryResult，如果是内存归并，QueryResult的实现是JDBCMemoryQueryResult；如果是流式归并，则是JDBCStreamQueryResult
            List<QueryResult> result = jdbcExecutor.execute(executionGroupContext, callback);
            //执行处理引擎发布执行完成事件
            ExecuteProcessEngine.finish(executionGroupContext.getExecutionID());
            return result;
        } finally {
            ExecuteProcessEngine.clean();
        }
    }
}
```

继续追jdbcExecutor.execute方法

```java
public final class JDBCExecutor {
    public <T> List<T> execute(final ExecutionGroupContext<JDBCExecutionUnit> executionGroupContext,
                               final JDBCExecutorCallback<T> firstCallback, final JDBCExecutorCallback<T> callback) throws SQLException {
        try {
            //调用执行引擎execute方法
            return executorEngine.execute(executionGroupContext, firstCallback, callback, serial);
        } catch (final SQLException ex) {
            SQLExecutorExceptionHandler.handleException(ex);
            return Collections.emptyList();
        }
    }
}
```

继续追ExecutorEngine#execute方法，我们就看串行化的设计就好了，并行的只是拿到Future再处理

```java
public final class ExecutorEngine implements AutoCloseable {
    public <I, O> List<O> execute(final ExecutionGroupContext<I> executionGroupContext,
                                  final ExecutorCallback<I, O> firstCallback, final ExecutorCallback<I, O> callback, final boolean serial) throws SQLException {
        //如果执行组为空，代码没有执行单元，直接返回空列表
        if (executionGroupContext.getInputGroups().isEmpty()) {
            return Collections.emptyList();
        }
        //判断是否串行化，串行化的话调用serialExecute，并行调用parallelExecute
        return serial ? serialExecute(executionGroupContext.getInputGroups().iterator(), firstCallback, callback)
                : parallelExecute(executionGroupContext.getInputGroups().iterator(), firstCallback, callback);
    }

    private <I, O> Collection<O> syncExecute(final ExecutionGroup<I> executionGroup, final ExecutorCallback<I, O> callback) throws SQLException {
        //使用ExecutorCallback真正去调用
        return callback.execute(executionGroup.getInputs(), true, ExecutorDataMap.getValue());
    }
}
```

继续追ExecutorCallback#excute方法

```java
public abstract class JDBCExecutorCallback<T> implements ExecutorCallback<JDBCExecutionUnit, T> {
    @Override
    public final Collection<T> execute(final Collection<JDBCExecutionUnit> executionUnits, final boolean isTrunkThread, final Map<String, Object> dataMap) throws SQLException {
        // TODO It is better to judge whether need sane result before execute, can avoid exception thrown
        Collection<T> result = new LinkedList<>();
        //遍历执行组内的执行单元
        for (JDBCExecutionUnit each : executionUnits) {
            //执行单元执行
            T executeResult = execute(each, isTrunkThread, dataMap);
            if (null != executeResult) {
                result.add(executeResult);
            }
        }
        return result;
    }
    
    private T execute(final JDBCExecutionUnit jdbcExecutionUnit, final boolean isTrunkThread, final Map<String, Object> dataMap) throws SQLException {
        SQLExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);
        DataSourceMetaData dataSourceMetaData = getDataSourceMetaData(jdbcExecutionUnit.getStorageResource().getConnection().getMetaData());
        SQLExecutionHook sqlExecutionHook = new SPISQLExecutionHook();
        try {
            //获取sql单元
            SQLUnit sqlUnit = jdbcExecutionUnit.getExecutionUnit().getSqlUnit();
            sqlExecutionHook.start(jdbcExecutionUnit.getExecutionUnit().getDataSourceName(), sqlUnit.getSql(), sqlUnit.getParameters(), dataSourceMetaData, isTrunkThread, dataMap);
            //改写好的sql、数据源的Statement、连接模式，已经完事具备
            T result = executeSQL(sqlUnit.getSql(), jdbcExecutionUnit.getStorageResource(), jdbcExecutionUnit.getConnectionMode());
            sqlExecutionHook.finishSuccess();
            finishReport(dataMap, jdbcExecutionUnit);
            return result;
        } catch (final SQLException ex) {
            if (!isTrunkThread) {
                return null;
            }
            Optional<T> saneResult = getSaneResult(sqlStatement);
            if (saneResult.isPresent()) {
                return saneResult.get();
            }
            sqlExecutionHook.finishFailure(ex);
            SQLExecutorExceptionHandler.handleException(ex);
            return null;
        }
    }
}
```

最后一步追

```java
public abstract class ExecuteQueryCallback extends JDBCExecutorCallback<QueryResult> {
    
    @Override
    protected final QueryResult executeSQL(final String sql, final Statement statement, final ConnectionMode connectionMode) throws SQLException {
        //使用数据源的statement真正执行sql
        ResultSet resultSet = executeQuery(sql, statement);
        //判断连接模式来确定使用流式归并还是内存归并
        return ConnectionMode.MEMORY_STRICTLY == connectionMode ? new JDBCStreamQueryResult(resultSet) : new JDBCMemoryQueryResult(resultSet);
    }
}
```

可以看到，执行程序层次非常深，但是其代码并不复杂，就是遍历执行单元，然后执行



可能有同学会问数据源的statement是怎么创建的，已经它是怎么根据连接模式限制连接数的？



答案就在创建ExecutionGroup的时候



![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210903025724.png)



### 4、根据连接模式创建数据库连接



```java
public abstract class AbstractExecutionPrepareEngine<T> implements ExecutionPrepareEngine<T> {
    
    @Override
    public final ExecutionGroupContext<T> prepare(final RouteContext routeContext, final Collection<ExecutionUnit> executionUnits) throws SQLException {
			...
            result.addAll(group(dataSourceName, sqlUnitGroups, connectionMode));
        }
        return decorate(routeContext, result);
    }
}
```

就是group方法



```java
public final class DriverExecutionPrepareEngine<T extends DriverExecutionUnit<?>, C> extends AbstractExecutionPrepareEngine<T> {
    @Override
    protected List<ExecutionGroup<T>> group(final String dataSourceName, final List<List<SQLUnit>> sqlUnitGroups, final ConnectionMode connectionMode) throws SQLException {
        List<ExecutionGroup<T>> result = new LinkedList<>();
        //根据数据库名字、执行组的sql执行数量，连接模式来创建数据库连接
        List<C> connections = executorDriverManager.getConnections(dataSourceName, sqlUnitGroups.size(), connectionMode);
        int count = 0;
        for (List<SQLUnit> each : sqlUnitGroups) {
            result.add(createExecutionGroup(dataSourceName, each, connections.get(count++), connectionMode));
        }
        return result;
    }
}
```

追下去，又来到我们熟悉的ShardingSphereConnection类

```java
public final class ShardingSphereConnection extends AbstractConnectionAdapter implements ExecutorJDBCManager {
    @Override
    public List<Connection> getConnections(final String dataSourceName, final int connectionSize, final ConnectionMode connectionMode) throws SQLException {
        DataSource dataSource = dataSourceMap.get(dataSourceName);
        Preconditions.checkState(null != dataSource, "Missing the data source name: '%s'", dataSourceName);
        Collection<Connection> connections;
        //尝试从缓存中取数据库连接
        synchronized (getCachedConnections()) {
            connections = getCachedConnections().get(dataSourceName);
        }
        List<Connection> result;
        if (connections.size() >= connectionSize) {
            //缓存能满足直接分配
            result = new ArrayList<>(connections).subList(0, connectionSize);
        } else if (!connections.isEmpty()) {
            //缓存有，但不满足sql执行数量
            result = new ArrayList<>(connectionSize);
            result.addAll(connections);
            //半缓存半创建
            List<Connection> newConnections = createConnections(dataSourceName, dataSource, connectionSize - connections.size(), connectionMode);
            result.addAll(newConnections);
            synchronized (getCachedConnections()) {
                getCachedConnections().putAll(dataSourceName, newConnections);
            }
        } else {
            //缓存拿不到，直接创建
            result = new ArrayList<>(createConnections(dataSourceName, dataSource, connectionSize, connectionMode));
            synchronized (getCachedConnections()) {
                getCachedConnections().putAll(dataSourceName, result);
            }
        }
        return result;
    }
    
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private List<Connection> createConnections(final String dataSourceName, final DataSource dataSource, final int connectionSize, final ConnectionMode connectionMode) throws SQLException {
        if (1 == connectionSize) {
            Connection connection = createConnection(dataSourceName, dataSource);
            replayMethodsInvocation(connection);
            return Collections.singletonList(connection);
        }
        if (ConnectionMode.CONNECTION_STRICTLY == connectionMode) {
            //连接数限制模式的创建方式
            return createConnections(dataSourceName, dataSource, connectionSize);
        }
        //内存限制模式的创建方式
        synchronized (dataSource) {
            return createConnections(dataSourceName, dataSource, connectionSize);
        }
    }
}
```

可以看到会根据不同模式进行创建不同数量的Connection