## ShardingSphere-5.0.0-beta源码学习



#### 前言

在上一篇最后，展示了ShardingSphere底层执行所需要经过的几个重要步骤

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210829012633.png)

总得来说，ShardingSphere的sql解析是使用antlr, 使用其maven插件，编写各种数据库类型的g4规则文件，然后生成相应的代码

其中在**sql->SQLStatement过程中经历了词法分析、语法分析、再使用visitor进行访问解析出SQLStatement**


今天我们就来学习一下ShardingSphere的sql解析



## 1、使用入口

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



## 2、ShardingSphereSQLParserEngine

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



### 3、SQLStatementParserEngine

```java
public final class SQLStatementParserEngine {

    //SQL statement解析 执行器
    private final SQLStatementParserExecutor sqlStatementParserExecutor;

    //guava的加载缓存
    private final LoadingCache<String, SQLStatement> sqlStatementCache;
    
    public SQLStatementParserEngine(final String databaseType) {
        //根据数据库类型初始化SQL statement解析 执行器
        sqlStatementParserExecutor = new SQLStatementParserExecutor(databaseType);
        // TODO use props to configure cache option
        //初始化guava的加载缓存
        sqlStatementCache = SQLStatementCacheBuilder.build(new CacheOption(2000, 65535L, 4), databaseType);
    }
    
    /**
     * Parse to SQL statement.
     *
     * @param sql SQL to be parsed
     * @param useCache whether use cache
     * @return SQL statement
     */
    public SQLStatement parse(final String sql, final boolean useCache) {
        /**
         * 如果是使用缓存则从sqlStatementCache中直接取，取不到则调用SQLStatementCacheLoader进行加载(SQLStatementCacheLoader内部也是使用SQL statement解析 执行器)
         * 如果不使用缓存，则使用SQL statement解析 执行器 解析出 对应的 SQLStatement
         */
        return useCache ? sqlStatementCache.getUnchecked(sql) : sqlStatementParserExecutor.parse(sql);
    }
}
```

SQLStatementParserEngine使用了Guava缓存，这种设计在ShardingSphere出现地方挺多的。

然后SQLStatementParserEngine内部核心就是调用sqlStatementParserExecutor.parse(sql)，得到SQLStatement



### 4、语法解析

```java
public final class SQLStatementParserExecutor {

    //解析引擎
    private final SQLParserEngine parserEngine;

    //visitor引擎
    private final SQLVisitorEngine visitorEngine;
    
    public SQLStatementParserExecutor(final String databaseType) {
        //根据数据库类型初始化解析引擎
        parserEngine = new SQLParserEngine(databaseType);
        //根据数据引擎创建STATEMENT类信息的visitor引擎
        visitorEngine = new SQLVisitorEngine(databaseType, "STATEMENT", new Properties());
    }
    
    /**
     * Parse to SQL statement.
     *
     * @param sql SQL to be parsed
     * @return SQL statement
     */
    public SQLStatement parse(final String sql) {
        //解析引擎先解析sql得到语法树ParseTree，再使用visitor引擎解析出SQLStatement
        return visitorEngine.visit(parserEngine.parse(sql, false));
    }
}
```

可以看到通过解析引擎SQLParserEngine#parse即可语法解析sql即可得到语法树ParseTree，其实它不只是语法树那么简单，更是个语法上下文, 以下方mysql的SQLServerStatementParser为例

```java
public class SQLServerStatementParser extends Parser{
    
    public static class InsertContext extends ParserRuleContext {
        public TerminalNode INSERT() { return getToken(MySQLStatementParser.INSERT, 0); }
        public InsertSpecificationContext insertSpecification() {
            return getRuleContext(InsertSpecificationContext.class,0);
        }
        public TableNameContext tableName() {
            return getRuleContext(TableNameContext.class,0);
        }
        public InsertValuesClauseContext insertValuesClause() {
            return getRuleContext(InsertValuesClauseContext.class,0);
        }
        public SetAssignmentsClauseContext setAssignmentsClause() {
            return getRuleContext(SetAssignmentsClauseContext.class,0);
        }
        public InsertSelectClauseContext insertSelectClause() {
            return getRuleContext(InsertSelectClauseContext.class,0);
        }
        public TerminalNode INTO() { return getToken(MySQLStatementParser.INTO, 0); }
        public PartitionNamesContext partitionNames() {
            return getRuleContext(PartitionNamesContext.class,0);
        }
        public OnDuplicateKeyClauseContext onDuplicateKeyClause() {
            return getRuleContext(OnDuplicateKeyClauseContext.class,0);
        }
        public InsertContext(ParserRuleContext parent, int invokingState) {
            super(parent, invokingState);
        }
        @Override public int getRuleIndex() { return RULE_insert; }
        @Override
        public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
            if ( visitor instanceof MySQLStatementVisitor ) return ((MySQLStatementVisitor<? extends T>)visitor).visitInsert(this);
            else return visitor.visitChildren(this);
        }
    }
}
```

