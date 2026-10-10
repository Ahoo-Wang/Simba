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

- English pages live at the root (`guide/`, `architecture/`, `api/`, `modules/`, `testing/`, `onboarding/`);
  Chinese pages mirror the same paths under `zh/`. Every page change is made in both languages.
- Navigation and sidebars: `.vitepress/config/en.ts` and `zh.ts`; site config: `.vitepress/config/index.ts`.
- `llms.txt` (page index) is maintained by hand: update it when pages are added, renamed, or removed.
- `llms-full.txt` is generated from the English pages: run `pnpm run sync:llms` after editing them
  (`pnpm run check:llms` verifies it). Add a `<doc title=... path=...>` block by hand for a new page.

## Content Conventions

- Every page has `title` and `description` frontmatter.
- Keep class names, method names, and config keys in English in Chinese pages.
- Citations: `[file_path:line](https://github.com/Ahoo-Wang/Simba/blob/main/file_path#Lline)`. Line anchors
  drift; when code changes move cited lines, fix the citations in both languages.
- Mermaid: dark-mode colors (`#2d333b` fills, `#6d5dfc` borders, `#e6edf3` text), `<br>` not `<br/>`
  (Vue compiler), and `autonumber` in sequence diagrams.

## Boundaries

- **Ask first:** changing `.vitepress/config/` (navigation) or the theme.
- **Never:** delete pages without discussion.
