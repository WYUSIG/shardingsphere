## ShardingSphere源码学习-5.0.0-beta



##### 前言

至于为什么会先选jdbc core开始看呢？因为在之前jdbc示例时可以看到创建DataSource代码如下：

```java
/**
 * 根据参数，创建不同example的DataSource
 */
public final class DataSourceFactory {
    
    public static DataSource newInstance(final ShardingType shardingType) throws SQLException {
        switch (shardingType) {
            case SHARDING_DATABASES:
                return new ShardingDatabasesConfigurationPrecise().getDataSource();
            case SHARDING_TABLES:
                return new ShardingTablesConfigurationPrecise().getDataSource();
            case SHARDING_DATABASES_AND_TABLES:
                return new ShardingDatabasesAndTablesConfigurationPrecise().getDataSource();
            case READWRITE_SPLITTING:
                return new ReadwriteSplittingConfiguration().getDataSource();
            case SHARDING_READWRITE_SPLITTING:
                return new ShardingReadwriteSplittingConfigurationPrecise().getDataSource();
            default:
                throw new UnsupportedOperationException(shardingType.name());
        }
    }
}
```

```java
public final class ShardingDatabasesConfigurationPrecise implements ExampleConfiguration {

    /**
     * 由ShardingSphereDataSourceFactory创建一个DataSource
     * @return jdbc接口的DataSource
     */
    @Override
    public DataSource getDataSource() throws SQLException {
        return ShardingSphereDataSourceFactory.createDataSource(createDataSourceMap(), Collections.singleton(createShardingRuleConfiguration()), new Properties());
    }
}
```

就是这个ShardingSphereDataSourceFactory，这里已经可以瞥见ShardingSphere将会实现jdbc接口，然后实现自己的逻辑，这里我们就来探讨以下一条sql在ShardingSphere执行大致过程



### 1、jdbc core



#### 1.1、目录截图

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210828165408.png)

从目录可以看到jdbc的常见元素：datasource、connection、statement、resultset



#### 1.2、ShardingSphere DataSource Factory

ShardingSphere提供了两种创建DataSource方式，普通java api和yaml

其中yaml又调用了普通java api方式

下面我们来看一下这两种方式：



#### 1.2.1、普通java api创建ShardingSphereDataSource

```java
package org.apache.shardingsphere.driver.api;

/**
 * ShardingSphere data source factory.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ShardingSphereDataSourceFactory {
    
    /**
     * Create ShardingSphere data source.
     *
     * @param dataSourceMap data source map
     * @param configurations rule configurations
     * @param props properties for data source
     * @return ShardingSphere data source
     * @throws SQLException SQL exception
     */
    public static DataSource createDataSource(final Map<String, DataSource> dataSourceMap, final Collection<RuleConfiguration> configurations, final Properties props) throws SQLException {
        return new ShardingSphereDataSource(dataSourceMap, configurations, props);
    }
    
    /**
     * Create ShardingSphere data source.
     *
     * @param dataSource data source
     * @param configurations rule configurations
     * @param props properties for data source
     * @return ShardingSphere data source
     * @throws SQLException SQL exception
     */
    public static DataSource createDataSource(final DataSource dataSource, final Collection<RuleConfiguration> configurations, final Properties props) throws SQLException {
        Map<String, DataSource> dataSourceMap = new HashMap<>(1, 1);
        dataSourceMap.put(DefaultSchema.LOGIC_NAME, dataSource);
        return createDataSource(dataSourceMap, configurations, props);
    }
}
```

可以看到里面就两个方法，本质上是一个方法，都是通过传入普通DataSource、RuleConfiguration集合、Properties，然后调用ShardingSphereDataSource构造方法即可创建

其中RuleConfiguration是一个抽象接口，具体的分片、读写分离、加密等配置项由各个功能去定义

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210828180757.png)



#### 1.2.2、yaml文件创建ShardingSphereDataSource

```java
package org.apache.shardingsphere.driver.api.yaml;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class YamlShardingSphereDataSourceFactory {

    private static final YamlRuleConfigurationSwapperEngine SWAPPER_ENGINE = new YamlRuleConfigurationSwapperEngine();

    private static final YamlDataSourceConfigurationSwapper DATASOURCE_SWAPPER = new YamlDataSourceConfigurationSwapper();

    /**
     * Create ShardingSphere data source.
     *
     * @param yamlFile YAML file for rule configurations
     * @return ShardingSphere data source
     * @throws SQLException SQL exception
     * @throws IOException IO exception
     */
    public static DataSource createDataSource(final File yamlFile) throws SQLException, IOException {
        YamlRootRuleConfigurations configurations = YamlEngine.unmarshal(yamlFile, YamlRootRuleConfigurations.class);
        return ShardingSphereDataSourceFactory.createDataSource(DATASOURCE_SWAPPER.swapToDataSources(configurations.getDataSources()),
                SWAPPER_ENGINE.swapToRuleConfigurations(configurations.getRules()), configurations.getProps());
    }
    
    ...
}
```

