import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import type { SelectionAnchor } from '../src/annotationAnchor.ts'
import {
  ANNOTATIONS_DIR,
  AnnotationConflictError,
  AnnotationStore,
  NoAnnotationError,
} from '../src/annotationStore.ts'
import { findRepoRoot } from '../src/config.ts'
import { WorkflowError } from '../src/workflow.ts'
import { git, makeRepo } from './helpers.ts'

const DOC = 'spec-00007-doc-annotations'
const ANCHOR: SelectionAnchor = { selected: 'the gate', before: 'before ', after: ' after' }
const OTHER: SelectionAnchor = { selected: 'another line', before: 'x', after: 'y' }

function storeOn() {
  const { repoRoot } = makeRepo({})
  return { repoRoot, store: new AnnotationStore(repoRoot) }
}

const question = { type: 'question' as const, text: 'why two gates?', anchor: ANCHOR }
const issue = { type: 'issue' as const, text: 'say which gate', anchor: ANCHOR }

function fileOf(repoRoot: string, docId = DOC): string {
  return join(repoRoot, ANNOTATIONS_DIR, `${docId}.json`)
}

describe('the annotation list on disk', () => {
  // spec-00007-AC-3.3 — the list is the record: a new store on the same repo
  // reads back what the last one wrote, which is what a restart is
  it('reads back what it wrote, with the quote derived from the anchor', async () => {
    const { repoRoot, store } = storeOn()

    const added = await store.add(DOC, question)

    expect(added).toMatchObject({ id: 'n-1', type: 'question', text: 'why two gates?', state: 'pending' })
    expect(added.quote).toBe('the gate')
    expect(added.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T.+Z$/)
    expect(new AnnotationStore(repoRoot).read(DOC).annotations).toEqual([added])
  })

  /**
   * spec-00007-AC-3.2 — annotations are board state: the file lives beside the
   * ask lists under `.whiteboard/`, which this repo's own .gitignore excludes, and
   * nothing of it is tracked.
   */
  // spec-00007-AC-3.2
  it('keeps the file out of git, and out of the docs tree', async () => {
    const { repoRoot, store } = storeOn()

    await store.add(DOC, question)

    expect(existsSync(fileOf(repoRoot))).toBe(true)
    expect(git(repoRoot, 'ls-files')).not.toContain('.whiteboard')
    expect(existsSync(join(repoRoot, 'docs', `${DOC}.json`))).toBe(false)
  })

  // The other half of AC-3.2, on this repo itself: the directory the store writes
  // into is one this repo's .gitignore already excludes, so nothing can be added.
  it('writes into a directory this repo ignores', () => {
    const repoRoot = findRepoRoot(process.cwd())

    expect(git(repoRoot, 'check-ignore', `${ANNOTATIONS_DIR}/${DOC}.json`).trim()).toBe(
      `${ANNOTATIONS_DIR}/${DOC}.json`,
    )
  })

  // spec-00007-AC-3.1 — a change and a delete, and what is left afterwards
  it('changes one annotation and drops another, keeping the rest', async () => {
    const { repoRoot, store } = storeOn()
    await store.add(DOC, question)
    const second = await store.add(DOC, issue)
    await store.add(DOC, { ...question, text: 'and the third?' })

    await store.patch(DOC, 'n-1', { text: 'why two gates, really?' })
    await store.remove(DOC, second.id)

    const list = new AnnotationStore(repoRoot).read(DOC)
    expect(list.annotations.map((annotation) => annotation.id)).toEqual(['n-1', 'n-3'])
    expect(list.annotations[0]!.text).toBe('why two gates, really?')
  })

  // The type may be changed as well as the text (spec-00007-FR-3)
  it('changes an annotation’s type', async () => {
    const { store } = storeOn()
    await store.add(DOC, question)

    expect((await store.patch(DOC, 'n-1', { type: 'issue' })).type).toBe('issue')
  })

  /**
   * spec-00007-AC-3.4 — the way out of an orphaned annotation: a new selection
   * replaces both the anchor and the quote, and clears the failure mark.
   */
  // spec-00007-AC-3.4
  it('replaces the anchor and the quote on a re-anchor, and clears the orphan mark', async () => {
    const { store } = storeOn()
    await store.add(DOC, question)
    await store.update(DOC, (list) => {
      list.annotations[0]!.orphan = 'missing'
    })

    const patched = await store.patch(DOC, 'n-1', { anchor: OTHER })

    expect(patched.anchor).toEqual(OTHER)
    expect(patched.quote).toBe('another line')
    expect(patched.orphan).toBeUndefined()
  })

  /**
   * An id is never handed out twice, whatever has since been deleted: a submit's
   * answer names ids, and an id that came round again would silently re-bind that
   * answer — the owner reading «n-2 was held back» would find another passage
   * under it. Reading the highest **surviving** id is not enough for that, which
   * is why the counter is kept.
   */
  it('never hands out an annotation id twice, whatever has been deleted since', async () => {
    const { repoRoot, store } = storeOn()
    await store.add(DOC, question)
    await store.add(DOC, issue)

    await store.remove(DOC, 'n-2')
    expect((await store.add(DOC, question)).id).toBe('n-3')

    // Everything gone, including the highest: the next id is still a new one.
    for (const id of ['n-1', 'n-3']) await store.remove(DOC, id)
    expect((await store.add(DOC, question)).id).toBe('n-4')
    // And the counter is on disk, so a restart does not start over either.
    expect((await new AnnotationStore(repoRoot).add(DOC, question)).id).toBe('n-5')
  })

  // The same numbering policy for both kinds of id, off the same counter
  it('never hands out a batch id twice either', async () => {
    const { repoRoot, store } = storeOn()
    await store.add(DOC, issue)
    await store.add(DOC, issue)

    expect((await store.addBatch(DOC, 's-1', ['n-1'])).id).toBe('b-1')
    expect((await new AnnotationStore(repoRoot).addBatch(DOC, 's-2', ['n-2'])).id).toBe('b-2')
  })

  // An id the list no longer holds claims nothing: the row is opened all the same
  it('opens the row even when one of the ids is no longer in the list', async () => {
    const { store } = storeOn()
    await store.add(DOC, issue)

    const batch = await store.addBatch(DOC, 's-1', ['n-1', 'n-9'])

    expect(batch.annotationIds).toEqual(['n-1', 'n-9'])
    expect(store.read(DOC).annotations).toHaveLength(1)
  })

  /**
   * A file written before the counter existed derives it from every id it can
   * still account for — the annotations, the batches, and the annotations those
   * batches **claimed**, which is where a submitted annotation's number survives.
   */
  it('derives the counter from the ids a file written without it still shows', async () => {
    const { repoRoot, store } = storeOn()
    mkdirSync(join(repoRoot, ANNOTATIONS_DIR), { recursive: true })
    writeFileSync(
      fileOf(repoRoot),
      JSON.stringify({
        docId: DOC,
        annotations: [
          { id: 'n-5', type: 'issue', text: 'x', anchor: ANCHOR, quote: 'x', createdAt: 'now', state: 'submitted' },
        ],
        batches: [{ id: 'b-2', status: 'done', sessionId: 's-1', annotationIds: ['n-5', 'n-7'], startedAt: 'now' }],
      }),
    )

    expect((await store.add(DOC, question)).id).toBe('n-8')
    expect((await store.addBatch(DOC, 's-2', [])).id).toBe('b-3')
  })

  /**
   * spec-00007-AC-3.4 with design-00002 §16.5 — why the last submit held it back
   * goes with the change that answers it: left standing on a re-anchored
   * annotation it would contradict the very mark the re-anchor lifted.
   */
  it('clears the last submit’s reason when the annotation is changed', async () => {
    const { store } = storeOn()
    await store.add(DOC, question)
    const block = () =>
      store.update(DOC, (list) => {
        list.annotations[0]!.blocked = 'orphan-missing'
        list.annotations[0]!.orphan = 'missing'
      })

    await block()
    expect((await store.patch(DOC, 'n-1', { anchor: OTHER })).blocked).toBeUndefined()
    await block()
    expect((await store.patch(DOC, 'n-1', { text: 'clearer now' })).blocked).toBeUndefined()
    await block()
    expect((await store.patch(DOC, 'n-1', { type: 'issue' })).blocked).toBeUndefined()
  })

  it('answers 404-shaped for an annotation that is not in the list', async () => {
    const { store } = storeOn()

    await expect(store.patch(DOC, 'n-9', { text: 'x' })).rejects.toBeInstanceOf(NoAnnotationError)
    await expect(store.remove(DOC, 'n-9')).rejects.toBeInstanceOf(NoAnnotationError)
  })

  /**
   * design-00001 §12.3: a submitted annotation takes no change and no delete —
   * a batch would be left pointing at something that is not there, with nothing
   * to hand back when its session ends.
   */
  it('refuses a change or a delete of an annotation that has been submitted', async () => {
    const { store } = storeOn()
    await store.add(DOC, issue)
    await store.update(DOC, (list) => {
      list.annotations[0]!.state = 'submitted'
    })

    for (const refuse of [() => store.patch(DOC, 'n-1', { text: 'x' }), () => store.remove(DOC, 'n-1')]) {
      await expect(refuse()).rejects.toBeInstanceOf(AnnotationConflictError)
      await expect(refuse()).rejects.toMatchObject({ reason: 'already-submitted' })
    }
  })

  // A list that cannot be read is a list that must not be written over: it is the
  // only copy of every annotation the document has.
  it('refuses to write over a file it cannot read', async () => {
    const { repoRoot, store } = storeOn()
    mkdirSync(join(repoRoot, ANNOTATIONS_DIR), { recursive: true })
    writeFileSync(fileOf(repoRoot), '{ not json')

    await expect(store.add(DOC, question)).rejects.toBeInstanceOf(WorkflowError)
    expect(readFileSync(fileOf(repoRoot), 'utf8')).toBe('{ not json')
  })

  /**
   * issue-00023 — one file, one answer. A read that served an unreadable list as
   * «no annotations» told the owner their annotations are gone, and the first
   * annotation they then made would write over the file a person could still have
   * rescued by hand. The refusal names the file for that reason.
   */
  it('refuses to read a file it cannot parse, the way a write refuses it', async () => {
    const { repoRoot, store } = storeOn()
    mkdirSync(join(repoRoot, ANNOTATIONS_DIR), { recursive: true })

    for (const broken of ['{ not json', '{ "docId": "x" }', '[]']) {
      writeFileSync(fileOf(repoRoot), broken)

      expect(() => store.read(DOC)).toThrowError(WorkflowError)
      expect(() => store.read(DOC)).toThrowError(new RegExp(`${DOC}\\.json`))
      await expect(store.add(DOC, question)).rejects.toThrowError(new RegExp(`${DOC}\\.json`))
    }
  })

  // And a document that simply has no file yet is no such refusal: an empty list
  // is the honest reading of «nothing annotated yet» (spec-00007-AC-9.9).
  it('reads a document with no file at all as an empty list', () => {
    const { store } = storeOn()

    expect(store.read(DOC)).toMatchObject({ docId: DOC, annotations: [], batches: [] })
  })

  /**
   * issue-00023 — a turn that changed nothing is not a turn worth a file. Every
   * cowrite session's end goes through the batch landing, so an unconditional
   * write left an empty annotation file behind for every document anyone ever
   * cowrote by hand (spec-00007-FR-8: the session behaves no differently).
   */
  it('writes no file at all for a turn that changed nothing', async () => {
    const { repoRoot, store } = storeOn()

    // No batch of this document to land, and no file to land it in.
    await store.landBatch(DOC, 's-1', 'done', 'abc1234')
    await store.update(DOC, () => 'nothing to change')

    expect(existsSync(fileOf(repoRoot))).toBe(false)
    // A turn that does change something still writes, of course.
    await store.add(DOC, question)
    expect(existsSync(fileOf(repoRoot))).toBe(true)
  })

  // Valid JSON that is no annotation list is the same refusal an unparsable file
  // is: the file is the only copy, so nothing may be written over it.
  it('refuses to write over a file whose JSON is no annotation list, saying what is wrong with it', async () => {
    const { repoRoot, store } = storeOn()
    mkdirSync(join(repoRoot, ANNOTATIONS_DIR), { recursive: true })
    writeFileSync(fileOf(repoRoot), '{ "docId": "x" }')

    await expect(store.add(DOC, question)).rejects.toThrowError(/holds neither a list of annotations/)
    expect(readFileSync(fileOf(repoRoot), 'utf8')).toBe('{ "docId": "x" }')
  })

  it('addresses a list by the document id alone, and reads no file outside its own directory', async () => {
    const { store } = storeOn()

    // Refused on both sides rather than answered with an empty list: a name that
    // is no document id is nobody's annotations (issue-00023).
    expect(() => store.read('../asks/spec-00001-x')).toThrowError(WorkflowError)
    await expect(store.add('../asks/spec-00001-x', question)).rejects.toThrowError(WorkflowError)
  })
})

