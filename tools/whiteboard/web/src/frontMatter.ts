const FRONT_MATTER = /^---\r?\n[\s\S]*?\r?\n---[ \t]*(?:\r?\n|$)/

/**
 * The editor holds the whole file so front matter stays visible and repairable,
 * but as Markdown a `---` block renders as a rule and a setext heading. The
 * preview shows the body; the metadata is already on the node.
 */
export function stripFrontMatter(text: string): string {
  return text.replace(FRONT_MATTER, '')
}
