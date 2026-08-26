# 人工复核数据包(20% = 16 题)

## 复核说明
1. 每题有两个系统的回答(System A 和 System B), **不知道哪个是 Classic/Agentic**
2. 请对每个回答独立打分:
   - **1** = 完全正确(覆盖所有关键事实)
   - **0.5** = 部分正确(覆盖部分事实)
   - **0** = 错误或未回答
3. 然后给出偏好: A / B / TIE
4. 预计时间: 40-60 分钟

---

## review_001 [S_simple_control]

**问题**: 在Nacos集群配置中，健康检查模式设置为哪种协议？

**标准答案**: 

**System A 回答**:
> 在Nacos集群配置中，健康检查模式设置为TCP协议，端口号为9411(即zipkin-server的端口) [14]。可以通过curl命令检查节点健康状态：`curl -X GET 'http://127.0.0.1:8848/nacos/v2/core/cluster/node/self/health'` [10][11]。

**System B 回答**:
> 在Nacos集群配置中，健康检查模式可以设置为TCP协议。当Nacos与Zipkin整合出现Service not found问题时，需要将zipkin-server服务的健康检查模式设置为TCP，端口号为9412[2]。

**你的评分**: A=____  B=____  偏好=____

---

## review_002 [A_multi_doc_compare]

**问题**: 根据Seata的更新日志和RocketMQ的架构说明，Seata在修复控制台模块安全漏洞时是否涉及了RocketMQ的Master-Slave异步复制模式？请结合两组件信息说明。

**标准答案**: 

**System A 回答**:
> 根据提供的片段，无法确定Seata在修复控制台模块安全漏洞时是否涉及了RocketMQ的Master-Slave异步复制模式。片段[3]仅介绍了Seata支持的序列化协议及其安全性风险，未提及控制台模块安全漏洞的修复信息。片段[1][2]描述了RocketMQ的Master-Slave异步复制模式的优缺点，但未与Seata的漏洞修复建立关联。两组件信息在提供的片段中未显示直接联系。

**System B 回答**:
> 知识库中没有相关内容。

**你的评分**: A=____  B=____  偏好=____

---

## review_003 [S_simple_control]

**问题**: Nacos在哪个版本中实现了统一的参数校验逻辑？

**标准答案**: 

**System A 回答**:
> Nacos在2.3.0版本中实现了统一的参数校验逻辑 [1]。在该版本中，Nacos明确了参数校验规则，在服务端实现了统一的参数校验逻辑并添加了参数校验层 [1]。用户可以通过配置`nacos.core.param.check.enabled=true`来开启参数校验功能 [3]。

**System B 回答**:
> Nacos在2.3.0版本中实现了统一的参数校验逻辑[14]。在此之前，Nacos的参数校验逻辑分散，由各类请求的处理方法单独进行校验，难以更改维护，经常出现参数校验的遗漏[14]。从2.3.0版本开始，Nacos明确了参数校验规则，在服务端实现了统一的参数校验逻辑并添加了参数校验层，根据校验规则对客户端向服务端发送的请求进行校验[14]。

**你的评分**: A=____  B=____  偏好=____

---

## review_004 [B_multi_constraint]

**问题**: 在RocketMQ集群中，采用同步双写HA模式，每个Master配置一个Slave。现发现某Master节点宕机后，客户端发送消息的RT明显升高，且部分消息发送失败。同时，运维人员尝试将Slave切换为Master，但未能成功。已知集群中Broker端口为10911，Proxy端口为8081，版本为4.9.4。请分析可能的原因，并给出排查步骤。

**标准答案**: 

**System A 回答**:
> 同步双写HA模式中，Master宕机后客户端发送消息RT升高且部分消息失败，可能原因是主备都写成功才返回成功，Master宕机导致写入操作无法完成 [1]。Slave无法自动切换为主机，这是当前版本的限制 [1]。

