---
layout: home
title: Simba
description: JVM 选主与分布式互斥库，基于 MySQL、Redis 或 Zookeeper。

hero:
  name: "Simba"
  text: "JVM 选主库"
  tagline: "同一时刻只有一个节点执行任务——基于你已经在运维的 MySQL、Redis 或 Zookeeper。"
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
  - title: 仅在 leader 上调度
    details: "给方法加上 @SimbaScheduled，或在不使用 Spring 时使用 SimbaScheduler。节点成为 leader 时开始工作，失去领导权时中断。"
  - title: 可推理的租约
    details: "所有权是有时限的租约。租约结束时，每个节点都会自行撤销所有权，即使后端调用仍然挂起。"
  - title: Fencing token
    details: "每个所有权任期都带有严格递增的 token，被保护的资源可以据此拒绝暂停后恢复的旧 leader。"
  - title: 三种后端，一份契约
    details: "JDBC/MySQL、Redis 和 Zookeeper 通过同一套兼容性测试。无需运行 Simba 服务端。"
  - title: Spring Boot starter
    details: "为 classpath 上的后端自动配置，提供专用回调执行器，并支持显式的 simba.backend 选择。"
  - title: 可观测
    details: "提供领导权、竞争和租约过期的 Micrometer 指标，以及只读的 simba Actuator 端点。"
---
