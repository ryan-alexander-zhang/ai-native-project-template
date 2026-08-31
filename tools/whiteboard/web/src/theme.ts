import { useCallback, useEffect, useState } from 'react'

export type Theme = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'whiteboard-theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

export function readStoredTheme(): Theme {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system'
}

/** Whether `theme` should paint dark right now; `system` asks the OS. */
export function resolvesDark(theme: Theme): boolean {
  return theme === 'dark' || (theme === 'system' && window.matchMedia(DARK_QUERY).matches)
}

function paint(theme: Theme): void {
  document.documentElement.classList.toggle('dark', resolvesDark(theme))
}

/**
 * Three-state theme. Tailwind's `dark:` variant is driven from a class here
 * (see index.css), because the media query alone cannot express a user choice.
 */
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => readStoredTheme())

  useEffect(() => {
    paint(theme)
    if (theme !== 'system') return
    // Only the system setting follows the OS, and it must keep following it.
    const media = window.matchMedia(DARK_QUERY)
    const onChange = () => paint('system')
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [theme])

  const choose = useCallback((next: Theme) => {
    localStorage.setItem(STORAGE_KEY, next)
    setTheme(next)
  }, [])

  return { theme, choose, isDark: resolvesDark(theme) }
}
