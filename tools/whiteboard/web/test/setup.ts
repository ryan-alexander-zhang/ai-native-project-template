// jsdom implements neither matchMedia nor ResizeObserver; xterm and React Flow
// both ask for them on mount. Stubbing them here keeps the gap in the test
// harness instead of in the components.
if (typeof window !== 'undefined') {
  // Tests that care about the system colour scheme override `matchMediaMatches`.
  window.matchMedia ??= ((query: string) => ({
    matches: (globalThis as { matchMediaMatches?: boolean }).matchMediaMatches ?? false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia

  // This jsdom build ships no localStorage; the theme and the panel sizes need one.
  if (!globalThis.localStorage) {
    const store = new Map<string, string>()
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: {
        getItem: (key: string) => store.get(key) ?? null,
        setItem: (key: string, value: string) => void store.set(key, String(value)),
        removeItem: (key: string) => void store.delete(key),
        clear: () => store.clear(),
        key: (index: number) => [...store.keys()][index] ?? null,
        get length() {
          return store.size
        },
      },
    })
  }

  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  // user-event builds mouse events without a `view`; d3-drag (inside React Flow)
  // reads `event.view.document` and throws. Fall back to the window.
  for (const constructor of [UIEvent, globalThis.MouseEvent, globalThis.PointerEvent]) {
    const proto = constructor?.prototype
    if (!proto) continue
    const view = Object.getOwnPropertyDescriptor(proto, 'view')
    Object.defineProperty(proto, 'view', {
      configurable: true,
      get(this: UIEvent) {
        return view?.get?.call(this) ?? window
      },
    })
  }

  // Radix menus, dialogs and popovers reach for pointer-capture and scrolling
  // APIs jsdom does not implement.
  const element = Element.prototype as unknown as {
    hasPointerCapture?: () => boolean
    setPointerCapture?: () => void
    releasePointerCapture?: () => void
    scrollIntoView?: () => void
  }
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
  element.scrollIntoView ??= () => {}

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
