---
title: 升级
description: Simba 的版本策略、5.x 内部如何升级，以及从 4.x 迁移到 5.0 的指引。
---

# 升级

Simba 遵循 [语义化版本](https://semver.org/lang/zh-CN/)。公共 API（`me.ahoo.simba`、`me.ahoo.simba.partition`、
`me.ahoo.simba.event`、`me.ahoo.simba.spi`）、`simba.*` 属性、JDBC schema、Redis 键和 Zookeeper 路径的破坏性变更只会出现在主版本中。
标注了 `@InternalSimbaApi` 的类型（引擎、`Simba.Builder.scheduler`、`Simba.awaitIdle`）不提供这一保证。

## 从 4.x 升级 {#from-4-x}

5.0 在 API、配置和存储上都与 4.x 不兼容，切换前需要停止所有节点。请按照
[从 4.x 迁移到 5.0](/zh/guide/migration) 操作。

## 5.x 内部升级 {#within-5-x}

次版本和补丁版本保持存储格式不变，因此不同 5.x 版本的节点可以竞争同一名称，你可以逐个节点滚动升级。发布说明列出了所有变更：
[GitHub Releases](https://github.com/Ahoo-Wang/Simba/releases)。
