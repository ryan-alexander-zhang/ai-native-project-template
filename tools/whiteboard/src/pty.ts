import { spawnSync } from 'node:child_process'
import { accessSync, constants } from 'node:fs'
import { spawn } from 'node-pty'
import type { PtyProcess, SpawnPty } from './sessionManager.ts'

/**
 * A pty forks before exec, so a missing command surfaces as an ordinary non-zero
 * exit rather than a spawn error. Checking first is what lets the board tell
 * "the agent never started" from "the agent ran and failed" (spec-00001-FR-16).
 */
function requireExecutable(command: string): void {
  if (command.includes('/') || command.includes('\\')) {
    try {
      accessSync(command, constants.X_OK)
    } catch {
      throw new Error(`agent command is not executable: ${command}`)
    }
    return
  }
  const finder = process.platform === 'win32' ? 'where' : 'which'
  if (spawnSync(finder, [command]).status !== 0) {
    throw new Error(`agent command not found on PATH: ${command}`)
  }
}

/**
 * How long a session gets on the polite signal before the unignorable one
 * follows (spec-00001-AC-49.10, issue-00012). Seconds, not tens of them: it is
 * long enough for a CLI to hear SIGHUP and write out what it was holding, and
 * short enough that a Stop the user is waiting on still answers like a button.
 */
export const KILL_GRACE_MS = 3_000

/** The pty spawner, with the grace as a parameter so a test can wait it out. */
export function ptySpawner(graceMs: number = KILL_GRACE_MS): SpawnPty {
  return (command, args, cwd): PtyProcess => {
    requireExecutable(command)
    // A size to start on, not the size it stays: the terminal that attaches
    // reports its own and the session is resized to it (spec-00001-FR-12).
    const pty = spawn(command, args, { name: 'xterm-color', cols: 120, rows: 30, cwd })
    let escalation: NodeJS.Timeout | undefined
    // Whichever signal ended it, the clock it was racing stops here, so nothing
    // is left ticking over a process that is already gone.
    pty.onExit(() => clearTimeout(escalation))
    return {
      onData: (listener) => {
        pty.onData(listener)
      },
      onExit: (listener) => {
        pty.onExit(({ exitCode }) => listener({ exitCode }))
      },
      write: (data) => pty.write(data),
      resize: (cols, rows) => pty.resize(cols, rows),
      /**
       * First SIGHUP, node-pty's default — a CLI that listens gets to finish. A
       * process that ignores it is killed outright once the grace is up, which
       * is what makes waiting for the exit bounded (issue-00012). Signalling a
       * process that has already gone is a no-op node-pty swallows, and the
       * escalation is armed once: asking twice must not restart the clock.
       */
      kill: () => {
        pty.kill()
        escalation ??= setTimeout(() => pty.kill('SIGKILL'), graceMs).unref()
      },
    }
  }
}

export const spawnPty: SpawnPty = ptySpawner()
