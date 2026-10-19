---
title: Upgrading
description: Simba's versioning policy, how to upgrade within 5.x, and where to find the 4.x to 5.0 migration.
---

# Upgrading

Simba follows [Semantic Versioning](https://semver.org/). Breaking changes of the public API (`me.ahoo.simba`,
`me.ahoo.simba.partition`, `me.ahoo.simba.event`, `me.ahoo.simba.spi`), the `simba.*` properties, the JDBC schema,
the Redis keys and the Zookeeper paths only happen in a major version. Types marked `@InternalSimbaApi` (the engine,
`Simba.Builder.scheduler`, `Simba.awaitIdle`) carry no such guarantee.

## From 4.x

5.0 is not compatible with 4.x in API, configuration or storage, and needs every node stopped before the switch. Follow
[Migrating from 4.x to 5.0](/guide/migration).

## Within 5.x

Minor and patch releases keep the storage format, so nodes of different 5.x versions can contend for the same names
and you can roll an upgrade node by node. Release notes list every change:
[GitHub Releases](https://github.com/Ahoo-Wang/Simba/releases).