/**
 * The batch lifecycle of design-00001 §12.6: the row is opened by the submit and
 * closed by the session's end callback, and anything but a natural end gives the
 * annotations back.
 */
describe('a batch reaching its end', () => {
  async function withBatch() {
    const { repoRoot, store } = storeOn()
    await store.add(DOC, issue)
    await store.add(DOC, { ...issue, text: 'and this passage' })
    await store.update(DOC, (list) => {
      list.batches.push({
        id: 'b-1',
        status: 'cowriting',
        sessionId: 's-1',
        annotationIds: ['n-1', 'n-2'],
        startedAt: new Date().toISOString(),
      })
      for (const annotation of list.annotations) {
        annotation.state = 'submitted'
        annotation.batchId = 'b-1'
      }
    })
    return { repoRoot, store }
  }

  // spec-00007-AC-9.4 — the collapse commit is the reference the list shows
  it('records a natural end as done with its commit, and keeps the annotations submitted', async () => {
    const { store } = await withBatch()

    await store.landBatch(DOC, 's-1', 'done', 'abc1234')

    const list = store.read(DOC)
    expect(list.batches[0]).toMatchObject({ status: 'done', commit: 'abc1234' })
    expect(list.batches[0]!.endedAt).toMatch(/^\d{4}-\d{2}-\d{2}T.+Z$/)
    expect(list.annotations.every((annotation) => annotation.state === 'submitted')).toBe(true)
    expect(list.annotations.every((annotation) => annotation.batchId === 'b-1')).toBe(true)
  })

  // spec-00007-AC-9.5 — no landed change is the null, and the list says so from it
  it('records a natural end with nothing committed as done with no commit', async () => {
    const { store } = await withBatch()

    await store.landBatch(DOC, 's-1', 'done', null)

    expect(store.read(DOC).batches[0]).toMatchObject({ status: 'done', commit: null })
  })

  /**
   * spec-00007-AC-10.7 and AC-10.2 — a stop and a failure both hand the batch's
   * annotations back to the unsubmitted region, where they can be changed and
   * submitted again; the row itself stays as history.
   */
  // spec-00007-AC-10.7
  it('hands the annotations back on a stop and on a failure, keeping the row', async () => {
    for (const ending of ['terminated', 'failed'] as const) {
      const { store } = await withBatch()

      await store.landBatch(DOC, 's-1', ending, null)

      const list = store.read(DOC)
      expect(list.batches[0]).toMatchObject({ id: 'b-1', status: ending })
      expect(list.annotations.map((annotation) => annotation.state)).toEqual(['pending', 'pending'])
      expect(list.annotations.every((annotation) => annotation.batchId === undefined)).toBe(true)
      // And they are editable again, the reading being the state alone.
      await expect(store.patch(DOC, 'n-1', { text: 'clearer now' })).resolves.toMatchObject({ text: 'clearer now' })
    }
  })

  // An annotation of another batch, or of none, is not the one being handed back:
  // the batch gives back what it claimed and nothing else.
  it('hands back only the annotations of the batch that ended', async () => {
    const { store } = await withBatch()
    await store.add(DOC, { ...question, text: 'still unsubmitted' })

    await store.landBatch(DOC, 's-1', 'terminated', null)

    const list = store.read(DOC)
    expect(list.annotations.map((annotation) => annotation.state)).toEqual(['pending', 'pending', 'pending'])
    expect(list.annotations[2]!.batchId).toBeUndefined()
  })

  /**
   * A file that cannot be read **refuses** the landing rather than swallowing it
   * (design-00001 §12.6): swallowed, the batch would read `cowriting` and its
   * annotations stay submitted for good, with nothing said and no way out until a
   * restart. The caller is the one that reports it and keeps it to retry.
   */
  it('refuses a landing it cannot write, leaving the file as it is', async () => {
    const { repoRoot } = await withBatch()
    writeFileSync(fileOf(repoRoot), '{ not json')

    await expect(new AnnotationStore(repoRoot).landBatch(DOC, 's-1', 'done', 'abc1234')).rejects.toBeInstanceOf(
      WorkflowError,
    )
    expect(readFileSync(fileOf(repoRoot), 'utf8')).toBe('{ not json')
  })

  it('leaves a session no batch of that document names alone', async () => {
    const { store } = await withBatch()

    await store.landBatch(DOC, 's-other', 'done', 'abc1234')

    expect(store.read(DOC).batches[0]).toMatchObject({ status: 'cowriting' })
  })

  it('lands a batch once: a second end finds nothing being cowritten', async () => {
    const { store } = await withBatch()
    await store.landBatch(DOC, 's-1', 'done', 'abc1234')

    await store.landBatch(DOC, 's-1', 'failed', null)

    expect(store.read(DOC).batches[0]).toMatchObject({ status: 'done', commit: 'abc1234' })
  })

  /**
   * spec-00007-AC-10.8 — the registry comes up empty, so a batch still reading
   * `cowriting` at boot is one the last process took down with it: it is written
   * off and its annotations go back, rather than being shown as cowritten for ever.
   */
  // spec-00007-AC-10.8
  it('writes off a batch the last process was killed with, at the next boot', async () => {
    const { repoRoot } = await withBatch()

    new AnnotationStore(repoRoot).reconcile()

    const list = new AnnotationStore(repoRoot).read(DOC)
    expect(list.batches[0]).toMatchObject({ status: 'failed', commit: null })
    expect(list.annotations.map((annotation) => annotation.state)).toEqual(['pending', 'pending'])
  })

  it('leaves a list with nothing being cowritten untouched, and skips a file it cannot read', async () => {
    const { repoRoot, store } = storeOn()
    await store.add(DOC, question)
    writeFileSync(fileOf(repoRoot, 'spec-00005-broken'), '{ not json')
    const before = readFileSync(fileOf(repoRoot), 'utf8')

    new AnnotationStore(repoRoot).reconcile()

    expect(readFileSync(fileOf(repoRoot), 'utf8')).toBe(before)
    expect(readFileSync(fileOf(repoRoot, 'spec-00005-broken'), 'utf8')).toBe('{ not json')
  })

  it('has nothing to reconcile when no document has annotations at all', () => {
    const { repoRoot } = storeOn()

    expect(() => new AnnotationStore(repoRoot).reconcile()).not.toThrow()
  })
})
