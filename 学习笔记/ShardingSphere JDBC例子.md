## ShardingSphere源码学习-5.0.0-beta



### 1、获取源码



#### 1.1、源码地址

>https://github.com/apache/shardingsphere.git



#### 1.2、fork出一个仓库

fork 这个上面的源码仓库，并在5.0.0-beta-release 这个分支上创建一个自己的学习分支

>因为5.0.0-beta-release分支已被删除，所以可以直接在主分支上创建学习分支

>?为什么需要fork和建立自己的分支呢？
>因为我们在源码学习过程中会debug源码，会写一些自己的理解、注释，甚至修改代码、提pr等等，因此fork一个自己的仓库也是很有必要的



#### 1.3、clone和添加upstream

clone fork后的仓库到本地，给该仓库添加upstream(源apache项目的地址)，这会方便我们后续更新最新代码和提交pr



#### 1.4、打开项目

idea打开项目，并等待下载好依赖，执行下方命令

```
mvn install -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
```

> 执行过程有一点漫长，需要耐心等待



#### 1.5、添加examples

 通过idea右边maven +号，把examples目录导进来，因为5.0.0-beta的examples pom没有编写好，我们需要对examples根目录pom文件进行修改

```
<version>5.0.0-alpha</version>
```

>如果不是5.0.0-beta则不需要修改

修改后再次刷新maven即可



### 2、ShardingSphere jdbc与加密 示例



#### 2.1、初始化示例数据库

在examples/src/resources目录下，我们可以看到一个manual_schema.sql的文件，执行该数据库脚本后，即可完成示例数据库的初始化

>推荐使用本地mysql，3306端口，用户名root，无密码，这样就和源码数据源配置相同




#### 2.2、examples的目录示例

| 例子 | 描述 |
|--------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| [分片](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/shardingsphere-jdbc-example/sharding-example)                                                    | 演示通过 ShardingSphere-JDBC 进行分库、分表、主从等      |
| [springboot jpa](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/shardingsphere-jdbc-example/sharding-example/sharding-spring-boot-jpa-example)         | 演示通过 SpringBoot JPA 对接 ShardingSphere-JDBC      |
| [springboot mybatis](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/shardingsphere-jdbc-example/sharding-example/sharding-spring-boot-mybatis-example) | 演示通过 SpringBoot Mybatis 对接 ShardingSphere-JDBC  |
| [治理](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/shardingsphere-jdbc-example/governance-example)                                                  | 演示在 ShardingSphere-JDBC 中使用治理                  |
| [事务](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/shardingsphere-jdbc-example/transaction-example)                                                 | 演示在 ShardingSphere-JDBC 中使用事务                  |
| [hint](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/shardingsphere-jdbc-example/other-feature-example/hint-example)                                  | 演示在 ShardingSphere-JDBC 中使用 hint                |
| [加密](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/shardingsphere-jdbc-example/other-feature-example/encrypt-example)                               | 演示在 ShardingSphere-JDBC 中使用加密                  |
| APM监控(Pending)                                                                                        | 演示在 ShardingSphere 中使用 APM 监控                  |
| proxy(Pending)                                                                                          | 演示使用 ShardingSphere-Proxy                         |
| [docker](https://github.com/apache/shardingsphere/tree/5.0.0-beta/examples/docker/docker-compose.md)     | |



#### 2.3、分库示例

##### 2.3.1、打开examples->sharding-example->sharding-raw-jdbc-example

##### 2.3.2、选择JavaConfigurationExampleMain类，属性shardingType选择ShardingType.SHARDING_DATABASES

##### 2.3.3、在org.apache.shardingsphere.example.core.jdbc.service.OrderServiceImpl#processSuccess方法的deleteData(orderIds);打上断点，如图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210822235058.png)

##### 2.3.4、分库规则

```java
public final class ShardingDatabasesConfigurationPrecise implements ExampleConfiguration{
    private ShardingRuleConfiguration createShardingRuleConfiguration() {
        ShardingRuleConfiguration result = new ShardingRuleConfiguration();
        //设置各个表的一些规则
        result.getTables().add(getOrderTableRuleConfiguration());
        result.getTables().add(getOrderItemTableRuleConfiguration());
        //广播表
        result.getBroadcastTables().add("t_address");
        //分库所依赖的列，分库算法名称
        result.setDefaultDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "inline"));
        Properties props = new Properties();
        //分库规则表达式
        props.setProperty("algorithm-expression", "demo_ds_${user_id % 2}");
        //设置inline这个分库算法的分库规则
        result.getShardingAlgorithms() .put("inline", new ShardingSphereAlgorithmConfiguration("INLINE", props));
        //设置雪花算法
        result.getKeyGenerators().put("snowflake", new ShardingSphereAlgorithmConfiguration("SNOWFLAKE", getProperties()));
        return result;
    }
}
```

从props.setProperty("algorithm-expression", "demo_ds_${user_id % 2}");这句代码可以看到，是按user_id对2取模来确定放到哪个库。

##### 2.3.5、debug运行JavaConfigurationExampleMain类，待断点拦截到时，观察数据库

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210822235431.png)

