---
layout: home
title: Simba
description: Leader election and distributed locks for the JVM, backed by MySQL, PostgreSQL, Redis or Zookeeper.

hero:
  name: "Simba"
  text: "One node at a time"
  tagline: "Leader election, leader-only work and distributed locks for the JVM, on the database, Redis or Zookeeper you already run. Bounded by leases, enforced with fencing tokens."
  actions:
    - theme: brand
      text: Why Simba
      link: /guide/
    - theme: alt
      text: Quick Start
      link: /guide/quick-start
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Simba

features:
  - title: Leases, because death is invisible
    details: "A node that stops answering may be dead or merely slow. Ownership therefore expires on its own: a node keeps a lease only by renewing it, and a crashed leader is replaced after about one lease duration."
  - title: Terms you can hold on to
    details: "Winning a lease gives you a Term: a fencing token, whether it is still valid and how long it has left, computed from your own monotonic clock. Every onElected(term) is followed by exactly one onRevoked(term, reason)."
  - title: Fencing tokens, because processes pause
    details: "A paused leader can wake up after its lease ended. Every term carries a strictly increasing token, so the resource you protect can reject the stale write. Simba never promises more than that."
  - title: Work bound to the term
    details: "runWhileLeader, schedules, partition groups, @SimbaScheduled and @SimbaPartitioned run on virtual threads, are interrupted when the term ends, and get to exit before the lease is released."
  - title: One session per process
    details: "Every election, lock and partition of a node hangs off one backend session, renewed by one keep-alive. No Simba server: JDBC (MySQL, PostgreSQL), Redis and Zookeeper pass the same TCK."
  - title: Verified, testable, observable
    details: "A pure engine checked by deterministic simulation over 10⁴ seeds; an in-memory backend and virtual time for your tests; snapshot(), Micrometer metrics, an Actuator endpoint and a health indicator in production."
---
