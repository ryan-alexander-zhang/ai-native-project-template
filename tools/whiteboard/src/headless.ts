import { spawn } from 'node:child_process'
import { KILL_GRACE_MS, killLadder } from './killLadder.ts'

/**
 * The non-interactive form of an agent CLI (design-00001 §10.1): given a
 * question it prints an answer and exits — no pty, no terminal, nothing to type
 * at. An ask thread is one such call per question, its follow-ups resuming the
 * first one's conversation by the CLI's own resume id (spec-00005-FR-8).
 *
 * The declaration holds the whole flag set of both forms. The agent entry's
 * `args` are deliberately never mixed in: they are the interactive form's flags,
 * and what an interactive flag does to a print-mode call is per-CLI unknown.
 */
export interface HeadlessConfig {
  /** The argv of a first call; `{question}` stands for the instruction and the question. */
  first: string[]
  /** The argv of a follow-up; `{session}` stands for the resume id the first call gave. */
  resume: string[]
  /** Which built-in reading of the CLI's stdout the answer and the resume id come from. */
  capture: CaptureName
}

/**
 * The captures the code holds: built into the code and only *named* by the flow
 * config, the same way the clarifiable type set is (design-00001 §10.1). There
 * is one — claude's `--output-format json` — so the reader below needs no
 * dispatch; a second one is what would put a switch there.
 */
export const CAPTURES = ['claude-json'] as const

export type CaptureName = (typeof CAPTURES)[number]

/** What one finished call yielded: the answer to show, and the id its follow-up resumes. */
export interface Captured {
  answer: string
  resumeId: string
}

export const QUESTION_PLACEHOLDER = '{question}'
export const SESSION_PLACEHOLDER = '{session}'

/**
 * Read `claude-json` stdout (design-00001 §10.1): the answer is `.result` —
 * plain text, already free of the control sequences spec-00005-FR-3 asks to be
 * stripped — and the resume id is `.session_id`. Output that will not parse, or
 * that carries neither field, is no answer at all, which the caller reads as the
 * call having failed.
 */
export function capture(stdout: string): Captured | undefined {
  let parsed: { result?: unknown; session_id?: unknown; is_error?: unknown }
  try {
    parsed = JSON.parse(stdout) as typeof parsed
  } catch {
    return undefined
  }
  // A call claude reports as an error is no answer, whatever it put in
  // `.result` — that field then holds the error, and exiting zero is how the
  // CLI reports an API failure. Read as an answer it would be filed on the
  // thread as one; refused here, the question lands `failed` and the raw stdout
  // is what the history keeps (spec-00005-FR-7).
  if (parsed?.is_error === true) return undefined
  if (typeof parsed?.result !== 'string' || typeof parsed.session_id !== 'string') return undefined
  return { answer: parsed.result, resumeId: parsed.session_id }
}

/**
 * The argv one call runs on: the declared form with its placeholders filled.
 * A resume id picks the resume form and a call without one the first form,
 * which is the whole of «a new thread or a follow-up» at this level. The whole
 * instruction goes in as a single argv element and no shell is involved, so
 * there is no escaping to get wrong (design-00001 §10.1).
 *
 * One pass over each argument, never two: the question is a person's own words,
 * and a second pass would go looking for placeholders inside what it had just
 * substituted — a question that mentions `{session}` would come out gutted.
 */
export function headlessArgs(headless: HeadlessConfig, question: string, resumeId?: string): string[] {
  const template = resumeId === undefined ? headless.first : headless.resume
  const pattern = new RegExp(`\\${QUESTION_PLACEHOLDER}|\\${SESSION_PLACEHOLDER}`, 'g')
  return template.map((arg) =>
    arg.replace(pattern, (found) => (found === QUESTION_PLACEHOLDER ? question : (resumeId ?? ''))),
  )
}

/** A running headless call: its output captured rather than streamed to a terminal. */
export interface HeadlessProcess {
  onStdout(listener: (chunk: string) => void): void
  onStderr(listener: (chunk: string) => void): void
  /** Fires exactly once, whichever way the call ended — a failure to start included. */
  onExit(listener: (event: { exitCode: number }) => void): void
  kill(): void
}

/** The second spawn seam (design-00001 §10.1), beside `SpawnPty` and never through it. */
export type SpawnHeadless = (command: string, args: string[], cwd: string) => HeadlessProcess

/**
 * How long after the process itself is gone the call waits for its pipes to
 * drain before ending anyway. `close` is the end event — that is what makes a
 * long answer whole — but a CLI that left a child of its own holding stdout
 * would never let it fire, and a call that never ends hangs the stop and the
 * shutdown waiting on it (spec-00003-FR-9). Long enough for a pipe to flush,
 * short enough that a Stop still answers like a button.
 */
const DRAIN_MS = 1_000

/** The headless spawner, with the grace as a parameter so a test can wait it out. */
export function headlessSpawner(graceMs: number = KILL_GRACE_MS): SpawnHeadless {
  return (command, args, cwd): HeadlessProcess => {
    const child = spawn(command, args, { cwd })
    const exits: Array<(event: { exitCode: number }) => void> = []
    let drain: NodeJS.Timeout | undefined
    // SIGTERM first, not SIGHUP: hanging up is what a terminal does, and this
    // call has none (design-00001 §10.3).
    const ladder = killLadder((signal) => void child.kill(signal as NodeJS.Signals), 'SIGTERM', graceMs)
    let gone = false
    const end = (exitCode: number) => {
      if (gone) return
      gone = true
      ladder.settle()
      clearTimeout(drain)
      for (const listener of exits) listener({ exitCode })
    }
    // `close`, not `exit`: the process is gone at `exit` but its pipes are not
    // yet drained, and an answer big enough to arrive in several chunks would be
    // captured half-written and then read as unparsable — a failed call with the
    // answer sitting in the buffer (spec-00005-FR-3).
    child.on('close', (code) => end(code ?? 1))
    // …but only for as long as draining can take: a process whose own child
    // inherited stdout keeps the pipes open after it dies, and `close` would
    // then never come at all.
    child.on('exit', (code) => {
      drain ??= setTimeout(() => end(code ?? 1), DRAIN_MS).unref()
    })
    // A CLI that is not there never runs, and a call that never ran is a call
    // that failed — one end state, so the thread has one outcome to record
    // rather than a fourth kind of ending (design-00001 §10.2). The guard above
    // is what keeps the `close` that follows an `error` from ending it twice.
    child.on('error', (cause) => {
      child.stderr?.emit('data', `whiteboard: could not start the agent — ${cause.message}\n`)
      end(1)
    })
    return {
      onStdout: (listener) => void child.stdout?.on('data', (chunk: Buffer | string) => listener(chunk.toString())),
      onStderr: (listener) => void child.stderr?.on('data', (chunk: Buffer | string) => listener(chunk.toString())),
      onExit: (listener) => void exits.push(listener),
      kill: ladder.kill,
    }
  }
}

export const spawnHeadless: SpawnHeadless = headlessSpawner()
