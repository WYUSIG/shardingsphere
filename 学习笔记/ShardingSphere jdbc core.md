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