可以看到，user_id为奇数的，进入demo_ds_1数据库，user_id为偶数的进入了demo_ds_0数据库。

##### 2.3.6、释放断点，执行完毕自动删除数据



#### 2.4、分表示例

##### 2.4.1、打开examples->sharding-example->sharding-raw-jdbc-example

##### 2.4.2、选择YamlConfigurationExampleMain类，属性shardingType选择ShardingType.SHARDING_TABLES

##### 2.4.3、在org.apache.shardingsphere.example.core.jdbc.service.OrderServiceImpl#processSuccess方法的deleteData(orderIds);打上断点，如图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210822235058.png)

##### 2.4.4、分表规则

sharding-tables.yaml文件

```yaml
dataSources:
  ds:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/demo_ds?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8
    username: root
    password:

rules:
- !SHARDING
  tables:
    t_order: 
      actualDataNodes: ds.t_order_${0..1}
      tableStrategy: 
        standard:
          shardingColumn: order_id
          shardingAlgorithmName: t_order_inline
      keyGenerateStrategy:
        column: order_id
        keyGeneratorName: snowflake
    t_order_item:
      actualDataNodes: ds.t_order_item_${0..1}
      tableStrategy:
        standard:
          shardingColumn: order_id
          shardingAlgorithmName: t_order_item_inline
      keyGenerateStrategy:
        column: order_item_id
        keyGeneratorName: snowflake
  bindingTables:
    - t_order,t_order_item
  broadcastTables:
    - t_address
  
  shardingAlgorithms:
    t_order_inline:
      type: INLINE
      props:
        algorithm-expression: t_order_${order_id % 2}
    t_order_item_inline:
      type: INLINE
      props:
        algorithm-expression: t_order_item_${order_id % 2}
  
  keyGenerators:
    snowflake:
      type: SNOWFLAKE
      props:
          worker-id: 123
```

可以看到t_order和t_order_item表分别按t_order_${order_id % 2}、t_order_item_${order_id % 2}规则进行数据分表

##### 2.4.5、debug运行YamlConfigurationExampleMain类，待断点拦截到时，观察数据库

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210823012623.png)

因为按order_id进行分表，而生成的order_id都是偶数，所以都被分到了t_order_0、t_order_item_0中



#### 2.5、分库分表示例

##### 2.5.1、打开examples->sharding-example->sharding-raw-jdbc-example

##### 2.5.2、选择YamlConfigurationExampleMain类，属性shardingType选择ShardingType.SHARDING_DATABASES_AND_TABLES

##### 2.4.3、在org.apache.shardingsphere.example.core.jdbc.service.OrderServiceImpl#processSuccess方法的deleteData(orderIds);打上断点，如图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210822235058.png)

##### 2.4.4、分库分表规则

sharding-databases-tables.yaml文件

```yaml
dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/demo_ds_0?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8
    username: root
    password:
  ds_1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/demo_ds_1?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8
    username: root
    password:

rules:
  - !SHARDING
    tables:
      t_order:
        actualDataNodes: ds_${0..1}.t_order_${0..1}
        tableStrategy:
          standard:
            shardingColumn: order_id
            shardingAlgorithmName: t_order_inline
        keyGenerateStrategy:
          column: order_id
          keyGeneratorName: snowflake
      t_order_item:
        actualDataNodes: ds_${0..1}.t_order_item_${0..1}
        tableStrategy:
          standard:
            shardingColumn: order_id
            shardingAlgorithmName: t_order_item_inline
        keyGenerateStrategy:
          column: order_item_id
          keyGeneratorName: snowflake
    bindingTables:
      - t_order,t_order_item
    broadcastTables:
      - t_address
    defaultDatabaseStrategy:
      standard:
        shardingColumn: user_id
        shardingAlgorithmName: database_inline
    defaultTableStrategy:
      none:

    shardingAlgorithms:
      database_inline:
        type: INLINE
        props:
          algorithm-expression: ds_${user_id % 2}
      t_order_inline:
        type: INLINE
        props:
          algorithm-expression: t_order_${order_id % 2}
      t_order_item_inline:
        type: INLINE
        props:
          algorithm-expression: t_order_item_${order_id % 2}

    keyGenerators:
      snowflake:
        type: SNOWFLAKE
        props:
          worker-id: 123
```

可以看到, 默认分库算法是database_inline， 表达式为ds_${user_id % 2}，对user_id按2取模；t_order表的分表算法是t_order_inline， 表达式为t_order_${order_id % 2}，
对order_id按2取模；t_order_item的分表算法是t_order_item_inline，表达式为：t_order_item_${order_id % 2}，对order_id按2取模

##### 2.4.5、debug运行YamlConfigurationExampleMain类，待断点拦截到时，观察数据库

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210823165314.png)

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210823165442.png)

可以看到，t_order和t_order_item数据先按user_id分到demo_ds_0和demo_ds_1上，再按order_id分到t_order_0还是t_order_1,t_order_item_0还是t_order_item_1



