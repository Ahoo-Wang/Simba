# Contributing to Simba

Thanks for helping. This page is the contract for changes; the [contributor guide](https://simba.ahoo.me/guide/contributing.html) has more background.

## Development setup

- JDK 17 and Docker (the JDBC and Redis tests start MySQL and Redis with Testcontainers). `-PtestJavaVersion=N` runs the tests on another installed JDK; bytecode always targets 17.
- `./gradlew check` runs all tests, detekt (static analysis and formatting) and the license-header check.
- Wiki: Node.js 22.12+ and pnpm, see `wiki/AGENTS.md`.

## Workflow

1. **Discuss first** for anything beyond a small fix: open an issue with the problem and acceptance criteria. Design decisions are recorded as ADRs in `docs/adr/`.
2. **Branch** from `main` (`feat/...`, `fix/...`, `docs/...`). Branches are short-lived; `main` is always releasable and only changes through pull requests.
3. **Commit and title** with [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, lowercase type, no trailing period. Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `style`, `revert`. Add `!` (`refactor(core)!: ...`) for breaking changes. The `Labeler` workflow checks the PR title and labels the PR, which drives the release notes.
4. **Test** every behavior change with focused, deterministic tests (latches/futures, no sleeps). Backend semantics go through the TCK `MutexContendServiceSpec`.
5. **Document** in English and Chinese (README, wiki). Regenerate `wiki/llms-full.txt` with `pnpm run sync:llms`. Update `AGENTS.md` when an invariant changes.
6. **Review**: CODEOWNERS are requested automatically. A pull request merges (squash) only when all required checks pass and review comments are resolved.

`main` is protected by a repository ruleset: changes only through pull requests, squash merges only, required status checks (tests per module and example backend, CodeQL, Codecov, PR title), resolved review threads, and no force-pushes or deletion.

## Quality gates (CI)

| Gate | Where |
|---|---|
| Unit, integration and TCK tests per module, plus the example app per backend; the full suite also runs on JDK 25 | `Integration Test` workflow |
| detekt static analysis, formatting and Apache license headers | `./gradlew check` |
| Coverage: project and patch targets | Codecov (`codecov.yml`) |
| Security analysis | CodeQL workflow |
| Conventional PR title and labels | `Labeler` workflow |

## Versioning and releases

Simba follows [Semantic Versioning](https://semver.org/): breaking API or behavior changes bump the major version, backward-compatible features the minor, fixes the patch. Breaking changes are listed with an upgrade path in the release notes and, for larger ones, in an ADR.

To release `X.Y.Z`: merge a `chore: bump version to vX.Y.Z` pull request (updates `gradle.properties` and the documented coordinates), then create the GitHub release `vX.Y.Z` from `main`. The `Packages Deploy` workflow verifies the tag against the project version and publishes to GitHub Packages and Maven Central. Release notes are generated from the PR labels (`.github/release.yml`).

## License

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE). Source files carry the Apache license header.
