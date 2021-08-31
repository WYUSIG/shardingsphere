## ShardingSphere-5.0.0-beta源码学习

ShardingSphere路由就是创建RouteContext的过程

如果你还记得之前分析的ShardingSpherePreparedStatement，在执行sql语句，创建执行上下文ExecutionContext时，就需要先创建路由上下文RouteContext

```java
public final class KernelProcessor {
    
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

下面我们就来探讨一下RouteContext创建过程

### SQLRouteEngine

在上面我们可以看到，创建RouteContext调用了KernelProcessor#route方法

```java
public final class KernelProcessor {
    
    private RouteContext route(final LogicSQL logicSQL, final ShardingSphereMetaData metaData, final ConfigurationProperties props) {
        //根据配置规则和props创建路由引擎，再调用路由引擎的route方法，传入解析+填入参数完成的sql和元数据，得到路由上下文
        return new SQLRouteEngine(metaData.getRuleMetaData().getRules(), props).route(logicSQL, metaData);
    }
}
```

而KernelProcessor#route方法里面又是创建SQLRouteEngine，然后再调用它的route方法

```java
@RequiredArgsConstructor
public final class SQLRouteEngine {
    
    private final Collection<ShardingSphereRule> rules;
    
    private final ConfigurationProperties props;
    
    /**
     * Route SQL.
     *
     * @param logicSQL logic SQL
     * @param metaData ShardingSphere meta data
     * @return route context
     */
    public RouteContext route(final LogicSQL logicSQL, final ShardingSphereMetaData metaData) {
        //判断sql是否需要全库执行，创建全库的路由执行器或部分的路由执行器
        SQLRouteExecutor executor = isNeedAllSchemas(logicSQL.getSqlStatementContext().getSqlStatement()) ? new AllSQLRouteExecutor() : new PartialSQLRouteExecutor(rules, props);
        //调用路由执行器route方法
        return executor.route(logicSQL, metaData);
    }
    
    // TODO use dynamic config to judge UnconfiguredSchema
    private boolean isNeedAllSchemas(final SQLStatement sqlStatement) {
        //如果sql是show tables则需要全库执行
        return sqlStatement instanceof MySQLShowTablesStatement;
    }
}
```
可以看到SQLRouteEngine里面主要是保存了配置的rules和props

SQLRouteEngine#route方法判断了是否需要遍历整个逻辑库，比如show tables语句，如果需要则直接创建AllSQLRouteExecutor

否则使用配置的rules和props创建PartialSQLRouteExecutor

### 整个逻辑库路由AllSQLRouteExecutor

```java
public final class AllSQLRouteExecutor implements SQLRouteExecutor {
    
    @Override
    public RouteContext route(final LogicSQL logicSQL, final ShardingSphereMetaData metaData) {
        RouteContext result = new RouteContext();
        //从元数据上下文中取出所有数据源，遍历，添加到路由上下文的routeUnits
        for (String each : metaData.getResource().getDataSources().keySet()) {
            result.getRouteUnits().add(new RouteUnit(new RouteMapper(each, each), Collections.emptyList()));
        }
        return result;
    }
}
```

可以看到AllSQLRouteExecutor非常简单，就是把所有数据源的逻辑名、真正名放到RouteContext#routeUnits

### 部分库路由PartialSQLRouteExecutor

```java
public final class PartialSQLRouteExecutor implements SQLRouteExecutor {
    
    static {
        //SPI SQLRouter类加载
        ShardingSphereServiceLoader.register(SQLRouter.class);
    }
    
    private final ConfigurationProperties props;
    
    @SuppressWarnings("rawtypes")
    private final Map<ShardingSphereRule, SQLRouter> routers;
    
