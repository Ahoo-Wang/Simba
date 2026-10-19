---
layout: home
title: Simba
description: JVM 选主与分布式锁库，基于 MySQL、PostgreSQL、Redis 或 Zookeeper。

hero:
  name: "Simba"
  text: "JVM 选主库"
  tagline: "同一时刻只有一个节点执行任务——基于你已经在运维的数据库、Redis 或 Zookeeper。"
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/quick-start
    - theme: alt
      text: 工作原理
      link: /zh/guide/correctness
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Simba

features:
  - title: 任期，而非标志位
    details: "赢得租约即获得一个 Term：它的 fencing token、是否仍然有效，以及还剩多长时间。每个任期都以恰好一次 onRevoked 结束。"
  - title: 每个节点一个会话
    details: "一个进程只保持一个后端会话；每个选举、锁和分区都随它存续、随它结束。一次续约（keep-alive）即可续期全部。"
  - title: Fencing token
    details: "每个任期都带有严格递增的 token，被保护的资源可以据此拒绝暂停后恢复的旧 leader。"
  - title: 任务绑定到任期
    details: "runWhileLeader、调度、@SimbaScheduled 和 @SimbaPartitioned 运行在虚拟线程上，任期结束时被中断，并在租约释放之前完成。"
  - title: 三种后端，一个引擎
    details: "JDBC（MySQL、PostgreSQL）、Redis 和 Zookeeper 在同一个纯状态机下通过同一套 TCK。无需运行 Simba 服务端。"
  - title: 可测试、可观测
    details: "测试中使用内存后端和虚拟时间；生产中提供 snapshot()、Micrometer 指标、simba Actuator 端点和健康指示器。"
---
