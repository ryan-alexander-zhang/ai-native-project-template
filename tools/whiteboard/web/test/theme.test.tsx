// @vitest-environment jsdom
import { act, cleanup, render, renderHook, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThemeMenu } from '../src/ThemeMenu.tsx'
import { readStoredTheme, resolvesDark, useTheme } from '../src/theme.ts'

function setSystemDark(dark: boolean) {
  ;(globalThis as { matchMediaMatches?: boolean }).matchMediaMatches = dark
}

beforeEach(() => {
  localStorage.clear()
  setSystemDark(false)
  document.documentElement.classList.remove('dark')
})

afterEach(cleanup)

describe('readStoredTheme', () => {
  it('defaults to following the system', () => {
    expect(readStoredTheme()).toBe('system')
  })

  it('reads a stored choice back', () => {
    localStorage.setItem('whiteboard-theme', 'dark')
    expect(readStoredTheme()).toBe('dark')
  })

  it('ignores a stored value outside the three states', () => {
    localStorage.setItem('whiteboard-theme', 'neon')
    expect(readStoredTheme()).toBe('system')
  })
})

describe('resolvesDark', () => {
  it('takes an explicit choice at face value', () => {
    setSystemDark(false)
    expect(resolvesDark('dark')).toBe(true)
    expect(resolvesDark('light')).toBe(false)
  })

  it('asks the system only for the system setting', () => {
    setSystemDark(true)
    expect(resolvesDark('system')).toBe(true)
    expect(resolvesDark('light')).toBe(false)
    setSystemDark(false)
    expect(resolvesDark('system')).toBe(false)
  })
})

describe('useTheme', () => {
  it('paints the document from the stored choice', () => {
    localStorage.setItem('whiteboard-theme', 'dark')

    const { result } = renderHook(() => useTheme())

    expect(result.current.theme).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('follows the system when set to system', () => {
    setSystemDark(true)

    const { result } = renderHook(() => useTheme())

    expect(result.current.isDark).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('keeps a choice across reloads', () => {
    const { result } = renderHook(() => useTheme())

    act(() => result.current.choose('dark'))

    expect(localStorage.getItem('whiteboard-theme')).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(readStoredTheme()).toBe('dark')
  })

  it('stops painting dark when the user picks light', () => {
    localStorage.setItem('whiteboard-theme', 'dark')
    const { result } = renderHook(() => useTheme())

    act(() => result.current.choose('light'))

    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })
})

describe('the theme menu', () => {
  it('names the setting in use', () => {
    render(<ThemeMenu theme="dark" onChoose={vi.fn()} />)
    expect(screen.getByLabelText('Theme: Dark')).toBeTruthy()
  })

  it('falls back to system for an unknown setting', () => {
    render(<ThemeMenu theme={'neon' as 'system'} onChoose={vi.fn()} />)
    expect(screen.getByLabelText('Theme: System')).toBeTruthy()
  })

  it('offers all three settings and reports the choice', async () => {
    const onChoose = vi.fn()
    render(<ThemeMenu theme="system" onChoose={onChoose} />)

    await userEvent.click(screen.getByLabelText('Theme: System'))

    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Light', 'Dark', 'System'])
    await userEvent.click(screen.getByRole('menuitem', { name: 'Dark' }))
    expect(onChoose).toHaveBeenCalledWith('dark')
  })
})