其实就是加了yaml文件解析，然后解析出DataSource、RuleConfiguration集合、Properties调用ShardingSphereDataSourceFactory方法

不过有个比较有意思的是YamlRootRuleConfigurations类

```java
public class YamlRootRuleConfigurations implements YamlConfiguration {
    
    private Map<String, Map<String, Object>> dataSources = new HashMap<>();
    
    private Collection<YamlRuleConfiguration> rules = new LinkedList<>();
    
    private Properties props = new Properties();
}
```

分别是dataSources、rules和props，这也是我们编写SHardingSphere yaml配置文件的结构

ShardingSphere DataSource Factory就先介绍到这里，下面我们来看一下ShardingSphereDataSource



### 1.3、ShardingSphereDataSource

```java
package org.apache.shardingsphere.driver.jdbc.core.datasource;

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
```

上面已经写了比较完整的注释，可以看到ShardingSphereDataSource主要就是构建元信息上下文和事务上下文，

并且实现jdbc DataSource的抽象方法getConnection和close，其中getConnection使用元信息上下文、事务上下文等构造一个ShardingSphereConnection，

毫无疑问，ShardingSphereConnection也是实现了jdbc的Connection；而close就是对管理的各个数据库进行关闭和元信息上下文

其中，元信息上下文MetaDataContexts也有两个实现类，分别是基于注册中心管理的GovernanceMetaDataContexts和基于本地配置的StandardMetaDataContexts



### 1.4、ShardingSphereConnection

```java
package org.apache.shardingsphere.driver.jdbc.core.connection;

public final class ShardingSphereConnection extends AbstractConnectionAdapter implements ExecutorJDBCManager {

    //所有数据库
    private final Map<String, DataSource> dataSourceMap;

    //元数据上下文
    private final MetaDataContexts metaDataContexts;

    //事务类型：local、xa、base
    private final TransactionType transactionType;

    //分布式事务管理器
    private final ShardingTransactionManager shardingTransactionManager;

    @Getter(AccessLevel.NONE)
    private boolean autoCommit = true;

    public ShardingSphereConnection(final Map<String, DataSource> dataSourceMap,
                                    final MetaDataContexts metaDataContexts, final TransactionContexts transactionContexts, final TransactionType transactionType) {
        this.dataSourceMap = dataSourceMap;
        this.metaDataContexts = metaDataContexts;
        this.transactionType = transactionType;
        shardingTransactionManager = transactionContexts.getDefaultTransactionManagerEngine().getTransactionManager(transactionType);
    }

    /**
     * 实现jdbc接口，获取预处理statement
     * @param sql sql语句
     */
    @Override
    public PreparedStatement prepareStatement(final String sql) throws SQLException {
        return new ShardingSpherePreparedStatement(this, sql);
    }

    /**
     * 实现jdbc接口，获取statement
     */
    @Override
    public Statement createStatement() {
        return new ShardingSphereStatement(this);
    }
}
```

通过源码可以发现，ShardingSphereConnection主要是创建ShardingSpherePreparedStatement、ShardingSphereStatement，并通过this参数把信息全部传到Statement，同时还涉及事务的commit、rollback，这里就不探讨



### 1.5、ShardingSpherePreparedStatement

```java
package org.apache.shardingsphere.driver.jdbc.core.statement;

public final class ShardingSpherePreparedStatement extends AbstractPreparedStatementAdapter {

    /**
     * ShardingSpherePreparedStatement构造方法
     * @param connection ShardingSphereConnection
     * @param sql sql语句
     * @param resultSetType ResultSet类型，一般为ResultSet.TYPE_FORWARD_ONLY，一次只读一条记录，读后释放
     * @param resultSetConcurrency ResultSet是否只读
     * @param resultSetHoldability 提交事务后，ResultSet是否依然可读
     * @param returnGeneratedKeys 是否返回生成的主键
     * @throws SQLException sql异常
     */
    private ShardingSpherePreparedStatement(final ShardingSphereConnection connection, final String sql,
                                            final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final boolean returnGeneratedKeys) throws SQLException {
        if (Strings.isNullOrEmpty(sql)) {
            throw new SQLException(SQLExceptionConstant.SQL_STRING_NULL_OR_EMPTY);
        }
        //ShardingSphereConnection,含有元信息，事务
        this.connection = connection;
        //元信息上下文
        metaDataContexts = connection.getMetaDataContexts();
        this.sql = sql;
        statements = new ArrayList<>();
        parameterSets = new ArrayList<>();
        //sql解析引擎
        ShardingSphereSQLParserEngine sqlParserEngine = new ShardingSphereSQLParserEngine(
                DatabaseTypeRegistry.getTrunkDatabaseTypeName(metaDataContexts.getDefaultMetaData().getResource().getDatabaseType()));
        //解析sql, 获得一个SQLStatement，SQLStatement有很多实现类，分别对应各类sql语句，如CreateDatabaseStatement、InsertStatement
        sqlStatement = sqlParserEngine.parse(sql, true);
        //把SQLStatement包装成ParameterMetaData，相当于sql中的问号，占位符信息
        parameterMetaData = new ShardingSphereParameterMetaData(sqlStatement);
        statementOption = returnGeneratedKeys ? new StatementOption(true) : new StatementOption(resultSetType, resultSetConcurrency, resultSetHoldability);
        //jdbc执行器
        JDBCExecutor jdbcExecutor = new JDBCExecutor(metaDataContexts.getExecutorEngine(), connection.isHoldTransaction());
        //驱动执行器，包含了jdbc执行器
        driverJDBCExecutor = new DriverJDBCExecutor(metaDataContexts, jdbcExecutor);
        rawExecutor = new RawExecutor(metaDataContexts.getExecutorEngine(), connection.isHoldTransaction(), metaDataContexts.getProps());
        // TODO Consider FederateRawExecutor
        federateExecutor = new FederateJDBCExecutor(DefaultSchema.LOGIC_NAME, metaDataContexts.getOptimizeContextFactory(), metaDataContexts.getProps(), jdbcExecutor);
        //sql批处理执行器
        batchPreparedStatementExecutor = new BatchPreparedStatementExecutor(metaDataContexts, jdbcExecutor);
        //内核处理器
        kernelProcessor = new KernelProcessor();
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        ResultSet result;
        try {
            //执行前先清空之前set的参数
            clearPrevious();
            //创建执行上下文
            executionContext = createExecutionContext();
            //执行返回结果
            List<QueryResult> queryResults = executeQuery0();
            //合并结果
            MergedResult mergedResult = mergeQuery(queryResults);
            result = new ShardingSphereResultSet(getResultSetsForShardingSphereResultSet(), mergedResult, this, executionContext);
        } finally {
            clearBatch();
        }
        currentResultSet = result;
        return result;
    }
}
```

