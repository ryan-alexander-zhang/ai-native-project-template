import { existsSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { type HeadlessConfig, capture, fillModel, headlessArgs, headlessSpawner } from '../src/headless.ts'
import { childEnv } from '../src/sessionManager.ts'
import { SESSION_WAIT } from './helpers.ts'

/** What the seam is handed when the entry declares no `env` of its own: the board's own environment (spec-00009-FR-1). */
const BOARD_ENV = childEnv({ name: 'test', command: 'node', args: [] })

vi.setConfig({ testTimeout: 30_000 })

const DECLARED: HeadlessConfig = {
  first: ['-p', '--permission-mode', 'plan', '{question}'],
  resume: ['-p', '--permission-mode', 'plan', '--resume', '{session}', '{question}'],
  capture: 'claude-json',
}

/** Run one call to its end, collecting everything it printed (design-00001 §10.1). */
function run(command: string, args: string[], graceMs?: number) {
  const call = headlessSpawner(graceMs)(command, args, tmpdir(), BOARD_ENV)
  let stdout = ''
  let stderr = ''
  const ended = new Promise<number>((resolve) => call.onExit(({ exitCode }) => resolve(exitCode)))
  call.onStdout((chunk) => {
    stdout += chunk
  })
  call.onStderr((chunk) => {
    stderr += chunk
  })
  return {
    ended,
    kill: () => call.kill(),
    get printed() {
      return { stdout, stderr }
    },
  }
}

describe('capture', () => {
  // design-00001 §10.1: the answer is `.result`, the resume id is `.session_id`
  it('reads the answer and the resume id out of claude-json stdout', () => {
    expect(capture(JSON.stringify({ result: 'because', session_id: 'cli-7', cost: 1 }))).toEqual({
      answer: 'because',
      resumeId: 'cli-7',
    })
  })

  /**
   * Output the capture cannot read is no answer at all, which the wrap-up reads
   * as the call having failed — the same end as a non-zero exit
   * (design-00001 §10.1, spec-00005-FR-7).
   */
  it('gives nothing back for output it cannot read as an answer', () => {
    expect(capture('not json at all')).toBeUndefined()
    expect(capture('')).toBeUndefined()
    expect(capture('null')).toBeUndefined()
    expect(capture(JSON.stringify({ result: 'because' }))).toBeUndefined()
    expect(capture(JSON.stringify({ session_id: 'cli-7' }))).toBeUndefined()
  })

  /**
   * A call claude reports as an error is no answer, whatever it put in
   * `.result` — the CLI exits zero and says so in the payload, so the exit code
   * alone would file an API failure on the thread as the answer to the question
   * (spec-00005-FR-7).
   */
  it('gives nothing back for a call the CLI itself reports as an error', () => {
    const errored = JSON.stringify({ is_error: true, result: 'API Error: overloaded', session_id: 'cli-7' })

    expect(capture(errored)).toBeUndefined()
    // …and `is_error: false` is the ordinary answer it says it is.
    expect(capture(JSON.stringify({ is_error: false, result: 'because', session_id: 'cli-7' }))).toEqual({
      answer: 'because',
      resumeId: 'cli-7',
    })
  })
})

describe('headlessArgs', () => {
  it('fills the first form with the payload, and never mentions a session', () => {
    expect(headlessArgs(DECLARED, 'the whole instruction and the question')).toEqual([
      '-p',
      '--permission-mode',
      'plan',
      'the whole instruction and the question',
    ])
  })

  // A resume id is what picks the resume form; that is the whole of the choice.
  it('fills the resume form with the resume id and the follow-up', () => {
    expect(headlessArgs(DECLARED, 'and the third?', 'cli-7')).toEqual([
      '-p',
      '--permission-mode',
      'plan',
      '--resume',
      'cli-7',
      'and the third?',
    ])
  })

  it('substitutes a payload that itself looks like a placeholder', () => {
    expect(headlessArgs(DECLARED, '{session}')).toEqual(['-p', '--permission-mode', 'plan', '{session}'])
  })

  /**
   * spec-00009-FR-1: `{model}` is filled in the **same** pass as the other two,
   * for the reason the pass is single at all — a question that says `{model}`
   * is a person's own words and comes out as they wrote it.
   */
  it('fills the model in the same pass, leaving a payload that says {model} alone', () => {
    const declared: HeadlessConfig = { ...DECLARED, first: ['-p', '--model', '{model}', '{question}'] }

    expect(headlessArgs(declared, 'what does {model} mean?', undefined, 'm1')).toEqual([
      '-p',
      '--model',
      'm1',
      'what does {model} mean?',
    ])
  })
})

/** The interactive form's own substitution (spec-00009-FR-1, design-00001 §13.4). */
describe('fillModel', () => {
  it('fills every {model}, inside an element as well as whole', () => {
    expect(fillModel(['--model={model}', '-c', 'model={model}'], 'm1')).toEqual(['--model=m1', '-c', 'model=m1'])
  })

  // An entry with no model has no placeholder either — the entry check saw to that.
  it('leaves an argv exactly as it is when the entry names no model', () => {
    const argv = ['--yolo']

    expect(fillModel(argv)).toBe(argv)
  })
})

describe('headlessSpawner', () => {
  it('captures stdout and stderr apart, and reports the exit code', async () => {
    const call = run('node', ['-e', "process.stdout.write('an answer'); process.stderr.write('a warning'); process.exit(3)"])

    expect(await call.ended).toBe(3)
    expect(call.printed).toEqual({ stdout: 'an answer', stderr: 'a warning' })
  })

  /**
   * The call ends on `close`, not on `exit`: an answer too big for one pipe
   * buffer arrives in several chunks, and the last of them lands after the
   * process itself is gone. Ending at `exit` would hand the capture a truncated
   * JSON object, which reads as no answer at all — a failed call with the answer
   * half in hand (spec-00005-FR-3).
   */
  it('has the whole of a long answer in hand by the time the call ends', async () => {
    const answer = JSON.stringify({ result: 'x'.repeat(400_000), session_id: 'cli-7' })
    const call = run('node', ['-e', `process.stdout.write(${JSON.stringify(answer)})`])

    expect(await call.ended).toBe(0)
    expect(call.printed.stdout).toHaveLength(answer.length)
    expect(capture(call.printed.stdout)?.resumeId).toBe('cli-7')
  })

  /**
   * A CLI that is not there never ran, and a call that never ran is a call that
   * failed: one end state, so the thread has one outcome to record
   * (design-00001 §10.2).
   */
  it('ends a call whose command is not there as a failed one, saying why', async () => {
    const call = run('definitely-not-an-agent-cli', ['-p', 'why?'])

    expect(await call.ended).toBe(1)
    expect(call.printed.stderr).toContain('could not start the agent')
  })

  it('ends a call the board stopped', async () => {
    const call = run('node', ['-e', 'setTimeout(() => {}, 60000)'])

    call.kill()

    expect(await call.ended).toBeGreaterThanOrEqual(0)
  })

  /**
   * The ladder this seam holds itself (design-00001 §10.3): SIGTERM first, so a
   * CLI that listens gets to finish, and SIGKILL once the grace is up — which is
   * what makes waiting for the exit bounded.
   *
   * The `sleep` this shell leaves behind inherited its stdout, so the pipes stay
   * open after the shell is killed and `close` never comes: what this also pins
   * is that the call ends all the same, because a stop that never finishes hangs
   * the shutdown waiting on it (spec-00003-FR-9).
   */
  it('kills a call that ignores the polite signal once the grace is up', async () => {
    const pidFile = join(tmpdir(), `whiteboard-deaf-to-term-${process.pid}.pid`)
    rmSync(pidFile, { force: true })
    const call = run('sh', ['-c', `trap '' TERM; echo $$ > ${pidFile}; sleep 60`], 200)
    await vi.waitFor(() => expect(existsSync(pidFile)).toBe(true), SESSION_WAIT)
    const pid = Number(readFileSync(pidFile, 'utf8').trim())

    call.kill()
    await call.ended

    expect(() => process.kill(pid, 0)).toThrowError()
    rmSync(pidFile, { force: true })
  })
})
