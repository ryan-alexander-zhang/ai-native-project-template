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

  // React Flow only draws an edge once both endpoints have been measured, and
  // measurement arrives through this observer. A no-op stub silently costs you
  // every edge (issue-00002), so report a size instead. `borderBoxSize` is not
  // optional: react-resizable-panels reads it.
  //
  // The size an element reports comes from its `data-width` / `data-height`,
  // unless a test overrides it by selector through `resizeSizes`. A test that
  // needs an element to *change* size — a panel taking half the canvas, say —
  // sets `resizeSizes` again and calls `reportResize()`; the observers report
  // afresh, as the browser's would (issue-00006).
  const observers = new Set<{ report: () => void }>()
  type StubSize = { selector: string; width: number; height: number }
  const drive = globalThis as { resizeSizes?: StubSize[]; reportResize?: () => void }

  function entryFor(target: Element): ResizeObserverEntry {
    const forced = drive.resizeSizes?.find((size) => target.matches(size.selector))
    const element = target as HTMLElement
    const box = {
      inlineSize: forced?.width ?? Number(element.dataset.width ?? '240'),
      blockSize: forced?.height ?? Number(element.dataset.height ?? '92'),
    }
    const rect = {
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: box.inlineSize,
      bottom: box.blockSize,
      width: box.inlineSize,
      height: box.blockSize,
      toJSON: () => ({}),
    }
    Object.defineProperties(target, {
      offsetWidth: { configurable: true, value: box.inlineSize },
      offsetHeight: { configurable: true, value: box.blockSize },
    })
    return {
      target,
      contentRect: rect,
      borderBoxSize: [box],
      contentBoxSize: [box],
      devicePixelContentBoxSize: [box],
    } as unknown as ResizeObserverEntry
  }

  globalThis.ResizeObserver ??= class {
    private readonly callback: ResizeObserverCallback
    private readonly targets = new Set<Element>()

    constructor(callback: ResizeObserverCallback) {
      this.callback = callback
      observers.add(this)
    }

    observe(target: Element) {
      this.targets.add(target)
      // Asynchronously, like the real one: observing inside a layout effect
      // must not re-enter React's commit.
      queueMicrotask(() => this.report())
    }

    unobserve(target: Element) {
      this.targets.delete(target)
    }

    disconnect() {
      this.targets.clear()
      observers.delete(this)
    }

    report() {
      const entries = [...this.targets].map(entryFor)
      if (entries.length > 0) this.callback(entries, this as unknown as ResizeObserver)
    }
  }

  drive.reportResize = () => {
    for (const observer of observers) observer.report()
  }

  // The board opens the docs-change channel on mount (spec-00001-FR-42), and
  // jsdom's own WebSocket would answer that by dialling a localhost server no
  // test is running. This one never opens and never signals, which is the
  // «connection not available» case FR-43 says costs the board nothing — the
  // tests that drive the channel stub their own socket over it.
  class SilentSocket {
    static readonly OPEN = 1
    readyState = 0
    constructor(readonly url: string) {}
    addEventListener() {}
    removeEventListener() {}
    send() {}
    close() {}
  }
  Object.defineProperty(globalThis, 'WebSocket', { configurable: true, writable: true, value: SilentSocket })

  // React Flow reads the canvas transform through it; jsdom has no CSSOM view.
  globalThis.DOMMatrixReadOnly ??= class {
    m22 = 1
    m41 = 0
    m42 = 0
    constructor(_transform?: string) {}
  } as unknown as typeof DOMMatrixReadOnly

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

  // react-resizable-panels hit-tests every pointerdown against the dividers
  // between the panels of a group, and preventDefault()s the ones that land on
  // one. jsdom measures every element as a zero-sized box at the origin, which
  // is exactly where a synthetic pointer is — so without a box every press
  // would read as a press on a divider, and a prevented pointerdown is enough
  // to stop a Radix menu from opening. Report a box for the panels, so the
  // dividers land where no synthetic pointer is.
  const PANEL_RECT = {
    x: 500,
    y: 500,
    top: 500,
    left: 500,
    right: 1500,
    bottom: 1500,
    width: 1000,
    height: 1000,
    toJSON: () => ({}),
  } as DOMRect
  const boundingRect = Element.prototype.getBoundingClientRect
  Element.prototype.getBoundingClientRect = function (this: Element) {
    return this.hasAttribute('data-panel') ? PANEL_RECT : boundingRect.call(this)
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

  // The annotation input hangs off the rectangle of the live selection
  // (design-00002 §16.2), and jsdom implements neither Range measurement. Same
  // reason as the stub above: the gap belongs in the harness, not in a component
  // that would have to guard a call every browser answers.
  Range.prototype.getBoundingClientRect ??= () =>
    ({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      width: 0,
      height: 0,
      toJSON: () => ({}),
    }) as DOMRect

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
