/**
 * Regenerates every `<doc path="...">` block of llms-full.txt from its source page (frontmatter stripped).
 * Usage: node scripts/sync-llms-full.mjs [--check]
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const target = join(root, 'llms-full.txt')
const check = process.argv.includes('--check')

const stripFrontmatter = (text) => text.replace(/^---\n[\s\S]*?\n---\n+/, '')

const current = readFileSync(target, 'utf8')
const missing = []
const next = current.replace(
  /(<doc title="[^"]*" path="([^"]+)">\n)([\s\S]*?)(\n<\/doc>)/g,
  (block, open, path, _body, close) => {
    const source = join(root, path)
    if (!existsSync(source)) {
      missing.push(path)
      return block
    }
    return open + stripFrontmatter(readFileSync(source, 'utf8')).trimEnd() + close
  }
)

if (missing.length) {
  console.error(`Missing source pages: ${missing.join(', ')}`)
  process.exit(1)
}
if (check) {
  if (next !== current) {
    console.error('llms-full.txt is out of date; run: pnpm run sync:llms')
    process.exit(1)
  }
  console.log('llms-full.txt is up to date')
} else {
  writeFileSync(target, next)
  console.log('llms-full.txt synced')
}
