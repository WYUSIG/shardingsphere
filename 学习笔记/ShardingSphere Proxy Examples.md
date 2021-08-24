## ShardingSphere源码学习-5.0.0-beta

### 1、运行源码中的proxy

#### 1.1(可选)、启动zk

>我使用的是3.6.2版本，端口为默认的2181
> 
> 可选：即不启动zk也可以

#### 打开example

打开examples->shardingsphere-proxy-example->shardingsphere-proxy-boot-mybatis-example

复制该模块下面的resources/conf/server.yaml文件

#### 备份proxy原有配置文件

打开shardingsphere-proxy -> shardingsphere-proxy-bootstrapt

把该模块原有conf文件备份到一个conf_tmp文件夹，如图所示：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824005412.png)

#### 启动proxy

删除shardingsphere-proxy-bootstrapt resources/conf里面的文件，同时粘贴之前复制example的server.yaml文件到conf文件夹

运行 Bootstrap 类

>Note: 在conf目录下server.yaml配置文件可以看到governance.registryCenter,该注册中心配置，如果没有启动zk，请注释掉该配置

运行成功效果如下图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824005829.png)

### 2、proxy分库分表示例

#### 2.1、准备分库分表配置文件

打开examples->shardingsphere-proxy-example->shardingsphere-proxy-boot-mybatis-example

复制该模块下面的resources/conf文件夹

打开shardingsphere-proxy -> shardingsphere-proxy-bootstrapt

把刚刚复制的文件夹粘贴到到shardingsphere-proxy-bootstrapt模块的resources目录中

#### 2.2、分库分表规则

分析上面我们粘贴的config-sharding.yaml文件配置

```yaml
schemaName: sharding_db

dataSources:
  ds_0:
    url: jdbc:mysql://127.0.0.1:3306/demo_ds_0?serverTimezone=UTC&useSSL=false
    username: root
    password:
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
    maintenanceIntervalMilliseconds: 30000
  ds_1:
    url: jdbc:mysql://127.0.0.1:3306/demo_ds_1?serverTimezone=UTC&useSSL=false
    username: root
    password:
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
    maintenanceIntervalMilliseconds: 30000

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

可以看到ShardingSphere Proxy模拟出一个供客户端连接的数据库：sharding_db。

<i>分库规则:</i>

在defaultDatabaseStrategy配置项可以看到，分库所依赖列为user_id，分库算法为database_inline，

从shardingAlgorithms.database_inline中可以看出，ds_${user_id % 2}，按照user_id对2取模进行分库。

<i>分表规则:</i>

i: 在rules.tables.t_order.tableStrategy配置项可以看到，t_order分表所依赖列为order_id，分表算法为t_order_inline，

从rules.shardingAlgorithms.t_order_inline配置项中看到t_order_${order_id % 2}，按照order_id对2取模进行分表。

ii: 在rules.tables.t_order_item.tableStrategy配置项可以看到，t_order_item分表所依赖列为order_id，分表算法为t_order_item_inline，

从rules.shardingAlgorithms.t_order_item_inline配置项中看到t_order_item_${order_id % 2}，按照order_id对2取模进行分表。

#### 2.3、重新运行Proxy

删除shardingsphere-proxy -> shardingsphere-proxy-bootstrapt的target文件夹

重新运行Bootstrap类

#### 2.4、在example删除数据处打上断点及去掉事务注解

打开examples->shardingsphere-proxy-example->shardingsphere-proxy-boot-mybatis-example

从下面该段代码可以看出，会依赖查找ExampleService类型的主bean
```java
private static ExampleService getExampleService(final ConfigurableApplicationContext applicationContext) {
    return applicationContext.getBean(ExampleService.class);
}
```

而它的主bean就是标注了Primary的OrderServiceImpl

```java
package org.apache.shardingsphere.example.core.mybatis.service;