#### 2.6、读写分离示例

##### 2.6.1、打开examples->sharding-example->sharding-raw-jdbc-example

##### 2.6.2、选择YamlConfigurationExampleMain类，属性shardingType选择ShardingType.READWRITE_SPLITTING

##### 2.6.3、配置文件打开sql日志输出

```yaml
dataSources:
  write_ds:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/demo_write_ds?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8
    username: root
    password:
  read_ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/demo_read_ds_0?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8
    username: root
    password:
  read_ds_1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/demo_read_ds_1?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8
    username: root
    password:

rules:
- !READWRITE_SPLITTING
  dataSources:
    pr_ds:
      writeDataSourceName: write_ds
      readDataSourceNames: [read_ds_0, read_ds_1]

props:
  sql-show: true
```

##### 2.6.4、给伪从库初始化数据

因为该演示只使用一个数据库，所以从库没有配置主从同步，我们需要给从库建表并插入数据，否则等会延迟查询语句会报找不到表, 分别在demo_read_ds_0和demo_read_ds_1数据库执行下列语句

```sql
create table t_address
(
    address_id   bigint       not null
        primary key,
    address_name varchar(100) not null
);

INSERT INTO t_address (address_id, address_name) VALUES (0, 'address_0');
INSERT INTO t_address (address_id, address_name) VALUES (1, 'address_1');
INSERT INTO t_address (address_id, address_name) VALUES (2, 'address_2');
INSERT INTO t_address (address_id, address_name) VALUES (3, 'address_3');
INSERT INTO t_address (address_id, address_name) VALUES (4, 'address_4');
INSERT INTO t_address (address_id, address_name) VALUES (5, 'address_5');
INSERT INTO t_address (address_id, address_name) VALUES (6, 'address_6');
INSERT INTO t_address (address_id, address_name) VALUES (7, 'address_7');
INSERT INTO t_address (address_id, address_name) VALUES (8, 'address_8');
INSERT INTO t_address (address_id, address_name) VALUES (9, 'address_9');

create table t_order
(
    order_id   bigint auto_increment
        primary key,
    user_id    int         not null,
    address_id bigint      not null,
    status     varchar(50) null
);

INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (1, 1, 1, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (2, 2, 2, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (3, 3, 3, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (4, 4, 4, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (5, 5, 5, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (6, 6, 6, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (7, 7, 7, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (8, 8, 8, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (9, 9, 9, 'INSERT_TEST');
INSERT INTO t_order (order_id, user_id, address_id, status) VALUES (10, 10, 10, 'INSERT_TEST');

create table t_order_item
(
    order_item_id bigint auto_increment
        primary key,
    order_id      bigint      not null,
    user_id       int         not null,
    status        varchar(50) null
);

INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (1, 1, 1, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (2, 2, 2, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (3, 3, 3, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (4, 4, 4, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (5, 5, 5, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (6, 6, 6, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (7, 7, 7, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (8, 8, 8, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (9, 9, 9, 'INSERT_TEST');
INSERT INTO t_order_item (order_item_id, order_id, user_id, status) VALUES (10, 10, 10, 'INSERT_TEST');
```

##### 2.6.5、运行观察日志

```
[ShardingSphere-SQL] Actual SQL: write_ds ::: CREATE TABLE IF NOT EXISTS t_order (order_id BIGINT NOT NULL AUTO_INCREMENT, user_id INT NOT NULL, address_id BIGINT NOT NULL, status VARCHAR(50), PRIMARY KEY (order_id)) 
[ShardingSphere-SQL] Actual SQL: write_ds ::: INSERT INTO t_address (address_id, address_name) VALUES (?, ?) ::: [0, address_0] 
[ShardingSphere-SQL] Actual SQL: read_ds_0 ::: SELECT * FROM t_order 
[ShardingSphere-SQL] Actual SQL: read_ds_1 ::: SELECT * FROM t_order_item
```

从挑选的几段日志可以看出，创建表和插入数据全部命中write_ds，而查询则命中read_ds_0或read_ds_1，同时具有负载均衡功能



#### 2.7、加密示例

##### 2.7.1、打开examples->sharding-example->other-feature-example->encrypt-example->encrypt-raw-jdbc-example

##### 2.7.2、选择YamlConfigurationExampleMain类


##### 2.7.3、配置文件

```yaml
dataSources:
  unique_ds:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/demo_ds?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8
    username: root
    password:

rules:
- !ENCRYPT
  tables:
    t_user:
      columns:
        user_name:
          plainColumn: user_name_plain
          cipherColumn: user_name
          encryptorName: name_encryptor
        pwd:
          cipherColumn: pwd
          assistedQueryColumn: assisted_query_pwd
          encryptorName: pwd_encryptor
  encryptors:
    name_encryptor:
      type: AES
      props:
        aes-key-value: 123456abc
    pwd_encryptor:
      type: assistedTest
```

可以看出，t_user表的user_name才有AES加密，

##### 2.7.4、运行效果

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210823192346.png)







