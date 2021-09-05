## ShardingSphere源码学习-5.0.0-beta



#### 前言

在分库分表场景下，我们直接输入的sql会碰到需要改写的情况，例如计算平均数：

```sql
SELECT AVG(price) FROM t_order WHERE user_id=1;
```

直接路由到各个数据库，然后合并平均数算出来的结果时不正确的

这时候就需要ShardingSphere改写引擎进行补列，同时查出count和sum

```sql
SELECT COUNT(price) AS AVG_DERIVED_COUNT_0, SUM(price) AS AVG_DERIVED_SUM_0 FROM t_order WHERE user_id=1;
```

当然改写规则还非常多，比如逻辑表名改成真实表面、自动生成主键的参数改写、分页修正等等，这是ShardingSphere官方文档的改写规则图：

![改写引擎结构](https://shardingsphere.apache.org/document/current/img/sharding/rewrite_architecture_cn.png)



### 1、代码入口

我们回到创建ExecutionContext过程中

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

    private SQLRewriteResult rewrite(final LogicSQL logicSQL, final ShardingSphereMetaData metaData, final ConfigurationProperties props, final RouteContext routeContext) {
        return new SQLRewriteEntry(
                metaData.getSchema(), props, metaData.getRuleMetaData().getRules()).rewrite(logicSQL.getSql(), logicSQL.getParameters(), logicSQL.getSqlStatementContext(), routeContext);
    }
}
```

这里创建了SQLRewriteEntry，并调用它的rewrite方法，同时所有的真实表(metaData.getSchema())、配置的props、rules, 解析好的sql和参数等都传到了SQLRewriteEntry



### 2、SQLRewriteEntry

```java
public final class SQLRewriteEntry {
    
    static {
        //SPI加载
        ShardingSphereServiceLoader.register(SQLRewriteContextDecorator.class);
    }
    
    private final ShardingSphereSchema schema;
    
    private final ConfigurationProperties props;
    
    @SuppressWarnings("rawtypes")
    private final Map<ShardingSphereRule, SQLRewriteContextDecorator> decorators;
    
    public SQLRewriteEntry(final ShardingSphereSchema schema, final ConfigurationProperties props, final Collection<ShardingSphereRule> rules) {
        this.schema = schema;
        this.props = props;
        /**
         * 根据rule配置的类型去spi查找SQLRewriteContextDecorator是实现类
         * sharing的话就是
         */
        decorators = OrderedSPIRegistry.getRegisteredServices(rules, SQLRewriteContextDecorator.class);
    }
    
    public SQLRewriteResult rewrite(final String sql, final List<Object> parameters, final SQLStatementContext<?> sqlStatementContext, final RouteContext routeContext) {
        //创建SQLRewriteContext
        SQLRewriteContext sqlRewriteContext = createSQLRewriteContext(sql, parameters, sqlStatementContext, routeContext);
        //如果路由只命中一个库，直接使用GenericSQLRewriteEngine来生成重写的sql，否则使用RouteSQLRewriteEngine，给每个命中的路由单元生成重写的sql
        return routeContext.getRouteUnits().isEmpty()
                ? new GenericSQLRewriteEngine().rewrite(sqlRewriteContext) : new RouteSQLRewriteEngine().rewrite(sqlRewriteContext, routeContext);
    }
    
    private SQLRewriteContext createSQLRewriteContext(final String sql, final List<Object> parameters, final SQLStatementContext<?> sqlStatementContext, final RouteContext routeContext) {
        SQLRewriteContext result = new SQLRewriteContext(schema, sqlStatementContext, sql, parameters);
        //装饰模式，sharding会在这里进行参数改写
        decorate(decorators, result, routeContext);
        //遍历上面生成的SQLTokenGenerators，生成SQLToken,SQLToken记录了需要改写的位置
        result.generateSQLTokens();
        return result;
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void decorate(final Map<ShardingSphereRule, SQLRewriteContextDecorator> decorators, final SQLRewriteContext sqlRewriteContext, final RouteContext routeContext) {
        decorators.forEach((key, value) -> value.decorate(key, props, sqlRewriteContext, routeContext));
    }
}
```

这里设计了装饰器模式，并且支持SPI扩展，默认有：

- EncryptSQLRewriteContextDecorator(加密)
- ShadowSQLRewriteContextDecorator(影子库)
- ShardingSQLRewriteContextDecorator(分片)



我们来看一下分片的装饰器做了哪些工作



### 3、ShardingSQLRewriteContextDecorator

```java
@Setter
public final class ShardingSQLRewriteContextDecorator implements SQLRewriteContextDecorator<ShardingRule> {
    