@Service
@Primary
public class OrderServiceImpl implements ExampleService {
    ...
}
```

在org.apache.shardingsphere.example.core.mybatis.service.OrderServiceImpl#processSuccess方法的deleteData(orderIds);处打上断点，同时去掉事务注解
如图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824223524.png)

#### 2.5、运行proxy分库分表客户端示例

debug启动SpringBootStarterExample，待代码运行到断点

#### 2.6、运行效果

观察数据库demo_ds_0和demo_ds_1

demo_ds_0数据情况：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824224926.png)

demo_ds_1运行情况：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824225142.png)

demo_ds_1

可以看到数据先按user_id分到不同库，再按order_id分到不同表


### 3、proxy读写示例

#### 3.1、准备配置文件

因为我们之前复制的shardingsphere-proxy-boot-mybatis-example模块的conf文件夹已经包含了读写分离配置文件：config-readwrite-splitting.yaml

另外我们需要打开proxy打印sql日志开关，方便我们观察sql会命中哪个库

修改proxy的server.yaml:

```yaml
rules:
  - !AUTHORITY
    users:
      - root@:root
      - sharding@:sharding
    provider:
      type: NATIVE

props:
  max-connections-size-per-query: 1
  executor-size: 16  # Infinite by default.
  proxy-frontend-flush-threshold: 128  # The default value is 128.
    # LOCAL: Proxy will run with LOCAL transaction.
    # XA: Proxy will run with XA transaction.
    # BASE: Proxy will run with B.A.S.E transaction.
  proxy-transaction-type: LOCAL
  proxy-opentracing-enabled: false
  proxy-hint-enabled: false
  sql-show: true
  check-table-metadata-enabled: false
```

就是props.sql-show改成true

#### 3.2、重新运行proxy

因为我们上一步修改了server.yaml配置文件，删除proxy的target目录，重新运行

#### 3.3、读写分离配置规则

```yaml
schemaName: readwrite-splitting_db

dataSources:
  write_ds:
    url: jdbc:mysql://127.0.0.1:3306/demo_write_ds?serverTimezone=UTC&useSSL=false
    username: root
    password:
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
    maintenanceIntervalMilliseconds: 30000
  read_ds_0:
    url: jdbc:mysql://127.0.0.1:3306/demo_read_ds_0?serverTimezone=UTC&useSSL=false
    username: root
    password:
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
    maintenanceIntervalMilliseconds: 30000
  read_ds_1:
    url: jdbc:mysql://127.0.0.1:3306/demo_read_ds_1?serverTimezone=UTC&useSSL=false
    username: root
    password:
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
    maintenanceIntervalMilliseconds: 30000

rules:
- !READWRITE_SPLITTING
  dataSources:
    pr_ds:
      writeDataSourceName: write_ds
      readDataSourceNames:
        - read_ds_0
        - read_ds_1
props:
  sql-show: true
```

可以看到ShardingSphere Proxy模拟出一个供客户端连接的数据库：readwrite-splitting_db。

写库为：write_ds

读库为：read_ds_0、read_ds_1

#### 3.4、给伪从库初始数据

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

#### 3.5、修改示例的数据源

打开shardingsphere-proxy-boot-mybatis-example

修改application.property文件：

```yaml
mybatis.config-location=classpath:META-INF/mybatis-config.xml

spring.datasource.type=com.zaxxer.hikari.HikariDataSource
spring.datasource.driver-class-name=com.mysql.jdbc.Driver
spring.datasource.url=jdbc:mysql://localhost:3307/readwrite-splitting_db?useServerPrepStmts=true&cachePrepStmts=true
spring.datasource.username=root
spring.datasource.password=root
```

就是把sharding_db改成readwrite-splitting_db

#### 3.5、运行观察日志

运行shardingsphere-proxy-boot-mybatis-example

```
[ShardingSphere-Command-3] ShardingSphere-SQL - Actual SQL: write_ds ::: CREATE TABLE IF NOT EXISTS t_order (order_id BIGINT NOT NULL AUTO_INCREMENT, user_id INT NOT NULL, address_id BIGINT NOT NULL, status VARCHAR(50), PRIMARY KEY (order_id)) 
[ShardingSphere-Command-3] ShardingSphere-SQL - Actual SQL: write_ds ::: INSERT INTO t_address (address_id, address_name) VALUES (?, ?) ::: [0, address_0] 
[ShardingSphere-Command-3] ShardingSphere-SQL - Actual SQL: read_ds_0 ::: SELECT * FROM t_order 
[ShardingSphere-Command-4] ShardingSphere-SQL - Actual SQL: read_ds_1 ::: SELECT * FROM t_order_item
[ShardingSphere-Command-3] ShardingSphere-SQL - Actual SQL: write_ds ::: DROP TABLE IF EXISTS t_order_item;
```

从挑选的几段proxy日志可以看出，创建表和插入数据全部命中write_ds，而查询则命中read_ds_0或read_ds_1，同时具有负载均衡功能


### 4、proxy加密示例

#### 4.1、准备配置文件

在proxy conf目录下新建config-encrypt.yaml文件

#### 4.2、加密配置

```yaml
schemaName: encrypt_db

