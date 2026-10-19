---
title: 后端
description: JDBC（MySQL、PostgreSQL）、Redis 和 Zookeeper 后端如何存储会话、租约和组成员，fencing token 从何而来，以及如何运维各个后端。
---

# 后端

每个后端都实现同一个 `LeaseBackend` SPI，并通过同一套 [TCK](/zh/guide/testing#backend-tck)，因此选举、锁和组在所有后端上的行为都相同。
它们的区别在于存储方式、在哪里测量时间，以及 fencing token 的持久性。

每个后端都遵循相同的规则：会话在其租约时长内未续约即过期；过期的会话永不复活；租约只在其会话存活期间属于该会话；租约的 token 大于零，
随每个新任期严格递增且永不重置；所有剩余时间都由后端的时钟测量，并以时长的形式返回。

## JDBC (MySQL 8, PostgreSQL 14+) {#jdbc-mysql-8-postgresql-14}

模块 `simba-jdbc`，类 `JdbcLeaseBackend`，配合一个 `Dialect`（`Dialect.MYSQL`、`Dialect.POSTGRESQL`）：

```kotlin
val backend = JdbcLeaseBackend.mysql(dataSource)      // or JdbcLeaseBackend.postgres(dataSource)
```

### 表结构 {#schema}

使用对应数据库的脚本创建这三张表（只需一次）：
[`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql)
或
[`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql)
（两者都在 classpath 的 `me/ahoo/simba/jdbc/` 下）。MySQL 版本：

```sql
create table if not exists simba_session
(
    session_id char(32) character set ascii collate ascii_bin not null primary key,
    expires_at bigint                                        not null comment 'database time, epoch ms',
    metadata   varchar(1024)                                 not null default '',
    index idx_session_expires (expires_at)
);

create table if not exists simba_lease
(
    name       varchar(128) character set ascii collate ascii_bin not null primary key,
    session_id char(32) character set ascii collate ascii_bin     not null default '' comment 'empty = free',
    token      bigint                                             not null comment 'term number, never reset',
    index idx_lease_session (session_id)
);

create table if not exists simba_member
(
    group_name varchar(128) character set utf8mb4 collate utf8mb4_bin not null,
    session_id char(32) character set ascii collate ascii_bin not null,
    primary key (group_name, session_id),
    index idx_member_session (session_id)
);
```

| 表 | 每行对应 | 说明 |
|---|---|---|
| `simba_session` | 会话 | `expires_at` 为数据库时间；每次续约一条 `UPDATE` 即可为会话续期，并由此为该节点的每个租约和组成员身份续期 |
| `simba_lease` | 曾被获取过的租约名称 | 永不删除，因此 token 永不重置；释放会把 `session_id` 置为 `''` 并保留 token |
| `simba_member` | 组成员 | 仅在其会话存活期间有效 |

租约名称和组名都区分大小写（MySQL 上为二进制排序规则，PostgreSQL 上为 `"C"`）。

### 工作原理 {#how-it-works}

- 只有当 `session_id` 所指会话的 `expires_at` 在未来时，租约才属于该会话。获取是一条 upsert：当行空闲或其会话已过期时占有该行，并把
  `token` 加一。在 MySQL 上，`token` 的赋值在 `SET` 中排在最前，因为 MySQL 按从左到右的顺序、使用已更新的值求值 `SET`。
- 接管时以共享锁（`for share`）读取持有者的会话行，因此在任何隔离级别下都与该会话的续约串行化：并发提交的续约要么胜出，要么在同一次续约
  回复中看到租约已被接管。
- 每次时间比较都使用数据库自身的时钟（`now`：UTC epoch 毫秒，在一条语句内不变，且不受连接时区影响），因此应用主机的时钟从不影响结果。
- 过期会话被惰性清理：过期会话的租约对下一个获取者空闲，发现自己会话已过期的续约会将其删除。其余部分（崩溃进程留下的会话）由
  `purgeExpired()` 删除：它在 `openSession` 或 `keepAlive` 之后运行，每个 `purgeInterval`（默认一分钟）至多一次，每次至多 1000
  个会话，沿 `idx_session_expires` 索引查找。

### 运维说明 {#operating-notes}

- 释放不会通知任何人：正常停止后，候选者在下一次尝试（`renewInterval / 2` 加抖动）时接管。
- 每条语句都有超时；starter 将其设为会话的续约间隔（`simba.jdbc.query-timeout`），超过之后引擎无论如何都会放弃该调用。
- 为 Simba 提供一个不会被应用耗尽的连接池：等待连接超过续约间隔的续约会被视为失败。

## Redis 7 {#redis-7}

模块 `simba-redis`（与驱动无关，Lua 脚本位于 `RedisScripting` 端口之后）和 `simba-spring-redis`
（`SpringRedisScripting`，Spring Data Redis 适配器）。`SpringRedisScripting` 需要 Spring Boot 默认的 Lettuce 客户端：
使用 Jedis 时订阅会阻塞调用线程，也无法使用 `WAIT`，因此 starter 遇到非 Lettuce 的 `RedisConnectionFactory` 会启动失败：

```kotlin
val backend = RedisLeaseBackend(SpringRedisScripting(redisConnectionFactory))
```

### 键 {#keys}

| 键 | 类型 | 内容 |
|---|---|---|
| `simba:lease:{name}` | 哈希，永不过期 | `session`、`token`、`expires`（Redis `TIME`）；释放只清除 `session`，因此 token 永不重置 |
| `simba:session:{sid}` | 哈希，`PX` = 租约时长 | 会话；一旦它消失，续约、获取和加入组都会失败 |
| `simba:session:{sid}:leases` | 集合，同一槽 | 会话的租约 |
| `simba:session:{sid}:groups` | 集合，同一槽 | 会话加入的组 |
| `simba:group:{group}` | 有序集合 | 成员（会话 id），以 Redis 时间下的过期时刻为分数 |

释放会在租约键的频道（`simba:lease:{name}`）上发布 `released`；这是唯一的广播，让候选者能够立即接管。所有时间判断都在脚本内使用 Redis `TIME`。

新任期的持续时间与其会话相同，而续约只延长节点仍持有的租约，因此即使会话键比租约存活得更久，租约也会自行过期。

### Cluster {#cluster}

在 Redis Cluster 上（`RedisScripting.clustered`，或 `simba.redis.route-by-slot=true`），每次脚本调用都限定在一个哈希槽内：
一次调用先访问会话所在的槽，然后为其余键的每个槽各运行一个脚本。此时租约和会话所在的槽可能位于时钟不同的节点上；它们之间的时钟偏差会让租约缩短或延长相应的时长。

### 主从切换后 token 回退 {#token-regression-after-a-failover}

Redis 复制是异步的。当主节点在副本收到最新的 `token` 递增之前故障时，被提升的副本可能分配一个已经用过的 token，或更小的 token。
此时检查 token 的资源可能接受一个任期在时间上更新但 token 更小的节点的写入，或拒绝合法的持有者。

- 设置 `simba.redis.wait-replicas`（构造参数 `waitReplicas`）：只有当这么多副本通过 `WAIT` 确认后，新任期才算数，否则获取失败。
  这会缩小窗口，但不能消除它，并且在 Cluster 上不可用。
- 启用持久化（AOF），使单节点重启不会重置 token。
- 当 fencing 必须严格时，使用 JDBC 或 Zookeeper。

## Zookeeper {#zookeeper}

模块 `simba-zookeeper`，类 `ZookeeperLeaseBackend`：

```kotlin
val backend = ZookeeperLeaseBackend("zk1:2181,zk2:2181,zk3:2181", root = "/simba")
```

### 节点 {#nodes}

| 路径 | 类型 | 内容 |
|---|---|---|
| `{root}/leases/{name}` | 临时节点 | 会话 id；持有者是节点的 `ephemeralOwner`，token 是其 `czxid` |
| `{root}/groups/{group}/{sessionId}` | 临时节点，位于容器节点之下 | 组成员 |

`root` 默认为 `/simba`。

### 工作原理 {#how-it-works-1}

- 一个 Simba 会话就是一个 Zookeeper 会话：后端为每个会话打开一个专用的 Zookeeper 客户端（不使用共享的 Curator 客户端），并且从不把它重连到另一个会话。
  `SessionId` 是以 16 位十六进制数字表示的 Zookeeper 会话 id。
- Token 是租约节点的 `czxid`：由于 zxid 是全局的，它在任期之间严格递增，但不连续。
- 释放会删除该节点；候选者 watch 该节点并立即接管。组成员的变化同样会被 watch，包括尚不存在的组被创建。
- 名字从发出 create 的那一刻起就被视为"可能拥有"：即使 create 已在服务端成功而回复丢失（连接断开、超时），下一次续约在核实节点持有者后
  仍会报告该租约，由引擎释放。同一会话的释放逐个执行。
- Zookeeper 客户端会自行发送心跳，因此一个本地看门狗会关闭在租约时长内没有被续约的会话：停止续约的节点永远不会仅靠心跳保住自己的租约。
- 剩余时间为 `min(leaseDuration, 协商后的会话超时)`。客户端断开连接期间，续约和获取都会失败，因此本地截止时刻会耗尽；当 Zookeeper
  报告会话过期时，会话为 `Expired`。

### 运维说明 {#operating-notes-1}

- 请求的会话超时为 `leaseDuration`；集群会将其限制在 `[minSessionTimeout, maxSessionTimeout]` 之内（默认为 2 到 20 个 tick）。
  崩溃后的故障转移需要协商后的超时时长。
- `simba.zookeeper.connection-timeout` 限制打开会话所需的最长时间。
- 组名遵循 `LeaseName` 的规则（1–128 个 `[A-Za-z0-9._:-]` 字符，且不能是 `.` 或 `..`），与所有后端一致。`members` 先对组路径执行
  sync，因此刚刚返回的 join 一定出现在视图中。

### 安全 {#security}

默认情况下节点使用 `OPEN_ACL_UNSAFE`：能连到该集群的任何客户端都可以删除或抢先创建租约节点，也可以伪造组成员。在共享的集群上，请启用认证并收紧 ACL：

```kotlin
val backend = ZookeeperLeaseBackend(
    "zk1:2181,zk2:2181,zk3:2181",
    authInfo = listOf(AuthInfo("digest", "simba:secret".toByteArray())),
    aclProvider = myAclProvider, // 例如对所有路径返回 ZooDefs.Ids.CREATOR_ALL_ACL
    clientConfig = ZKClientConfig(), // TLS 或 SASL 设置，可选
)
```

- 每个客户端（每个会话的客户端，以及 `members` 读取所用的客户端）都会添加 `authInfo`，并用 `clientConfig` 构建。
- 后端创建的每个节点（包括它创建的父节点）都使用 `aclProvider` 给出的 ACL。已经存在的父节点保留原有 ACL；`{root}/leases` 和
  `{root}/groups/{group}` 的 ACL 决定谁能创建或删除租约和成员：请用后端的身份创建它们，或自行设置其 ACL。
- Spring Boot：`simba.zookeeper.auth.scheme` / `simba.zookeeper.auth.credentials` 和 `simba.zookeeper.acl`
  （`auto` 在配置了凭据后使节点仅创建者可用）；`ACLProvider` bean 会替换 ACL，`ZKClientConfig` bean 用于配置所有客户端
  （见[配置](./configuration.md)）。

## 在 Spring Boot 中选择后端 {#selecting-a-backend-in-spring-boot}

当后端模块在 classpath 上、`simba.enabled` 和 `simba.<backend>.enabled` 不为 `false`、并且 `simba.backend` 未设置或指向它时，
对应后端的自动配置生效。除非应用自己定义了 `LeaseBackend` bean，否则它会定义该 bean。

当 classpath 上有多个后端模块时，请设置 `simba.backend=jdbc|redis|zookeeper`；否则启动会失败，`simba.backend` 指向一个未生效的后端时也会失败。
要使用 starter 不负责配置的后端（或你自己的 `LeaseBackend`），请自行定义 `LeaseBackend` bean。见 [Spring Boot](/zh/guide/configuration)。
