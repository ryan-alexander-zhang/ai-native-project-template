import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdirSync, realpathSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { makeRepo } from './helpers.ts'

const ENTRY = new URL('../bin/whiteboard.js', import.meta.url).pathname

/** Boot the CLI the way a user does: from somewhere in the repo, on a free port. */
function boot(cwd: string) {
  return spawnSync(process.execPath, [ENTRY], {
    cwd,
    encoding: 'utf8',
    env: { ...process.env, PORT: '0' },
    timeout: 20_000,
  })
}

const MINIMAL_CONFIG = `types:
  idea: { kind: living }
relations: [parent]
flow: {}
focus:
  idea: is it worth doing, and for whom
agents:
  claude: { command: claude }
`

describe('starting the board', () => {
  // spec-00001-AC-15.1
  it('refuses to start without a flow config, naming the path it looked for', () => {
    const { repoRoot } = makeRepo({})

    const result = boot(repoRoot)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain(join(realpathSync(repoRoot), 'whiteboard.config.yaml'))
    expect(result.stderr).toContain('no flow config at')
  })

  it('starts from a subdirectory by finding the repo that owns the config', () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(join(repoRoot, 'whiteboard.config.yaml'), MINIMAL_CONFIG)
    const nested = join(repoRoot, 'tools', 'whiteboard')
    mkdirSync(nested, { recursive: true })

    const result = readFirstLine(nested)

    expect(result.status).toBe(0)
    expect(result.stdout).toContain(realpathSync(repoRoot))
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

  // spec-00001-AC-48.4 at the boundary the criterion is written at: booting
  it('refuses to start when a clarifiable type carries no focus line, naming the type', () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(join(repoRoot, 'whiteboard.config.yaml'), MINIMAL_CONFIG.replace(/focus:\n  idea: [^\n]+\n/, ''))

    const result = boot(repoRoot)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('focus.idea')
  })

  // spec-00001-AC-48.2 and AC-48.6, same boundary: the type is named either way
  it('refuses to start on a blank or multi-line focus line, naming the type', () => {
    for (const line of ['  idea: ""\n', '  idea: "worth doing\\nfor whom"\n']) {
      const { repoRoot } = makeRepo({})
      writeFileSync(
        join(repoRoot, 'whiteboard.config.yaml'),
        MINIMAL_CONFIG.replace(/  idea: is it worth doing, and for whom\n/, line),
      )

      const result = boot(repoRoot)

      expect(result.status).toBe(1)
      expect(result.stderr).toContain('focus.idea')
    }
  })

  // spec-00001-AC-48.5
  it('refuses to start when a type that is not clarifiable carries a focus line, naming it', () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(join(repoRoot, 'whiteboard.config.yaml'), MINIMAL_CONFIG.replace('focus:\n', 'focus:\n  record: x\n'))

    const result = boot(repoRoot)

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('focus.record')
  })

  it('reports a port it cannot listen on instead of crashing', () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(join(repoRoot, 'whiteboard.config.yaml'), MINIMAL_CONFIG)
    const held = createServer().listen(0)
    const port = String((held.address() as { port: number }).port)

    const result = spawnSync(process.execPath, [ENTRY], {
      cwd: repoRoot,
      encoding: 'utf8',
      env: { ...process.env, PORT: port },
      timeout: 20_000,
    })
    held.close()

    expect(result.status).toBe(1)
    expect(result.stderr).toContain(`cannot listen on port ${port}`)
    expect(result.stdout).not.toContain('http://localhost')
  })

  /**
   * spec-00003-AC-9.3 at the entry point, and no further: a normal signal is
   * handled rather than killing the board where it stands, so there is a
   * shutdown to run at all — the exit is the board's own, code 0 and no signal.
   * What the shutdown then does per session is the Board's, proved at that level
   * in server.test.ts; nothing is running here to wrap up.
   */
  it('handles SIGTERM itself instead of being killed by it', async () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(join(repoRoot, 'whiteboard.config.yaml'), MINIMAL_CONFIG)
    const child = spawn(process.execPath, [ENTRY], { cwd: repoRoot, env: { ...process.env, PORT: '0' } })

    // Only once it is listening: a signal before that has no server to close.
    await new Promise((resolve) => child.stdout.once('data', resolve))
    child.kill('SIGTERM')

    const ended = await new Promise<[number | null, string | null]>((resolve) => {
      child.on('exit', (code, signal) => resolve([code, signal]))
    })
    expect(ended).toEqual([0, null])
  }, 20_000)

  it('starts and reports its address on a valid config', () => {
    const { repoRoot } = makeRepo({})
    writeFileSync(join(repoRoot, 'whiteboard.config.yaml'), MINIMAL_CONFIG)

    const result = readFirstLine(repoRoot)

    expect(result.status).toBe(0)
    expect(result.stdout).toContain('whiteboard: http://localhost:')
    expect(result.stdout).toContain(realpathSync(repoRoot))
  })
})

/** The board keeps running, so take its first line of output and stop it. */
function readFirstLine(cwd: string) {
  return spawnSync(
    process.execPath,
    [
      '-e',
      `const { spawn } = require('child_process');
       const child = spawn(process.execPath, [${JSON.stringify(ENTRY)}], { cwd: ${JSON.stringify(cwd)}, env: { ...process.env, PORT: '0' } });
       child.stdout.once('data', (data) => { process.stdout.write(data); child.kill(); process.exit(0) });
       child.stderr.on('data', (data) => process.stderr.write(data));
       child.on('exit', () => process.exit(1));`,
    ],
    { encoding: 'utf8', timeout: 20_000 },
  )
}
