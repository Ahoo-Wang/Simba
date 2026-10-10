<!-- Title: Conventional Commits, e.g. "fix(redis): keep the fencing token on own broadcasts". Add "!" for breaking changes. -->

## Summary

<!-- What changes and why. Link issues with "Fixes #123". -->

## Checklist

- [ ] Tests cover the change (TCK `MutexContendServiceSpec` for backend semantics; deterministic, no sleeps)
- [ ] `./gradlew check` passes locally (needs Docker for the JDBC/Redis Testcontainers)
- [ ] Docs updated in English and Chinese (`README*`, `wiki/`, `wiki/llms-full.txt` via `pnpm run sync:llms`)
- [ ] `AGENTS.md` updated if an invariant changed; ADR added for design decisions (`docs/adr/`)
- [ ] Breaking change? Title has `!` and the PR describes the upgrade path
