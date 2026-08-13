// jsdom implements neither matchMedia nor ResizeObserver; xterm and React Flow
// both ask for them on mount. Stubbing them here keeps the gap in the test
// harness instead of in the components.
if (typeof window !== 'undefined') {
  window.matchMedia ??= ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia

  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  // CodeMirror measures typed text through a Range; jsdom has no getClientRects.
  Range.prototype.getClientRects ??= () =>
    ({ length: 0, item: () => null, [Symbol.iterator]: function* () {} }) as unknown as DOMRectList

  // jsdom implements no SVG layout, so mermaid's measurement calls throw. Stubbing
  // the primitives lets mermaid parse and emit real svg; only the metrics are fake.
  const svg = SVGElement.prototype as unknown as {
    getBBox?: () => DOMRect
    getComputedTextLength?: () => number
    getScreenCTM?: () => DOMMatrix | null
  }
  svg.getBBox ??= () => ({ x: 0, y: 0, width: 100, height: 20 }) as DOMRect
  svg.getComputedTextLength ??= () => 100
  svg.getScreenCTM ??= () => null
}