排查步骤：
1. 检查Master节点状态，确认是否真正宕机
2. 验证Slave节点是否正常运行，检查其与原Master的同步状态
3. 检查客户端连接配置，确认是否正确连接到Proxy(端口8081) [2][4]
4. 检查Broker日志(端口10911)，查看是否有异常信息
5. 由于版本4.9.4不支持自动切换，需手动进行主备切换 [1]

**System B 回答**:
> 在RocketMQ集群中采用同步双写HA模式时，Master节点宕机后客户端发送消息RT升高且部分消息发送失败，可能是因为主备切换未自动实现，需要人工干预 [12]。排查步骤包括检查`innerClientAuthenticationCredentials`配置是否正确，以及Master/Slave的凭证配置是否匹配 [10]。同时，需确认Broker与Slave配对是否通过指定相同的BrokerName参数实现，Master的BrokerId必须为0，Slave的BrokerId必须大于0 [11]。

**你的评分**: A=____  B=____  偏好=____

---

## review_005 [S_simple_control]

**问题**: Apache RocketMQ 从哪个版本开始支持强制校验消息类型？

**标准答案**: 

**System A 回答**:
> Apache RocketMQ 从5.0版本开始支持强制校验消息类型，即每个主题只允许发送一种消息类型的消息，这样可以更好的运维和管理生产系统，避免混乱 [1][4][7]。为保证向下兼容4.x版本行为，强制校验功能默认开启 [1][4][7][8]。历史版本的SDK不支持强制校验，需要自己保证消息类型一致，建议升级到Apache RocketMQ服务端5.x版本 [2][3][5][6]。

**System B 回答**:
> Apache RocketMQ 从5.0版本开始支持强制校验消息类型 [2][3][4]。强制校验功能默认开启 [2][3][4]。历史版本(4.x和3.x)的SDK不支持强制校验，需要自己保证消息类型一致 [1][5]。

**你的评分**: A=____  B=____  偏好=____

---

## review_006 [A_multi_doc_compare]

**问题**: 在Dubbo服务提供方异步执行和Nacos关闭默认控制台的场景中，两者分别解决了什么问题？请结合两个组件的功能说明。

**标准答案**: 

**System A 回答**:
> Dubbo服务提供方异步执行将阻塞的业务从Dubbo内部线程池切换到业务自定义线程，避免Dubbo线程池的过度占用，有助于避免不同服务间的互相影响，但这并不等同于节省资源或提升RPC响应性能[14]。Nacos关闭默认控制台是为了满足部分公司或用户希望使用统一平台进行Nacos的配置和服务管理，或将控制台鉴权和客户端访问的鉴权分离的需求[19]。

**System B 回答**:
> Dubbo服务提供方异步执行将阻塞的业务从Dubbo内部线程池切换到业务自定义线程，避免Dubbo线程池的过度占用，有助于避免不同服务间的互相影响[3]。Nacos关闭默认控制台允许公司使用统一平台进行配置和服务管理，或将控制台鉴权和客户端访问的鉴权分离[1][2]。在Nacos 2.2.2版本前，无论是否开启鉴权功能，默认控制台都会跳转到登录页，导致用户被误导认为控制台存在鉴权功能，而关闭默认控制台可解决这一问题[4][5]。

**你的评分**: A=____  B=____  偏好=____

---

## review_007 [C_multi_step]

**问题**: 在Seata组件中，若要支持Oracle和PostgreSQL多主键，需要查看哪个issue编号？同时，该组件还支持了哪些数据库相关的功能？请结合支持Oracle timestamp类型和MySQL update join联表更新语法的issue编号，说明这些数据库功能分别对应哪个issue。最后，如果要在Docker环境中部署支持这些功能的Seata，需要满足什么架构条件？

**标准答案**: 

**System A 回答**:
> 支持Oracle和PostgreSQL多主键的issue编号是#4863[1]。Seata组件还支持Oracle timestamp类型(issue编号#4542)[1]和MySQL update join联表更新语法(issue编号#4914)[1]。

在Docker环境中部署支持这些功能的Seata，需要满足高可用架构条件：多个Seata Server使用同一个注册中心和同一个存储中心[4]。同时，支持的环境变量和Docker相同[2]。

