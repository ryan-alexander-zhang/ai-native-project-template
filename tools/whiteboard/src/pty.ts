import { spawnSync } from 'node:child_process'
import { accessSync, constants } from 'node:fs'
import { spawn } from 'node-pty'
import { KILL_GRACE_MS, killLadder } from './killLadder.ts'
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

/** The pty spawner, with the grace as a parameter so a test can wait it out. */
export function ptySpawner(graceMs: number = KILL_GRACE_MS): SpawnPty {
  return (command, args, cwd): PtyProcess => {
    requireExecutable(command)
    // A size to start on, not the size it stays: the terminal that attaches
    // reports its own and the session is resized to it (spec-00001-FR-12).
    const pty = spawn(command, args, { name: 'xterm-color', cols: 120, rows: 30, cwd })
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
