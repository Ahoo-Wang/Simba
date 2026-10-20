# AGENTS.md — Simba Wiki

VitePress site for Simba. Root rules in `../AGENTS.md` also apply.

## Commands

Requires Node.js 22.12 or later (see `engines` in `package.json`).

```bash
pnpm install
pnpm run dev          # Dev server
pnpm run build        # Build; run before committing
pnpm run preview      # Preview the build
pnpm run fix:mermaid  # Fix common Mermaid syntax issues; run after editing diagrams
pnpm run check:mermaid # Parse every Mermaid diagram in headless Chrome ($CHROME_BIN, else Chrome on PATH or macOS)
pnpm run sync:llms    # Regenerate llms-full.txt from the English pages
```

The `Deploy Wiki to GitHub Pages` workflow builds the site and runs `check:mermaid` and `check:llms` on every pull
request that touches `wiki/`, and deploys from `main`.

## Layout

Each topic has exactly one page; link to it instead of restating it. Before adding a page, check whether an existing
page owns the topic.

| Page | Owns |
|---|---|
| `guide/index.md` | Why Simba: the problem from first principles, what Simba guarantees and does not, choosing an API and a backend |
| `guide/quick-start.md` | Dependencies, backend wiring, first code for each API, use without Spring |
| `guide/concepts.md` | How Simba works: sessions, local deadline, leases, terms, tokens, candidates, election lifecycle, handover order, callbacks, timing |
| `guide/correctness.md` | Fencing and failures: why validity is not enough, the fencing protocol and recipes, token sources, `ensureValid`, failure modes |
| `guide/backends.md` | Backend requirements, per-backend storage (schema, keys, paths), fencing source, operating notes |
| `guide/configuration.md` | Spring Boot: every `simba.*` property, `simba.backend` selection, starter beans, shutdown order, `Simba.builder()` without Spring |
| `guide/leader-work.md` | Elections, `runWhileLeader`, `Schedule`, partition groups, `@SimbaScheduled`, `@SimbaPartitioned` |
| `guide/locks.md` | `lock(name)` (blocking, async, coroutines), `LockTerm`, early loss |
| `guide/observability.md` | Snapshot, events, metrics, alert rules, Actuator endpoint, health indicator |
| `guide/testing.md` | In-memory backend, virtual time, simulation, `FencingSpec`, `LeaseBackendSpec` |
| `guide/upgrading.md` | Versioning policy, upgrades within 5.x |
| `guide/migration.md` | 4.x to 5.0: API, property, storage and metric mapping, switch-over steps |
| `api/index.md` | Public types and their contracts |
| `architecture/index.md` | Internals: layers, lease engine, threads, time, wire contracts, ADR index |
| `contributing/index.md` | Build, tests, TCK, adding a backend, documentation workflow |

- Chinese pages mirror the same paths under `zh/`. Every page change is made in both languages. Chinese headings carry
  the English slug as an explicit id (`## 交接顺序 {#handover-order}`), so both languages share anchors.
- Navigation and sidebars: `.vitepress/config/en.ts` and `zh.ts`; site config: `.vitepress/config/index.ts`.
- `llms.txt` (page index, same content as the root `llms.txt`) is maintained by hand: update both when pages are
  added, renamed, or removed.
- `llms-full.txt` is generated from the English pages: run `pnpm run sync:llms` after editing them
  (`pnpm run check:llms` verifies it). Add a `<doc title=... path=...>` block by hand for a new page.

## Content Conventions

- Every page has `title` and `description` frontmatter.
- Keep class names, method names, and config keys in English in Chinese pages.
- Describe behavior, not code layout: state the contract and link the source file or ADR on GitHub
  (`https://github.com/Ahoo-Wang/Simba/blob/main/<path>`) without line anchors, which drift.
- Verify every claim against the code. Never claim more than `../AGENTS.md` guarantees (e.g. no split-brain, or work
  continuing while the backend is down).
- Start each page from the reader's problem and derive the mechanism from it; state the contract, then the details.
- Add a diagram only when it explains a mechanism that prose explains worse (a sequence, a state machine, a data
  flow). Diagrams render through `vitepress-plugin-mermaid` and `.vitepress/theme/Mermaid.vue` (as in CoCache): the
  component follows the light/dark theme and adds zoom, pan and an expanded view, so never add `style`, `classDef` or
  `linkStyle` lines. Use `<br>` not `<br/>` (Vue compiler), `autonumber` in sequence diagrams, and never `;` in
  sequence-diagram message text. Run `pnpm run check:mermaid` after editing diagrams.
- Write any text containing `<…>`, such as `CompletionStage<Void>`, in backticks; otherwise Vue parses it as HTML.

## Boundaries

- **Ask first:** changing `.vitepress/config/` (navigation) or the theme.
- **Never:** delete pages without discussion.