**System B 回答**:
> 在Seata组件中，支持Oracle和PostgreSQL多主键需要查看issue编号#4863[14]。该组件还支持Oracle timestamp类型(issue #4542)[14]和MySQL update join联表更新语法(issue #4914)[14]。在Docker环境中部署支持这些功能的Seata时，需要满足的条件包括支持arm64架构的dockerfile(issue #4802)[14]。

**你的评分**: A=____  B=____  偏好=____

---

## review_008 [C_multi_step]

**问题**: 在Seata组件中，若要支持从Nacos单一dataId中读取所有配置，同时需要优化读取配置值时属性bean未初始化的问题，并支持使用System.getProperty获取Netty配置参数，这三项功能分别对应的PR编号是什么？

**标准答案**: 

**System A 回答**:
> 支持从Nacos单一dataId中读取所有配置的PR编号是[#3303][1]；优化读取配置值时属性bean未初始化问题的PR编号是[#3316][1]；支持使用System.getProperty获取Netty配置参数的PR编号是[#3336][1]。

**System B 回答**:
> 支持从Nacos单一dataId中读取所有配置的PR编号是#3303[13]，优化读取配置值时属性bean未初始化问题的PR编号未在提供的证据中明确提及，支持使用System.getProperty获取Netty配置参数的PR编号是#3336[13]。

**你的评分**: A=____  B=____  偏好=____

---

## review_009 [B_multi_constraint]

**问题**: 某公司正在将Seata从1.0.0升级到1.3.0，升级过程中遇到以下问题：1) 在Nacos注册中心中，服务端和客户端无法互相发现，且未显式配置group；2) 执行全局事务回滚时，出现脏数据，检查发现undo_log表结构未调整；3) 升级前使用的配置项风格为下划线分隔，升级后部分配置未生效。请分析可能的原因并给出解决方案。

**标准答案**: 

**System A 回答**:
> 根据提供的证据，我可以分析出以下可能的原因和解决方案：

1) 在Nacos注册中心中服务端和客户端无法互相发现且未显式配置group的问题，可能是因为没有正确配置namingLoadCacheAtStart参数，该参数控制启动时是否优先读取本地缓存，默认为false，在>=1.0.0版本中可用[12]。解决方案是将该参数设置为true，并确保namingCacheRegistryDir参数正确配置[12]。

2) 全局事务回滚时出现脏数据且undo_log表结构未调整的问题，是因为Seata从0.7.0版本开始，undo_log表增加了context字段[10][11]。解决方案是修改undo_log表结构，添加context字段，其定义为varchar(128) NOT NULL[10][11]。

3) 升级前使用的配置项风格为下划线分隔，升级后部分配置未生效的问题，可能是因为配置

**System B 回答**:
> 1. Nacos注册中心服务发现问题：1.3.0版本新增了group属性配置seata.registry.nacos.group，默认为DEFAULT_GROUP，Server和Client端需保持一致，未显式配置group可能导致服务发现失败 [1]。

2. 脏数据回滚问题：1.3.0版本升级后，mysql undolog表去除了id字段，与branch_table一并加强了时间戳精度，以防止undolog回滚时顺序错误导致脏数据无法回滚，需要升级mysql表结构并确保使用mysql5.6以上版本 [1]。

3. 配置项风格问题：1.1.0版本对配置项风格进行了统一，seata-all对应的*.conf文件配置项命名风格统一为点号+驼峰式组合，seata-spring-boot-starter对应的*.properties或*.yml文件命名风格统一为点号+中划线组合，需要调整配置项

**你的评分**: A=____  B=____  偏好=____

---

## review_010 [A_multi_doc_compare]

**问题**: 根据Seata和RocketMQ的文档，Seata在#5939中支持了什么配置，而RocketMQ的mqadmin命令中，如果同时配置了-b和-c，优先执行哪个？

**标准答案**: 

