## ShardingSphere学习-5.0.0-alpha

>因为官方在8月25号前shardingsphere ui只有alpha版本，所以使用alpha版本进行此次示例


### 1、准备环境

#### 1.1、启动zk

启动zookeeper, 使用默认端口2181即可，注意zookeeper启动会占用8080端口

### 1.2、启动mysql

3306端口，root，没有密码

### 1.3、准备shardingsphere proxy 5.0.0-alpha配置文件

#### 1.3.1、基础配置

server.yaml

```yaml
governance:
  name: governance_ds
  registryCenter:
    type: ZooKeeper
    serverLists: localhost:2181
    props:
      retryIntervalMilliseconds: 500
      timeToLiveSeconds: 60
      maxRetries: 3
      operationTimeoutMilliseconds: 500
  overwrite: true

authentication:
  users:
    root:
      password: root
#    sharding:
#      password: sharding 
#      authorizedSchemas: sharding_db

props:
  max-connections-size-per-query: 1
  acceptor-size: 16  # The default value is available processors count * 2.
  executor-size: 16  # Infinite by default.
  proxy-frontend-flush-threshold: 128  # The default value is 128.
    # LOCAL: Proxy will run with LOCAL transaction.
    # XA: Proxy will run with XA transaction.
  # BASE: Proxy will run with B.A.S.E transaction.
  proxy-transaction-type: LOCAL
  proxy-opentracing-enabled: false
  proxy-hint-enabled: false
  query-with-cipher-column: false
  sql-show: true
  check-table-metadata-enabled: false
```

#### 1.3.2、分库分表配置

config-sharding.yaml

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

#### 1.3.3、读写分离配置

config-replica-query.yaml

>Note: 5.0.0alpha的读写分离配置和5.0.0-beta的读写分离配置有较大差异

```yaml
schemaName: readwrite-splitting_db

dataSourceCommon:
 username: root
 password:
 connectionTimeoutMilliseconds: 30000
 idleTimeoutMilliseconds: 60000
 maxLifetimeMilliseconds: 1800000
 maxPoolSize: 10
 minPoolSize: 1
 maintenanceIntervalMilliseconds: 30000

dataSources:
 primary_ds:
   url: jdbc:mysql://localhost:3306/demo_write_ds?serverTimezone=UTC&useSSL=false
 replica_ds_0:
   url: jdbc:mysql://localhost:3306/demo_read_ds_0?serverTimezone=UTC&useSSL=false
 replica_ds_1:
   url: jdbc:mysql://localhost:3306/demo_read_ds_1?serverTimezone=UTC&useSSL=false

rules:
- !REPLICA_QUERY
 dataSources:
   pr_ds:
     name: pr_ds
     primaryDataSourceName: primary_ds
     replicaDataSourceNames:
       - replica_ds_0
       - replica_ds_1
```

#### 1.3.4、加密配置

config-encrypt.yaml

````yaml
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
````

### 1.4、启动shardingsphere proxy 5.0.0-alpha

把上面的配置文件放到conf目录后，进入bin目录，点击start.bat启动proxy

### 1.5、启动shardingsphere ui 5.0.0-alpha

进入shardingsphere ui 5.0.0-alpha bin目录，点击start.bat启动

启动成功后访问http://localhost:8088/

账号密码都是admin

### 2、添加注册中心

登录shardingsphere ui后，点击Governance->Registry Center>ADD

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825230416.png)

如上图所示，填好注册中心信息，点击确认。

添加成功后，点击连接按钮

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825230704.png)

连接成功，即可同步各项数据

### 3、Rule Config演示

#### 3.1、Schema

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825231423.png)

点击 Governance-> Rule Config 我们就可以看到有3个数据库, 这是我们之前配置的，proxy模拟出来由客户端连接的数据库

sharding_db是演示分库分表的数据库

readwrite-splitting_db是演示读写分离的数据库

encrypt_db是演示加密的数据库

具体演示可查看之前的proxy examples文章

同时我们可以看到，每个数据库中都有三项配置，rule、datasource、metadata

##### 3.1.1、rule

顾名思义，就是我们配置项的rule部分，可以进行分库分表、读写分离等配置

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825232808.png)

因为shardingsphere基于注册中心进行数据同步，所以我们可以在线修改配置

但是新配置并不会写回配置文件，这一点需要注意

##### 3.3.2、datasource

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825234001.png)

datasource是我们配置项中的数据源配置部分，含有jdbc url、数据库用户名、密码、jdbc连接池各项属性等。

##### 3.3.3、metadata

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825234516.png)

可以查看、编辑配置的数据表列元信息

#### 3.4、Authentication

记录shardingsphere proxy的账号密码，授权数据库

#### 3.5、Props

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210825235206.png)

这里记录了一些shardingsphere proxy配置元信息，比如是否打印sql日志等，可以在这里修改

### 4、Runtime Status

#### 4.1、Service Node

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210826000008.png)

Service Node显示的是在线的proxy实例，因为是从zk同步信息，实例下线可能存在一定的延迟

同时还配置了实例开关Enabled，关闭后客户端那边将无法连接这个proxy

#### 4.2、Replica DataSource Info

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210826000431.png)

显示的是读写分离的从(读)库信息，同时配置了实例开关Enabled，关闭后proxy将不会命中该从库


shardingsphere ui后面还有数据迁移 shardingsphere scaling的管理，这里展示不做介绍展示

//TODO