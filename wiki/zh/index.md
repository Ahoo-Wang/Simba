---
layout: home
title: Simba
description: 面向 JVM 的 leader 选举与分布式锁，基于 MySQL、PostgreSQL、Redis 或 Zookeeper。

hero:
  name: "Simba"
  text: "同一时刻，只有一个节点"
  tagline: "面向 JVM 的 leader 选举、仅由 leader 执行的任务和分布式锁，构建在你已经在运维的数据库、Redis 或 Zookeeper 之上。以租约限定时间，以 fencing token 强制执行。"
  actions:
    - theme: brand
      text: 为什么选择 Simba
      link: /zh/guide/
    - theme: alt
      text: 快速开始
      link: /zh/guide/quick-start
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Simba

features:
  - title: 租约，因为死亡不可见
    details: "一个不再响应的节点可能已经宕机，也可能只是变慢了。因此所有权必须自行过期：节点只有不断续约才能保有租约，崩溃的 leader 大约在一个租约时长之后被替换。"
  - title: 可以握在手里的任期
    details: "赢得租约即获得一个 Term：fencing token、是否仍然有效、还剩多长时间，均由你自己的单调时钟计算。每个 onElected(term) 之后恰好跟随一个 onRevoked(term, reason)。"
  - title: Fencing token，因为进程会暂停
    details: "暂停的 leader 可能在租约结束后才醒来。每个任期都携带严格递增的 token，被保护的资源可以据此拒绝过期的写入。Simba 从不承诺超出这一点的东西。"
  - title: 任务绑定到任期
    details: "runWhileLeader、调度、分区组、@SimbaScheduled 和 @SimbaPartitioned 运行在虚拟线程上，任期结束时被中断，并在租约释放之前得以退出。"
  - title: 每个进程一个会话
    details: "节点的每个选举、锁和分区都挂在同一个后端会话上，由一次续约统一续期。无需 Simba 服务端：JDBC（MySQL、PostgreSQL）、Redis 和 Zookeeper 通过同一套 TCK。"
  - title: 经过验证、可测试、可观测
    details: "纯函数引擎经过 10⁴ 个种子的确定性模拟检验；测试中使用内存后端和虚拟时间；生产中提供 snapshot()、Micrometer 指标、Actuator 端点和健康指示器。"
---