**System A 回答**:
> Seata在#5939中支持了jmx监控配置[1][5]。在RocketMQ的mqadmin命令中，如果同时配置了-b和-c，优先以Broker地址(-b)执行命令[14]。

**System B 回答**:
> 根据提供的片段，关于RocketMQ的mqadmin命令，如果同时配置了-b(Broker地址)和-c(clusterName)，则优先以Broker地址执行命令[1]。

**你的评分**: A=____  B=____  偏好=____

---

## review_011 [S_simple_control]

**问题**: RocketMQ 基于什么协议确保消息零丢失？

**标准答案**: 

**System A 回答**:
> RocketMQ 基于 DLedger 的 Raft 一致性协议确保消息零丢失，支持自动选主和多副本同步复制 [13]。该协议通过多副本机制保证消息在分布式系统中的可靠存储，即使部分节点故障也能确保数据不丢失 [13]。

**System B 回答**:
> RocketMQ 基于 DLedger 的 Raft 一致性协议确保消息零丢失 [1]。该协议支持自动选主和多副本同步复制，保证消息在分布式系统中的可靠性 [1]。

**你的评分**: A=____  B=____  偏好=____

---

## review_012 [C_multi_step]

**问题**: 在RocketMQ中，主题的队列列表作为消息存储的容器，其数量在创建主题时定义。若用户需要创建一个支持FIFO消息类型的主题，且该主题的队列数量至少为1，那么该主题的队列数量限制条件是什么？同时，该主题所支持的消息类型中，FIFO类型通过什么机制保证消息的投递顺序？

**标准答案**: 

**System A 回答**:
> 在RocketMQ中，支持FIFO消息类型的主题队列数量至少为1，队列数量在创建主题时定义，且一个主题内至少包含一个队列[5][6]。FIFO类型通过消息分组(MessageGroup)标记一组特定消息的先后顺序，保证相同消息组的消息按照发送顺序被存储在同一个队列中，并通过消费者和服务端的协议保障消息消费严格按照存储顺序处理[9][11]。

**System B 回答**:
> 主题的队列数量在创建主题时定义，一个主题内至少包含一个队列 [1][3]。对于支持FIFO消息类型的主题，队列数量限制条件与普通主题相同，即至少为1个队列 [1]。FIFO类型通过消息分组(MessageGroup)标记一组特定消息的先后顺序，保证消息的投递顺序严格按照消息发送时的顺序 [1][4]。

**你的评分**: A=____  B=____  偏好=____

---

## review_013 [A_multi_doc_compare]

**问题**: 根据Nacos和Seata的文档片段，Nacos启用MCP服务需要修改哪个配置文件中的哪个属性？Seata的更新中提到了哪些依赖的移除？

**标准答案**: 

**System A 回答**:
> 根据提供的证据片段，关于Nacos启用MCP服务的配置信息，片段[5][6]提到Nacos支持xDS协议中的CDS、EDS服务，并为EDS以及MCP实现了增量推送，用户可以使用Envoy或其他支持xDS协议的客户端与Nacos进行对接实现服务发现功能，但未明确指出需要修改的具体配置文件和属性。关于Seata更新中依赖的移除，提供的证据片段中未包含任何Seata相关的信息，因此无法回答Seata更新中提到的依赖移除问题[5][6]。

**System B 回答**:
> 根据提供的片段，无法确定Nacos启用MCP服务需要修改哪个配置文件中的哪个属性，因为片段中没有提到MCP服务的相关信息。

关于Seata的更新中提到的依赖移除，片段中只提到了配置项的兼容性变化，如1.1.0版本对配置项风格的统一，以及1.3.0版本中mysql undolog表去除id字段，但没有明确提到依赖的移除。[1][2][3]

**你的评分**: A=____  B=____  偏好=____

---

## review_014 [A_multi_doc_compare]

**问题**: 在Dubbo的override覆盖规则中，如果要对所有IP地址生效且持久保存，需要设置哪些必填项？同时，Seata组件在优化日志异常率配置时，将配置项从哪个位置移动到了哪个位置？

**标准答案**: 