insert语句经过SQLParserEngine语法解析后便得到InsertContext，其中语法解析之前又需要经过词法解析，把每个单词都提取出来



### 5、词法解析

词法解析我们可以在SQLParserFactory中看到

```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SQLParserFactory {
    
    /**
     * New instance of SQL parser.
     * 
     * @param databaseType database type
     * @param sql SQL
     * @return SQL parser
     */
    public static SQLParser newInstance(final String databaseType, final String sql) {
        //门面模式，根据数据库类型获取对应的sql解析门面对象
        SQLParserFacade sqlParserFacade = SQLParserFacadeRegistry.getInstance().getSQLParserFacade(databaseType);
        return createSQLParser(sql, sqlParserFacade);
    }
    
    private static SQLParser createSQLParser(final String sql, final SQLParserFacade sqlParserFacade) {
        //从sql解析门面对象中取出词法解析器lexerClass，并对sql进行词法解析
        //从sql解析门面对象中取出语法解析器ParserClass
        return createSQLParser(createTokenStream(sql, sqlParserFacade.getLexerClass()), sqlParserFacade.getParserClass());
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private static SQLParser createSQLParser(final TokenStream tokenStream, final Class<? extends SQLParser> parserClass) {
        //把词法解析结果构建语法解析类
        return parserClass.getConstructor(TokenStream.class).newInstance(tokenStream);
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private static TokenStream createTokenStream(final String sql, final Class<? extends SQLLexer> lexerClass) {
        Lexer lexer = (Lexer) lexerClass.getConstructor(CharStream.class).newInstance(getSQLCharStream(sql));
        return new CommonTokenStream(lexer);
    }
    
    private static CharStream getSQLCharStream(final String sql) {
        CodePointBuffer buffer = CodePointBuffer.withChars(CharBuffer.wrap(sql.toCharArray()));
        return CodePointCharStream.fromBuffer(buffer);
    }
}
```

其中createTokenStream(sql, sqlParserFacade.getLexerClass())这句代码就是解析器门面中拿到词法解析类

下面以mysql解析器门面为例：

```java
public final class MySQLParserFacade implements SQLParserFacade {
    
    @Override
    public String getDatabaseType() {
        return "MySQL";
    }
    
    @Override
    public Class<? extends SQLLexer> getLexerClass() {
        return MySQLLexer.class;
    }
    
    @Override
    public Class<? extends SQLParser> getParserClass() {
        return MySQLParser.class;
    }
}
```

然后它的词法解析器类就是MySQLLexer：

```java
public final class MySQLLexer extends MySQLStatementLexer implements SQLLexer {
    
    public MySQLLexer(final CharStream input) {
        super(input);
    }
}
```

可以看到，继承了MySQLStatementLexer，MySQLStatementLexer是使用antlr生产的类

如果各位对antlr的细节感兴趣的话也可以去学习一下，这里也附上一些学习链接

>https://zhuanlan.zhihu.com/p/114982293
>https://github.com/antlr/antlr4.git

上面说了语法解析、词法解析，那么只剩下visitor访问，转化成SQLStatemeng



### 6、visitor访问

visitor访问的话在SQLVisitorEngine#visit方法，其中参数是语法解析完成的语法树上下文，例如InsertContext

```java
@RequiredArgsConstructor
public final class SQLVisitorEngine {
    
    private final String databaseType;
    
    private final String visitorType;

    private final Properties props;
    
    /**
     * Visit parse tree.
     *
     * @param parseTree parse tree
     * @param <T> type of SQL visitor result
     * @return SQL visitor result
     */
    public <T> T visit(final ParseTree parseTree) {
        //根据数据库类型、visitor类型(STATEMENT)、sql类型、初始化参数 获得对应的SQLTypeVisitor，如MySQLDDLStatementSQLVisitor
        ParseTreeVisitor<T> visitor = SQLVisitorFactory.newInstance(databaseType, visitorType, SQLVisitorRule.valueOf(parseTree.getClass()), props);
        //调用该类型的语法树的回调方法，该方法里面会调用ParseTreeVisitor的指定方法，如创建表、删除表等，最后得到指定Statement
        return parseTree.accept(visitor);
    }
}
```

通过工厂模式，创建对应的ParseTreeVisitor

