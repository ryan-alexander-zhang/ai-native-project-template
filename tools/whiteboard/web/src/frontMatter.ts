const FRONT_MATTER = /^---\r?\n[\s\S]*?\r?\n---[ \t]*(?:\r?\n|$)/

/**
 * The editor holds the whole file so front matter stays visible and repairable,
 * but as Markdown a `---` block renders as a rule and a setext heading. The
 * preview shows the body; the metadata is already on the node.
 */
export function stripFrontMatter(text: string): string {
  return text.replace(FRONT_MATTER, '')
}

/**
 * A slug as `rule-00001-BR-18` defines one: lowercase words joined by hyphens.
 * The server rules on it too (spec-00001-AC-53.4) — this is so the refusal
 * lands before a request that cannot succeed is sent.
 */
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export function isSlug(slug: string): boolean {
  return SLUG.test(slug)
}

/**
 * The template as the new document's first version: `id`, `type` and `status`
 * carry the allocated values rather than the template's placeholders
 * (spec-00001-FR-53). Only the front matter block is touched — a `status:` line
 * further down the body is prose, not metadata.
 */
export function prefillFrontMatter(template: string, id: string, type: string): string {
  const block = FRONT_MATTER.exec(template)
  if (!block) return template
  const filled = block[0]
    .replace(/^id:.*$/m, `id: ${id}`)
    .replace(/^type:.*$/m, `type: ${type}`)
    .replace(/^status:.*$/m, 'status: draft')
  return filled + template.slice(block[0].length)
}
