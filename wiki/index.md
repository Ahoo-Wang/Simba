---
layout: home
title: Simba
description: Leader election and distributed mutex for the JVM, backed by MySQL, Redis or Zookeeper.

hero:
  name: "Simba"
  text: "Leader election for the JVM"
  tagline: "One node at a time runs the job — on the MySQL, Redis or Zookeeper you already operate."
  actions:
    - theme: brand
      text: Quick Start
      link: /guide/quick-start
    - theme: alt
      text: How it works
      link: /guide/correctness
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Simba

features:
  - title: Leader-only scheduling
    details: "Annotate a method with @SimbaScheduled, or use SimbaScheduler without Spring. Work starts when the node becomes leader and is interrupted when it loses leadership."
  - title: Leases you can reason about
    details: "Ownership is a time-bounded lease. Each node revokes its own ownership when the lease ends, even if the backend call hangs."
  - title: Fencing tokens
    details: "Every ownership term carries a strictly increasing token, so the resources you protect can reject a paused former leader."
  - title: Three backends, one contract
    details: "JDBC/MySQL, Redis and Zookeeper pass the same compatibility test kit. No Simba server to run."
  - title: Spring Boot starter
    details: "Auto-configuration for the backend on your classpath, a dedicated callback executor, and explicit simba.backend selection."
  - title: Observable
    details: "Micrometer metrics for leadership, contention and lease expiry, plus a read-only simba Actuator endpoint."
---
