import { spawnSync } from 'node:child_process'
import { accessSync, constants } from 'node:fs'
import { spawn } from 'node-pty'
import { KILL_GRACE_MS, killLadder } from './killLadder.ts'
import type { PtyProcess, SpawnPty } from './sessionManager.ts'

/**
 * Why this command cannot be run, or nothing when it can. A pty forks before
 * exec, so a missing command surfaces as an ordinary non-zero exit rather than a
 * spawn error: checking first is what lets the board tell «the agent never
 * started» from «the agent ran and failed» (spec-00001-FR-16).
 *
 * Exported because the unified submit has to ask the **same** question before it
 * moves a document's status (design-00001 §12.4 第 3 步, spec-00007-AC-7.4): a
 * second reading of «can this start» would let a submit write the transition and
 * then be refused by this one, which is exactly the landing FR-7 forbids.
 */
export function unrunnable(command: string): string | undefined {
  if (command.includes('/') || command.includes('\\')) {
    try {
      accessSync(command, constants.X_OK)
    } catch {
      return `agent command is not executable: ${command}`
    }
    return undefined
  }
  const finder = process.platform === 'win32' ? 'where' : 'which'
  if (spawnSync(finder, [command]).status !== 0) {
    return `agent command not found on PATH: ${command}`
  }
  return undefined
}

function requireExecutable(command: string): void {
  const problem = unrunnable(command)
  if (problem !== undefined) throw new Error(problem)
}

/** The pty spawner, with the grace as a parameter so a test can wait it out. */
export function ptySpawner(graceMs: number = KILL_GRACE_MS): SpawnPty {
  return (command, args, cwd, env): PtyProcess => {
    requireExecutable(command)
    // A size to start on, not the size it stays: the terminal that attaches
    // reports its own and the session is resized to it (spec-00001-FR-12).
    const pty = spawn(command, args, { name: 'xterm-color', cols: 120, rows: 30, cwd, env })
    // First SIGHUP, node-pty's own default — a CLI that listens gets to finish —
    // then SIGKILL once the grace is up (issue-00012). Signalling a process that
    // has already gone is a no-op node-pty swallows.
    const ladder = killLadder((signal) => pty.kill(signal), 'SIGHUP', graceMs)
    // Whichever signal ended it, the clock it was racing stops here, so nothing
    // is left ticking over a process that is already gone.
    pty.onExit(ladder.settle)
    return {
      onData: (listener) => {
        pty.onData(listener)
      },
      onExit: (listener) => {
        pty.onExit(({ exitCode }) => listener({ exitCode }))
      },
      write: (data) => pty.write(data),
      resize: (cols, rows) => pty.resize(cols, rows),
      kill: ladder.kill,
    }
  }
}

export const spawnPty: SpawnPty = ptySpawner()