    @SuppressWarnings("unchecked")
    @Override
    public void decorate(final ShardingRule shardingRule, final ConfigurationProperties props, final SQLRewriteContext sqlRewriteContext, final RouteContext routeContext) {
        if (routeContext.isFederated()) {
            return;
        }
        for (ParameterRewriter each : new ShardingParameterRewriterBuilder(shardingRule, routeContext).getParameterRewriters(sqlRewriteContext.getSchema())) {
            //如果参数数量>0, 有分页、自动生成主键等 则进行参数改写
            if (!sqlRewriteContext.getParameters().isEmpty() && each.isNeedRewrite(sqlRewriteContext.getSqlStatementContext())) {
                each.rewrite(sqlRewriteContext.getParameterBuilder(), sqlRewriteContext.getSqlStatementContext(), sqlRewriteContext.getParameters());
            }
        }
        //添加分片的改写规则生成器SQLTokenGenerators
        sqlRewriteContext.addSQLTokenGenerators(new ShardingTokenGenerateBuilder(shardingRule, routeContext).getSQLTokenGenerators());
    }
    
    @Override
    public int getOrder() {
        return ShardingOrder.ORDER;
    }
    
    @Override
    public Class<ShardingRule> getTypeClass() {
        return ShardingRule.class;
    }
}
```

通过分片装饰器装饰器我们看到：

首先**判断参数是否需要改写**，分库下的分页、主键生成是需要参数是需要改写的

然后**添加SQLTokenGenerators**，这个是生成SQLToken的关键



装饰器执行完成后，回到SQLRewriteEntry

然后**通过SQLTokenGenerator生成SQLToken**，由各种类型的SQLToken#toString方法判断不同的改写规则



我们以TableToken为例,，根据逻辑表名改写成真实表名：

```sql
SELECT order_id FROM t_order WHERE order_id=1;
```

改成

```sql
SELECT order_id FROM t_order_1 WHERE order_id=1;
```

下面我们看一下是如何做到的



### 4、TableToken

> SQLToken会记录改写的开始结束位置，在这个位置进行替换，替换成改写好的部分，TableToken的话就是真正的数据库名.真正的表名

```java
public final class TableToken extends SQLToken implements Substitutable, RouteUnitAware {
    
    //sql改写结束位置，通过开始-结束位置动态拼接字符串达到sql改写，startIndex定义在SQLToken
    @Getter
    private final int stopIndex;
    
    private final IdentifierValue tableName;
    
    private final IdentifierValue owner;
    
    private final SQLStatementContext sqlStatementContext;
    
    private final ShardingRule shardingRule;
    
    public TableToken(final int startIndex, final int stopIndex, final SimpleTableSegment tableSegment, final SQLStatementContext sqlStatementContext, final ShardingRule shardingRule) {
        super(startIndex);
        this.stopIndex = stopIndex;
        //sql直接写的表名
        tableName = tableSegment.getTableName().getIdentifier();
        this.sqlStatementContext = sqlStatementContext;
        //sql直接写的表名的数据库名，像demo_ds.t_order
        owner = tableSegment.getOwner().isPresent() ? tableSegment.getOwner().get().getIdentifier() : null;
        this.shardingRule = shardingRule;
    }
    
    @Override
    public String toString(final RouteUnit routeUnit) {
        //传入路由单元，根据该路由单元拿到真实表名
        String actualTableName = getLogicAndActualTables(routeUnit).get(tableName.getValue().toLowerCase());
        //如果真实表名找不到，则使用小写解析sql的表名
        actualTableName = null == actualTableName ? tableName.getValue().toLowerCase() : actualTableName;
        //查找真正的数据库名
        String owner = "";
        //如果sql里面写了逻辑数据库名，并且路由单元有匹配的逻辑数据库名，则进行改写
        if (null != this.owner && routeUnit.getDataSourceMapper().getLogicName().equals(this.owner.getValue())) {
            owner = this.owner.getQuoteCharacter().wrap(routeUnit.getDataSourceMapper().getActualName()) + ".";
        }
        //最后返回该路由单元下：真正的数据库名.真正的表名
        return Joiner.on("").join(owner, tableName.getQuoteCharacter().wrap(actualTableName));
    }
    
    private Map<String, String> getLogicAndActualTables(final RouteUnit routeUnit) {
        Collection<String> tableNames = sqlStatementContext.getTablesContext().getTableNames();
        Map<String, String> result = new HashMap<>(tableNames.size(), 1);
        for (RouteMapper each : routeUnit.getTableMappers()) {
            result.put(each.getLogicName().toLowerCase(), each.getActualName());
            result.putAll(shardingRule.getLogicAndActualTablesFromBindingTable(routeUnit.getDataSourceMapper().getLogicName(), each.getLogicName(), each.getActualName(), tableNames));
        }
        return result;
    }
}
```

其他feature或其他规则的sql改写细节这里就不细说了，数量众多，且是个体力活，有机会再慢慢探讨...

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/1630229516(1).jpg)


>E:\opensource\shardingsphere\docs\document\content\features\sharding\principle\rewrite.cn.md