```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SQLVisitorFactory {
    
    /**
     * New instance of SQL visitor.
     * 
     * @param databaseType database type
     * @param visitorType SQL visitor type
     * @param visitorRule SQL visitor rule
     * @param props SQL visitor config
     * @param <T> type of visitor result
     * @return parse tree visitor
     */
    public static <T> ParseTreeVisitor<T> newInstance(final String databaseType, final String visitorType, final SQLVisitorRule visitorRule, final Properties props) {
        SQLVisitorFacade facade = SQLVisitorFacadeRegistry.getInstance().getSQLVisitorFacade(databaseType, visitorType);
        return createParseTreeVisitor(facade, visitorRule.getType(), props);
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows(ReflectiveOperationException.class)
    private static <T> ParseTreeVisitor<T> createParseTreeVisitor(final SQLVisitorFacade visitorFacade, final SQLStatementType type, final Properties props) {
        switch (type) {
            case DML:
                return (ParseTreeVisitor) visitorFacade.getDMLVisitorClass().getConstructor(Properties.class).newInstance(props);
            case DDL:
                return (ParseTreeVisitor) visitorFacade.getDDLVisitorClass().getConstructor(Properties.class).newInstance(props);
            case TCL:
                return (ParseTreeVisitor) visitorFacade.getTCLVisitorClass().getConstructor(Properties.class).newInstance(props);
            case DCL:
                return (ParseTreeVisitor) visitorFacade.getDCLVisitorClass().getConstructor(Properties.class).newInstance(props);
            case DAL:
                return (ParseTreeVisitor) visitorFacade.getDALVisitorClass().getConstructor(Properties.class).newInstance(props);
            case RL:
                return (ParseTreeVisitor) visitorFacade.getRLVisitorClass().getConstructor(Properties.class).newInstance(props);
            default:
                throw new SQLParsingException("Can not support SQL statement type: `%s`", type);
        }
    }
}
```

可以看到又是一个门面模式，例如mysql DML语句的话MySQLDMLStatementSQLVisitor，而MySQLDMLStatementSQLVisitor又继承了MySQLStatementSQLVisitor

```java
@NoArgsConstructor
@Getter(AccessLevel.PROTECTED)
public abstract class MySQLStatementSQLVisitor extends MySQLStatementBaseVisitor<ASTNode> {
    
    @Override
    public ASTNode visitInsert(final InsertContext ctx) {
        // TODO :FIXME, since there is no segment for insertValuesClause, InsertStatement is created by sub rule.
        MySQLInsertStatement result;
        if (null != ctx.insertValuesClause()) {
            result = (MySQLInsertStatement) visit(ctx.insertValuesClause());
        } else if (null != ctx.insertSelectClause()) {
            result = (MySQLInsertStatement) visit(ctx.insertSelectClause());
        } else {
            result = new MySQLInsertStatement();
            result.setSetAssignment((SetAssignmentSegment) visit(ctx.setAssignmentsClause()));
        }
        if (null != ctx.onDuplicateKeyClause()) {
            result.setOnDuplicateKeyColumns((OnDuplicateKeyColumnsSegment) visit(ctx.onDuplicateKeyClause()));
        }
        result.setTable((SimpleTableSegment) visit(ctx.tableName()));
        result.setParameterCount(currentParameterIndex);
        return result;
    }

    @Override
    public ASTNode visitInsertSelectClause(final InsertSelectClauseContext ctx) {
        MySQLInsertStatement result = new MySQLInsertStatement();
        if (null != ctx.LP_()) {
            if (null != ctx.fields()) {
                result.setInsertColumns(new InsertColumnsSegment(ctx.LP_().getSymbol().getStartIndex(), ctx.RP_().getSymbol().getStopIndex(), createInsertColumns(ctx.fields())));
            } else {
                result.setInsertColumns(new InsertColumnsSegment(ctx.LP_().getSymbol().getStartIndex(), ctx.RP_().getSymbol().getStopIndex(), Collections.emptyList()));
            }
        } else {
            result.setInsertColumns(new InsertColumnsSegment(ctx.start.getStartIndex() - 1, ctx.start.getStartIndex() - 1, Collections.emptyList()));
        }
        result.setInsertSelect(createInsertSelectSegment(ctx));
        return result;
    }
}
```

MySQLStatementBaseVisitor是antlr生成的类，MySQLStatementSQLVisitor是它的实现，也就是antlr的visitor模式。

总之整个sql解析简单流程就是:

**sql -> 词法解析(Lexer) -> 语法解析(Parser) -> 访问(visitor) -> SQLSatement**

> 一句话说完的事又水了那么长，嘻嘻

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/1630229516(1).jpg)