**System A 回答**:
> 在Dubbo的override覆盖规则中，如果要对所有IP地址生效且持久保存，需要设置以下必填项：
- `override://` 表示数据采用覆盖方式 [1]
- `0.0.0.0` 表示对所有IP地址生效 [1]
- `com.foo.BarService` 表示只对指定服务生效 [1]
- `category=configurators` 表示该数据为动态配置类型 [1]
- `dynamic=false` 表示该数据为持久数据 [1]

关于Seata组件在优化日志异常率配置时的位置移动，检索片段中没有相关信息。

**System B 回答**:
> 在Dubbo的override覆盖规则中，要对所有IP地址生效且持久保存，需要设置必填项：`override://`表示覆盖方式，`0.0.0.0`表示对所有IP地址生效，`com.foo.BarService`表示服务名，`category=configurators`表示动态配置类型，`dynamic=false`表示持久数据[1]。关于Seata组件优化日志异常率配置的移动位置，检索片段中未提供相关信息[1][9]。

**你的评分**: A=____  B=____  偏好=____

---

## review_015 [A_multi_doc_compare]

**问题**: 根据Nacos和Seata的文档，两个项目在问题报告或变更记录方面分别强调了什么？请对比说明。

**标准答案**: 

**System A 回答**:
> Nacos强调问题报告时应具体到细节、可复现，并检查是否存在相同问题的issues，同时提供Nacos日志和配置信息[17]。Seata则强调编写良好、详细、明确的问题报告，并提供了问题模板，要求用户在提交issue前先搜索是否已存在相同问题[13]。Nacos注重版本、环境、配置等细节信息的提供，而Seata更注重问题报告的规范性和完整性。

**System B 回答**:
> Nacos强调问题报告应具体到细节、可复现且不重复，包括版本、环境、配置等信息，并建议附加Nacos日志和数据目录 [1]。Nacos还要求修改时保证分支仅和issue相关，做到一个分支只修改一件事，提交记录使用英文描述并以"谓+宾"格式进行 [2]。

Seata则强调社区欢迎所有以善意和尊重方式并能为项目增加价值的贡献，包括源代码、文档、建设性错误报告和讨论等 [3]。Seata还指出社区努力实现精英治理，随着时间的推移，为项目增加价值的贡献者会获得更多权利和责任 [3]。

**你的评分**: A=____  B=____  偏好=____

---

## review_016 [B_multi_constraint]

**问题**: 在 Seata 1.4.2 及以下版本中，使用 Oracle 数据库且字段类型为 timestamp 时，回滚操作抛出 Cannot construct instance of `java.time.LocalDateTime` 异常，同时全局事务锁重入时因表名大小写问题报 LockWaitTimeoutException，请问可能的原因及解决方案是什么？

**标准答案**: 

**System A 回答**:
> 在 Seata 1.4.2 及以下版本中，使用 Oracle 数据库且字段类型为 timestamp 时，回滚操作抛出 Cannot construct instance of `java.time.LocalDateTime` 异常，解决方案是升级到 1.5.0 及以上版本 [1][4]。对于全局事务锁重入时因表名大小写问题导致的 LockWaitTimeoutException，可能的原因是数据库表名大小写敏感问题，但片段中未提供具体解决方案 [1][4][10]。

**System B 回答**:
> 在 Seata 1.4.2 及以下版本中，使用 Oracle 数据库且字段类型为 timestamp 时回滚抛出 Cannot construct instance of `java.time.LocalDateTime` 异常的解决方案是升级到 1.5.0 及以上版本 [1]。

对于全局事务锁重入时因表名大小写问题报 LockWaitTimeoutException，解决方案是从 MySQL JDBC 连接 URL 中移除 `useOldAliasMetadataBehavior=true` 参数，或将其设置为 `false` [3]。

对于 Oracle 使用 timestamp 字段类型回滚失败的问题，可以拉取 seata-plugin 代码，本地打包自行引入，或直接拷贝代码进行 SPI 扩展支持 [2]。

**你的评分**: A=____  B=____  偏好=____

---

