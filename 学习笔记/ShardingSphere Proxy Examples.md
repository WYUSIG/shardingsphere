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

复制该模块下面的resources/conf/config-sharding.yaml文件

打开shardingsphere-proxy -> shardingsphere-proxy-bootstrapt

把刚刚复制的config-sharding.yaml文件到shardingsphere-proxy-bootstrapt模块的resources/conf目录中

#### 2.2、分库分表规则

分析上面我们复制的config-sharding.yaml文件配置

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

#### 2.4、在example删除数据处打上断点

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

在org.apache.shardingsphere.example.core.mybatis.service.OrderServiceImpl#processSuccess方法的deleteData(orderIds);处打上断点，如图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824182234.png)

#### 2.5、运行proxy分库分表客户端示例

debug启动SpringBootStarterExample，待代码运行到断点

#### 2.6、运行效果


### 3、proxy读写示例

#### 3.1、准备配置文件

打开shardingsphere-proxy -> shardingsphere-proxy-bootstrapt，删除之前分库分表示例的conf/config-sharding.yaml文件

打开examples->shardingsphere-proxy-example->shardingsphere-proxy-boot-mybatis-example

复制该模块下面的resources/conf/config-readwrite-splitting.yaml文件

打开shardingsphere-proxy -> shardingsphere-proxy-bootstrapt

把刚刚复制的config-readwrite-splitting.yaml文件到shardingsphere-proxy-bootstrapt模块的resources/conf目录中

#### 3.2、读写分离配置规则

新增打印sql：

```yaml
props:
  sql-show: true
```

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

#### 3.3、给伪从库初始数据

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

#### 3.4、运行观察日志

```
[ShardingSphere-SQL] Actual SQL: write_ds ::: CREATE TABLE IF NOT EXISTS t_order (order_id BIGINT NOT NULL AUTO_INCREMENT, user_id INT NOT NULL, address_id BIGINT NOT NULL, status VARCHAR(50), PRIMARY KEY (order_id)) 
[ShardingSphere-SQL] Actual SQL: write_ds ::: INSERT INTO t_address (address_id, address_name) VALUES (?, ?) ::: [0, address_0] 
[ShardingSphere-SQL] Actual SQL: read_ds_0 ::: SELECT * FROM t_order 
[ShardingSphere-SQL] Actual SQL: read_ds_1 ::: SELECT * FROM t_order_item
```

从挑选的几段日志可以看出，创建表和插入数据全部命中write_ds，而查询则命中read_ds_0或read_ds_1，同时具有负载均衡功能


### 4、proxy加密示例

#### 4.1、准备配置文件

打开shardingsphere-proxy -> shardingsphere-proxy-bootstrapt，删除之前分库分表示例的conf/config-readwrite-splitting.yaml文件

复制之前备份的配置文件conf_tmp/config-encrypt.yaml文件，粘贴到conf目录

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
  encryptors:
    aes_encryptor:
      type: AES
      props:
        aes-key-value: 123456abc
    md5_encryptor:
      type: MD5
  tables:
    t_encrypt:
      columns:
        user_id:
          plainColumn: user_plain
          cipherColumn: user_cipher
          encryptorName: aes_encryptor
        order_id:
          cipherColumn: order_cipher
          encryptorName: md5_encryptor
```

可以看到ShardingSphere Proxy模拟出一个供客户端连接的数据库：encrypt_db。


