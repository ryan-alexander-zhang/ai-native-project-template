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

export const spawnPty: SpawnPty = (command, args, cwd): PtyProcess => {
  requireExecutable(command)
  const pty = spawn(command, args, { name: 'xterm-color', cols: 120, rows: 30, cwd })
  return {
    onData: (listener) => {
      pty.onData(listener)
    },
    onExit: (listener) => {
      pty.onExit(({ exitCode }) => listener({ exitCode }))
    },
    write: (data) => pty.write(data),
    kill: () => pty.kill(),
  }
}
