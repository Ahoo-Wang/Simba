---
layout: home
title: Simba
description: Leader election and distributed locks for the JVM, backed by MySQL, PostgreSQL, Redis or Zookeeper.

hero:
  name: "Simba"
  text: "Leader election for the JVM"
  tagline: "One node at a time runs the job — on the database, Redis or Zookeeper you already operate."
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
  - title: Terms, not flags
    details: "Winning a lease gives you a Term: its fencing token, whether it is still valid, and how long it has left. Every term ends with exactly one onRevoked."
  - title: One session per node
    details: "A process keeps one backend session alive; every election, lock and partition lives and dies with it. One keep-alive renews them all."
  - title: Fencing tokens
    details: "Every term carries a strictly increasing token, so the resources you protect can reject a paused former leader."
  - title: Work bound to the term
    details: "runWhileLeader, schedules, @SimbaScheduled and @SimbaPartitioned run on virtual threads, are interrupted when the term ends, and finish before the lease is released."
  - title: Three backends, one engine
    details: "JDBC (MySQL, PostgreSQL), Redis and Zookeeper pass the same TCK under one pure state machine. No Simba server to run."
  - title: Testable and observable
    details: "In-memory backend and virtual time for your tests; snapshot(), Micrometer metrics, a simba Actuator endpoint and health indicator in production."
---
