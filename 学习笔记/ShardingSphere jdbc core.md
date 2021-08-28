## ShardingSphere源码学习-5.0.0-beta

### 前言

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

#### 普通java api创建ShardingSphereDataSource

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

#### yaml文件创建ShardingSphereDataSource

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

### ShardingSphereDataSource

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
     * 分布式事务上下文
     */
    private final TransactionContexts transactionContexts;

    public ShardingSphereDataSource(final Map<String, DataSource> dataSourceMap, final Collection<RuleConfiguration> configurations, final Properties props) throws SQLException {
        //使用DataSources、Rules、props构造出元信息上下文
        metaDataContexts = new MetaDataContextsBuilder(
                Collections.singletonMap(DefaultSchema.LOGIC_NAME, dataSourceMap), Collections.singletonMap(DefaultSchema.LOGIC_NAME, configurations), props).build();
        //从元信息上下文获取xa分布式事务类型(有Atomikos、Narayana、Bitronix)
        String xaTransactionMangerType = metaDataContexts.getProps().getValue(ConfigurationPropertyKey.XA_TRANSACTION_MANAGER_TYPE);
        //构造出分布式事务上下文
        transactionContexts = createTransactionContexts(metaDataContexts.getDefaultMetaData().getResource().getDatabaseType(), dataSourceMap, xaTransactionMangerType);
    }

    /**
     * 构造出分布式事务上下文
     * @param databaseType 数据库类型
     * @param dataSourceMap 所有数据库
     * @param xaTransactionMangerType xa分布式事务类型，有Atomikos、Narayana、Bitronix
     * @return 分布式事务上下文
     */
    private TransactionContexts createTransactionContexts(final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap, final String xaTransactionMangerType) {
        ShardingTransactionManagerEngine engine = new ShardingTransactionManagerEngine();
        engine.init(databaseType, dataSourceMap, xaTransactionMangerType);
        return new StandardTransactionContexts(Collections.singletonMap(DefaultSchema.LOGIC_NAME, engine));
    }

    /**
     * 实现jdbc的getConnection方法
     * @return 返回一个ShardingSphereConnection
     * 我们可以看到，把所有数据库、元信息上下文、分布式事务上下文等继续传到下一层Connection
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

上面已经写了比较完整的注释，可以看到ShardingSphereDataSource主要就是构建元信息上下文和分布式事务上下文，

并且实现jdbc DataSource的抽象方法getConnection和close，其中getConnection使用元信息上下文、分布式事务上下文等构造一个ShardingSphereConnection，

毫无疑问，ShardingSphereConnection也是实现了jdbc的Connection；而close就是对管理的各个数据库进行关闭和元信息上下文

其中，元信息上下文MetaDataContexts也有两个实现类，分别是基于注册中心管理的GovernanceMetaDataContexts和基于本地配置的StandardMetaDataContexts

### ShardingSphereConnection

