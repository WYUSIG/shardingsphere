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



### 1、SQLRouteEngine

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



### 2、整个逻辑库路由AllSQLRouteExecutor

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



### 3、部分逻辑库路由PartialSQLRouteExecutor

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



#### 3.1、分片路由器ShardingSQLRouter

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



#### 3.2、读写分离路由器ReadwriteSplittingSQLRouter

```java
public final class ReadwriteSplittingSQLRouter implements SQLRouter<ReadwriteSplittingRule> {

    @Override
    public RouteContext createRouteContext(final LogicSQL logicSQL, final ShardingSphereMetaData metaData, final ReadwriteSplittingRule rule, final ConfigurationProperties props) {
        RouteContext result = new RouteContext();
        //构造出ReadwriteSplittingDataSourceRouter，调用route方法返回命中库的名字
        String dataSourceName = new ReadwriteSplittingDataSourceRouter(rule.getSingleDataSourceRule()).route(logicSQL.getSqlStatementContext().getSqlStatement());
        result.getRouteUnits().add(new RouteUnit(new RouteMapper(DefaultSchema.LOGIC_NAME, dataSourceName), Collections.emptyList()));
        return result;
    }
    ...
}
```

可以看到，其内部逻辑就是构造出ReadwriteSplittingDataSourceRouter，调用route方法返回命中库的名字，

我们再看看ReadwriteSplittingDataSourceRouter是如何判断主库还是从库的，以及回调机制



##### 3.2.1、ReadwriteSplittingDataSourceRouter

```java
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
}
```

可以看到，它根据解析出来的sql判断是否需要访问主库：

如果sql有锁操作 或者 非select语句 或者 PrimaryVisitedManager已经标记访问主库 或者 HintManager已经标记只写 或者 是个事务

则代表需要访问主库

以及我们可以看到DataSourceNameAware这个SPI，在需要访问主库或从库时，会分表回调用DataSourceNameAware#getPrimaryDataSourceName、DataSourceNameAware#getReplicaDataSourceNames方法，针对一些有特殊需要的开发者

从库的负载均衡算法ReplicaLoadBalanceAlgorithm我就不展开了，ShardingSphere内置了两个算法：
RandomReplicaLoadBalanceAlgorithm(随机)、RoundRobinReplicaLoadBalanceAlgorithm(轮询)



**总的来说，路由过程就是创建RouteContext，添加RouteUnit的过程，而RouteUnit里面记录了接下来sql执行时需要访问的数据库(含逻辑名和真实名)**