这里可以看到ShardingSpherePreparedStatement实现相当复杂，包含各种执行器、上下文，这里看一个比较重要的**执行上下文**

```java
public class ShardingSpherePreparedStatement {
    private ExecutionContext createExecutionContext() {
        //进一步解析sql
        LogicSQL logicSQL = createLogicSQL();
        //检查sql
        SQLCheckEngine.check(logicSQL.getSqlStatementContext().getSqlStatement(), logicSQL.getParameters(),
                metaDataContexts.getDefaultMetaData().getRuleMetaData().getRules(), DefaultSchema.LOGIC_NAME, metaDataContexts.getMetaDataMap(), null);
        //通过内核处理器创建执行上下文
        ExecutionContext result = kernelProcessor.generateExecutionContext(logicSQL, metaDataContexts.getDefaultMetaData(), metaDataContexts.getProps());
        //找出所有的需要生成主键的，并把生成结果放到generatedValues
        findGeneratedKey(result).ifPresent(generatedKey -> generatedValues.addAll(generatedKey.getGeneratedValues()));
        //返回执行上下文
        return result;
    }

    private LogicSQL createLogicSQL() {
        //获取新set的PrepareStatement参数, 如setString(index, str)
        List<Object> parameters = new ArrayList<>(getParameters());
        ShardingSphereSchema schema = metaDataContexts.getDefaultMetaData().getSchema();
        //通过解析出来的sqlStatement、参数、ShardingSphereSchema构造出SQLStatement上下文，这个上下文很多信息，也有很多实现类，分DDl、DML等
        SQLStatementContext<?> sqlStatementContext = SQLStatementContextFactory.newInstance(schema, parameters, sqlStatement);
        //返回LogicSQL，意为解析好的sql
        return new LogicSQL(sqlStatementContext, sql, parameters);
    }
}
```

```java
public class KernelProcessor {
    public ExecutionContext generateExecutionContext(final LogicSQL logicSQL, final ShardingSphereMetaData metaData, final ConfigurationProperties props) {
        //创建路由上下文
        RouteContext routeContext = route(logicSQL, metaData, props);
        //重写sql结果
        SQLRewriteResult rewriteResult = rewrite(logicSQL, metaData, props, routeContext);
        //创建ExecutionContext
        ExecutionContext result = createExecutionContext(logicSQL, metaData, routeContext, rewriteResult);
        //如果开启打印sql了的话，打印sql
        logSQL(logicSQL, props, result);
        return result;
    }
}
```

可以看到，到执行上下文创建完成，执行所需的东西都基本有了，我们可以看以下ExecutionContext类

```java
public final class ExecutionContext {
    
    //解析好的SQLStatement上下文
    private final SQLStatementContext<?> sqlStatementContext;
    
    //执行单元集合
    private final Collection<ExecutionUnit> executionUnits;
    
    //路由上下文
    private final RouteContext routeContext;
    
    public ExecutionContext(final SQLStatementContext<?> sqlStatementContext, final ExecutionUnit executionUnit, final RouteContext routeContext) {
        this(sqlStatementContext, Collections.singletonList(executionUnit), routeContext);
    }
}
```

因为ShardingSphere代码实在庞大与复杂，所以只能先介绍这些，我们从这些流程中已经可以瞥见ShardingSphere的大致执行流程

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210829012633.png)



