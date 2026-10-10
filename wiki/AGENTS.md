# AGENTS.md — Simba Wiki

VitePress site for Simba. Root rules in `../AGENTS.md` also apply.

## Commands

Requires Node.js 22.12 or later (see `engines` in `package.json`).

```bash
pnpm install
pnpm run dev          # Dev server
pnpm run build        # Build; run before committing
pnpm run preview      # Preview the build
pnpm run fix:mermaid  # Validate and fix Mermaid syntax; run after editing diagrams
pnpm run sync:llms    # Regenerate llms-full.txt from the English pages
```

## Layout

Each topic has exactly one page; link to it instead of restating it. Before adding a page, check whether an existing
page owns the topic.

| Page | Owns |
|---|---|
| `guide/index.md` | What Simba guarantees and does not; choosing an API and a backend |
| `guide/quick-start.md` | Dependencies, backend wiring, first code for each API, use without Spring |
| `guide/backends.md` | Per-backend storage, schema/keys, fencing source, operating notes, `simba.backend` selection |
| `guide/correctness.md` | Lease model, ttl/transition tuning, fencing tokens, failure modes, callback semantics |
| `guide/configuration.md` | Every `simba.*` property, starter beans, executors, factories without Spring |
| `guide/observability.md` | Metrics, alert rules, Actuator endpoint, custom observers |
| `guide/upgrading.md` | Upgrade steps and breaking changes |
| `api/index.md` | Public types and their contracts |
| `architecture/index.md` | Internals: layers, lease engine, threads, time, wire contracts, ADR index |
| `contributing/index.md` | Build, tests, TCK, adding a backend, documentation workflow |

- Chinese pages mirror the same paths under `zh/`. Every page change is made in both languages.
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
- Mermaid: dark-mode colors (`#2d333b` fills, `#6d5dfc` borders, `#e6edf3` text), `<br>` not `<br/>`
  (Vue compiler), and `autonumber` in sequence diagrams.

## Boundaries

- **Ask first:** changing `.vitepress/config/` (navigation) or the theme.
- **Never:** delete pages without discussion.
