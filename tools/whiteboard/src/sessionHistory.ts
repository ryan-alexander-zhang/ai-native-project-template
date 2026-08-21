import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { SessionKind, SessionStatus } from './sessionManager.ts'

/**
 * Where a session's history lands, relative to the repo root (spec-00001-FR-54):
 * under `.whiteboard/`, which the repo's own .gitignore excludes, so a transcript
 * — process state, not a document — never becomes a commit (decision-00008 §2
 * 第 3 条).
 */
export const SESSIONS_DIR = '.whiteboard/sessions'

/**
 * A history id is a session id, and a session id is what names its two files.
 * Anything else is not one: no separator can reach this far, so no request can
 * read a file outside the sessions directory.
 */
const HISTORY_ID = /^[0-9A-Za-z._-]+$/

/** What a finished session was, as the list and the meta payload carry it (spec-00001-FR-54). */
export interface SessionHistoryMeta {
  id: string
  kind: SessionKind
  /** The document it was about — the source of an advance, the subject of the other kinds. */
  docId: string
  /** Which agent of the flow config ran it (spec-00001-FR-55). */
  agent: string
  startedAt: string
  endedAt: string
  status: SessionStatus
  exitCode?: number
}

export interface SessionHistoryEntry {
  meta: SessionHistoryMeta
  /** The whole terminal transcript, as plain text — its first reader is a person. */
  transcript: string
}

function historyPath(repoRoot: string, id: string, extension: string): string {
  return join(repoRoot, SESSIONS_DIR, `${id}.${extension}`)
}

/**
 * Write one session's history: metadata as JSON, transcript as plain text. It
 * throws whatever the file system refused with — the caller is the wrap-up, and
 * its business is that this failure blocks nothing (spec-00001-AC-54.3).
 */
export function writeSessionHistory(repoRoot: string, meta: SessionHistoryMeta, transcript: string): void {
  mkdirSync(join(repoRoot, SESSIONS_DIR), { recursive: true })
  writeFileSync(historyPath(repoRoot, meta.id, 'json'), `${JSON.stringify(meta, null, 2)}\n`)
  writeFileSync(historyPath(repoRoot, meta.id, 'log'), transcript)
}

/**
 * Every session on disk, newest first (spec-00001-FR-54). The files are the
 * whole store, so a restart changes nothing here; an entry that cannot be read
 * back is left out rather than failing the list — one broken file must not cost
 * the user the rest of the history.
 */
export function listSessionHistory(repoRoot: string): SessionHistoryMeta[] {
  let entries: string[]
  try {
    entries = readdirSync(join(repoRoot, SESSIONS_DIR))
  } catch {
    return []
  }
  return entries
    .filter((entry) => entry.endsWith('.json'))
    .flatMap((entry) => {
      const meta = readMeta(repoRoot, entry.slice(0, -'.json'.length))
      return meta ? [meta] : []
    })
    .sort((left, right) => right.startedAt.localeCompare(left.startedAt))
}

/** One session's metadata and its transcript in full, or nothing when there is no such session. */
export function readSessionHistory(repoRoot: string, id: string): SessionHistoryEntry | undefined {
  const meta = readMeta(repoRoot, id)
  if (!meta) return undefined
  let transcript = ''
  try {
    transcript = readFileSync(historyPath(repoRoot, id, 'log'), 'utf8')
  } catch {
    // A session that printed nothing, or a log the write never got to: the
    // metadata is still the honest answer.
  }
  return { meta, transcript }
}

function readMeta(repoRoot: string, id: string): SessionHistoryMeta | undefined {
  if (!HISTORY_ID.test(id)) return undefined
  try {
    return JSON.parse(readFileSync(historyPath(repoRoot, id, 'json'), 'utf8')) as SessionHistoryMeta
  } catch {
    return undefined
  }
}
