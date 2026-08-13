import { spawnSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { makeRepo } from './helpers.ts'

const ENTRY = new URL('../bin/whiteboard.js', import.meta.url).pathname

/** Boot the CLI the way a user does: from a repo root, on a free port. */
function boot(repoRoot: string) {
  return spawnSync(process.execPath, [ENTRY], {
    cwd: repoRoot,
    encoding: 'utf8',
    env: { ...process.env, PORT: '0' },
    timeout: 20_000,
  })
}

describe('starting the board', () => {
  // spec-00001-AC-15.1
  it('refuses to start without a flow config, naming the path it looked for', () => {
    const { repoRoot } = makeRepo({})

    const result = boot(repoRoot)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain(join(repoRoot, 'whiteboard.config.yaml'))
    expect(result.stderr).toContain('no flow config at')
  })

  // spec-00001-AC-15.2
  it('refuses to start on an invalid flow config, naming the offending entry', () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(
      join(repoRoot, 'whiteboard.config.yaml'),
      `types:
  idea: { kind: living }
relations: [parent]
flow:
  idea:
    - { next: memo, carry: parent }
agents:
  claude: { command: claude }
`,
    )

    const result = boot(repoRoot)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('flow.idea[0].next')
    expect(result.stderr).toContain('"memo"')
  })

  it('starts and reports its address on a valid config', () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(
      join(repoRoot, 'whiteboard.config.yaml'),
      `types:
  idea: { kind: living }
relations: [parent]
flow: {}
agents:
  claude: { command: claude }
`,
    )

    // The board keeps running, so read its first line and stop it.
    const result = spawnSync(
      process.execPath,
      ['-e', `const { spawn } = require('child_process');
        const child = spawn(process.execPath, [${JSON.stringify(ENTRY)}], { cwd: ${JSON.stringify(repoRoot)}, env: { ...process.env, PORT: '0' } });
        child.stdout.once('data', (data) => { process.stdout.write(data); child.kill(); process.exit(0) });
        child.on('exit', () => process.exit(1));`],
      { encoding: 'utf8', timeout: 20_000 },
    )

    expect(result.status).toBe(0)
    expect(result.stdout).toContain('whiteboard: http://localhost:')
    expect(result.stdout).toContain(repoRoot)
  })
})