dataSource:
  url: jdbc:mysql://127.0.0.1:3306/demo_ds?serverTimezone=UTC&useSSL=false
  username: root
  password:
  connectionTimeoutMilliseconds: 30000
  idleTimeoutMilliseconds: 60000
  maxLifetimeMilliseconds: 1800000
  maxPoolSize: 50
  minPoolSize: 1
  maintenanceIntervalMilliseconds: 30000

rules:
  - !ENCRYPT
    tables:
      t_user:
        columns:
          user_name:
            cipherColumn: user_name
            encryptorName: aes_encryptor
          pwd:
            cipherColumn: pwd
            encryptorName: md5_encryptor
    encryptors:
      aes_encryptor:
        type: AES
        props:
          aes-key-value: 123456abc
      md5_encryptor:
        type: MD5
```

可以看到ShardingSphere Proxy模拟出一个供客户端连接的数据库：encrypt_db。

同时对t_user表的user_id进行AES加密，pwd进行md5加密

#### 4.3、修改示例的数据源

打开shardingsphere-proxy-boot-mybatis-example

修改application.property文件：

```yaml
mybatis.config-location=classpath:META-INF/mybatis-config.xml

spring.datasource.type=com.zaxxer.hikari.HikariDataSource
spring.datasource.driver-class-name=com.mysql.jdbc.Driver
spring.datasource.url=jdbc:mysql://localhost:3307/encrypt_db?useServerPrepStmts=true&cachePrepStmts=true
spring.datasource.username=root
spring.datasource.password=root
```

就是把readwrite-splitting_db改成encrypt_db

#### 4.4、修改example的依赖查找的ExampleService

打开shardingsphere-proxy-boot-mybatis-example

把getExampleService方法改成以下代码：

```java
private static ExampleService getExampleService(final ConfigurableApplicationContext applicationContext) {
//        return applicationContext.getBean(ExampleService.class);
    return applicationContext.getBean("encrypt", ExampleService.class);
}
```

同时修改resources/META-INF/mybatis-config.xml

```xml
<!DOCTYPE configuration
        PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-config.dtd">
<configuration>
    <mappers>
        <mapper resource="META-INF/mappers/AddressMapper.xml"/>
        <mapper resource="META-INF/mappers/OrderMapper.xml"/>
        <mapper resource="META-INF/mappers/OrderItemMapper.xml"/>
        <mapper resource="META-INF/mappers/UserMapper.xml"/>
    </mappers>
</configuration>
```

新增UserMapper.xml

#### 4.4、在example删除数据处打上断点及去掉事务注解

打开examples->shardingsphere-proxy-example->shardingsphere-proxy-boot-mybatis-example

在org.apache.shardingsphere.example.core.mybatis.service.UserServiceImpl#processSuccess方法的deleteData(userIds);处打上断点，
如图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825002255.png)

#### 4.5、重新运行proxy

因为我们新增了配置文件，删除proxy的target目录，重新运行。

#### 4.6、运行示例程序

debug运行shardingsphere-proxy-boot-mybatis-example，待代码运行到断点，观察数据库

#### 4.7、运行效果

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825002334.png)