    public PartialSQLRouteExecutor(final Collection<ShardingSphereRule> rules, final ConfigurationProperties props) {
        this.props = props;
        /**
         * 根据配置的rules规则，从SPI SQLRouter实现中，找到对应的SQLRouter，如：
         * rules:
         * - !SHARDING
         * 就会匹配 ShardingSQLRouter
         */
        routers = OrderedSPIRegistry.getRegisteredServices(rules, SQLRouter.class);
    }
    
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RouteContext route(final LogicSQL logicSQL, final ShardingSphereMetaData metaData) {
        RouteContext result = new RouteContext();
        for (Entry<ShardingSphereRule, SQLRouter> entry : routers.entrySet()) {
            if (result.getRouteUnits().isEmpty()) {
                //如果路由单元为空，直接调用对应的SQLRouter的createRouteContext，直接赋值
                result = entry.getValue().createRouteContext(logicSQL, metaData, entry.getKey(), props);
            } else {
                //调用对应的SQLRouter的decorateRouteContext,进行添加(ShardingSQLRouter目前暂无实现)
                entry.getValue().decorateRouteContext(result, logicSQL, metaData, entry.getKey(), props);
            }
        }
        if (result.getRouteUnits().isEmpty() && 1 == metaData.getResource().getDataSources().size()) {
            //如果没有命中路由，配置的数据源又只有一个，直接把这个数据源添加到路由上下文
            String singleDataSourceName = metaData.getResource().getDataSources().keySet().iterator().next();
            result.getRouteUnits().add(new RouteUnit(new RouteMapper(singleDataSourceName, singleDataSourceName), Collections.emptyList()));
        }
        return result;
    }
}
```

可以看到，其核心就是找到对应的SQLRouter(有ReadwriteSplittingSQLRouter、ShardingSQLRouter等)，调用其createRouteContext来创建路由上下文。

也是在这一步，路由根据配置的rule类型选择不同的SQLRouter分别处理不同的路由逻辑


### 分片路由器ShardingSQLRouter

```java
public final class ShardingSQLRouter implements SQLRouter<ShardingRule> {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public RouteContext createRouteContext(final LogicSQL logicSQL, final ShardingSphereMetaData metaData, final ShardingRule rule, final ConfigurationProperties props) {
        RouteContext result = new RouteContext();
        SQLStatement sqlStatement = logicSQL.getSqlStatementContext().getSqlStatement();
        //判断sql是DDL还是DML，进而判断sql是创建表、创建索引、创建视图、删除索引、插入、查询、删除等，创建特定的ShardingStatementValidator
        Optional<ShardingStatementValidator> validator = ShardingStatementValidatorFactory.newInstance(sqlStatement);
        //分片路由操作前的校验
        validator.ifPresent(optional -> optional.preValidate(rule, logicSQL.getSqlStatementContext(), logicSQL.getParameters(), metaData.getSchema()));
        //根据sql和配置创建分片条件ShardingConditions
        ShardingConditions shardingConditions = createShardingConditions(logicSQL, metaData, rule);
        //sql最后是否需要合并结果集
        boolean needMergeShardingValues = isNeedMergeShardingValues(logicSQL.getSqlStatementContext(), rule);
        if (sqlStatement instanceof DMLStatement && needMergeShardingValues) {
            //如果最后需要合并结果集，直接取最后一个分片条件
            mergeShardingConditions(shardingConditions);
        }
        //创建ShardingRouteEngine，再调用ShardingRouteEngine#route，对路由上下文进行添加路由单元
        ShardingRouteEngineFactory.newInstance(rule, metaData, logicSQL.getSqlStatementContext(), shardingConditions, props).route(result, rule);
        //分片路由操作后的校验
        validator.ifPresent(v -> v.postValidate(rule, logicSQL.getSqlStatementContext(), result, metaData.getSchema()));
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ShardingConditions createShardingConditions(final LogicSQL logicSQL, final ShardingSphereMetaData metaData, final ShardingRule rule) {
        List<ShardingCondition> shardingConditions;
        //如果是DML
        if (logicSQL.getSqlStatementContext().getSqlStatement() instanceof DMLStatement) {
            //创建ShardingConditionEngine，如果insert语句，则是InsertClauseShardingConditionEngine，否则是WhereClauseShardingConditionEngine
            ShardingConditionEngine shardingConditionEngine = ShardingConditionEngineFactory.createShardingConditionEngine(logicSQL, metaData, rule);
            /**
             * 调用对于的createShardingConditions方法，构造出条件ShardingConditions
             */
            shardingConditions = shardingConditionEngine.createShardingConditions(logicSQL.getSqlStatementContext(), logicSQL.getParameters());
        } else {
            shardingConditions = Collections.emptyList();
        }
        return new ShardingConditions(shardingConditions);
    }
}
```

上面的路由分片简单来说步骤就是：

(1) 分片路由操作前校验
(2) 拿到分片条件
(3) 创建分片路由引擎ShardingRouteEngine
(4) 调用分片路由引擎ShardingRouteEngine的route方法，添加路由单元
(5) 分片路由操作后校验

其中重点就是(2)(3)(4)步骤，感兴趣的同学可以继续深追，里面就有判断insert或where条件是否属于配置的分片字段等逻辑，较为复杂

### 读写分离路由器ReadwriteSplittingSQLRouter


