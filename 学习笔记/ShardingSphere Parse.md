## ShardingSphere-5.0.0-beta源码学习

#### 前言

在上一篇最后，展示了ShardingSphere底层执行所需要经过的几个重要步骤

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210829012633.png)

今天我们就来学习一下ShardingSphere的sql解析

## 使用入口

在前面我们分析ShardingSpherePreparedStatement的过程中, 在构造方法中，会创建一个解析引擎ShardingSphereSQLParserEngine来进行解析sql

```java
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
```

## ShardingSphereSQLParserEngine

在上面的入口中，我们看到了通过元数据上下文中的数据库类型构造出ShardingSphereSQLParserEngine

```java
public final class ShardingSphereSQLParserEngine {

    //普通sql解析引擎
    private final SQLStatementParserEngine sqlStatementParserEngine;

    //distSql解析引擎
    private final DistSQLStatementParserEngine distSQLStatementParserEngine;

    public ShardingSphereSQLParserEngine(final String databaseTypeName) {
        //通过SQLStatementParserEngineFactory创建
        sqlStatementParserEngine = SQLStatementParserEngineFactory.getSQLStatementParserEngine(databaseTypeName);
        distSQLStatementParserEngine = new DistSQLStatementParserEngine();
    }
    
    /**
     * Parse to SQL statement.
     *
     * @param sql SQL to be parsed
     * @param useCache whether use cache
     * @return SQL statement
     */
    @SuppressWarnings("OverlyBroadCatchBlock")
    public SQLStatement parse(final String sql, final boolean useCache) {
        try {
            return parse0(sql, useCache);
            // CHECKSTYLE:OFF
            // TODO check whether throw SQLParsingException only
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            throw ex;
        }
    }

    private SQLStatement parse0(final String sql, final boolean useCache) {
        try {
            //尝试使用普通的sql解析引擎解析
            return sqlStatementParserEngine.parse(sql, useCache);
        } catch (final SQLParsingException | ParseCancellationException originalEx) {
            try {
                //上面解析失败，再使用distSQL解析引擎解析
                return distSQLStatementParserEngine.parse(sql);
            } catch (final SQLParsingException ignored) {
                throw originalEx;
            }
        }
    }
}
```

可以看到里面包含了两种解析引擎，SQLStatementParserEngine和DistSQLStatementParserEngine，当解析时，会先尝试用SQLStatementParserEngine进行解析，解析失败后再尝试使用DistSQLStatementParserEngine

DistSQL 是 ShardingSphere 特有的内置 SQL 语言，提供了标准 SQL 之外的增量功能操作能力，比如新建规则等。这里先不关注，我们重点看一下普通的 SQLStatementParserEngine

## SQLStatementParserEngine





https://zhuanlan.zhihu.com/p/114982293