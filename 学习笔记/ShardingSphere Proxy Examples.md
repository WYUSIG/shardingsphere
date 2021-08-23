## ShardingSphere源码学习-5.0.0-beta

### 1、运行源码中的proxy

#### 1.1(可选)、启动zk

>我使用的是3.6.2版本，端口为默认的2181
> 
> 可选：即不启动zk也可以

#### 打开example

打开examples->shardingsphere-proxy-example->shardingsphere-proxy-boot-mybatis-example

复制该模块下面的resources/conf整个文件夹

#### 备份proxy原有配置文件

打开shardingsphere-proxy -> shardingsphere-proxy-bootstrapt

把该模块原有conf文件备份到一个conf_tmp文件夹，如图所示：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824005412.png)

#### 启动proxy

删除shardingsphere-proxy-bootstrapt resources/conf文件夹，同时粘贴之前复制example的conf文件夹

运行 Bootstrap 类

>在conf目录下server.yaml配置文件可以看到governance.registryCenter,该注册中心配置，如果没有启动zk，请注释掉该配置

运行成功效果如下图：

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20210824005829.png)


