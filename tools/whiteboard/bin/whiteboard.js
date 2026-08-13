#!/usr/bin/env node
import { join } from 'node:path'
import { ConfigError, loadFlowConfig } from '../src/config.ts'
import { Board } from '../src/server.ts'

const repoRoot = process.cwd()
const port = Number(process.env.PORT ?? 4173)

try {
  const config = loadFlowConfig(join(repoRoot, 'whiteboard.config.yaml'))
  const board = new Board({ repoRoot, docsDir: join(repoRoot, 'docs'), config })
  board.listen(port)
  console.log(`whiteboard: http://localhost:${port} — docs of ${repoRoot}`)
} catch (error) {
  // A missing or invalid flow config is fatal: there is no built-in default (spec-00001-FR-15).
  console.error(error instanceof ConfigError ? error.message : error)
  process.exit(1)
}
