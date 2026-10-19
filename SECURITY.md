# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 5.x | ✅ |
| 4.6.x | Fixes on the `v4.6.x` maintenance branch |
| 3.3.x | Security fixes only |
| < 3.3 | ❌ |

## Reporting a vulnerability

Please do not open a public issue. Report privately through
[GitHub Security Advisories](https://github.com/Ahoo-Wang/Simba/security/advisories/new), or by email to
ahoowang@qq.com. Include the affected version, backend, and a reproduction if possible.

You can expect an acknowledgement within 3 working days and an assessment within 7. Fixes are released as patch
versions and credited in the advisory unless you prefer otherwise.

## Securing the backends

Simba trusts its backend: any client that can write Simba's tables, keys or nodes can take over or forge leases and
group members. Restrict that access to the nodes running Simba.

- JDBC and Redis: use a dedicated account (database user, Redis ACL user) limited to Simba's tables or keys.
- Zookeeper: nodes are world-writable (`OPEN_ACL_UNSAFE`) unless you configure authentication and an ACL
  (`simba.zookeeper.auth.*` and `simba.zookeeper.acl`, or `ZookeeperLeaseBackend(authInfo, aclProvider,
  clientConfig)`). See [Zookeeper security](https://github.com/Ahoo-Wang/Simba/blob/main/wiki/guide/backends.md).
