/**
 * 主题调色板，保持与本项目 Compose 主题（ZlBg #0B0C0F / ZlAccent #7EE787）一致。
 */

export type ThemeMode = 'dark' | 'light'

export interface TerminalPalette {
  background: string
  foreground: string
  cursor: string
  cursorAccent: string
  selectionBackground: string
  selectionInactiveBackground: string
  black: string
  red: string
  green: string
  yellow: string
  blue: string
  magenta: string
  cyan: string
  white: string
  brightBlack: string
  brightRed: string
  brightGreen: string
  brightYellow: string
  brightBlue: string
  brightMagenta: string
  brightCyan: string
  brightWhite: string
}

const DARK: TerminalPalette = {
  background: '#0b0c0f',
  foreground: '#e6e8eb',
  cursor: '#7ee787',
  cursorAccent: '#0b0c0f',
  selectionBackground: '#b6d7ff88',
  selectionInactiveBackground: '#b6d7ff44',
  black: '#1f2328',
  red: '#ff7b72',
  green: '#7ee787',
  yellow: '#d29922',
  blue: '#79c0ff',
  magenta: '#d2a8ff',
  cyan: '#56d4dd',
  white: '#c9d1d9',
  brightBlack: '#6e7681',
  brightRed: '#ffa198',
  brightGreen: '#aff5b4',
  brightYellow: '#e3b341',
  brightBlue: '#a5d6ff',
  brightMagenta: '#e2c5ff',
  brightCyan: '#76e3ea',
  brightWhite: '#f0f6fc'
}

const LIGHT: TerminalPalette = {
  background: '#ffffff',
  foreground: '#1f2328',
  cursor: '#136f36',
  cursorAccent: '#ffffff',
  selectionBackground: '#264f7866',
  selectionInactiveBackground: '#264f7833',
  black: '#24292f',
  red: '#cf222e',
  green: '#116329',
  yellow: '#953800',
  blue: '#0550ae',
  magenta: '#8250df',
  cyan: '#0a7ea4',
  white: '#6e7781',
  brightBlack: '#57606a',
  brightRed: '#a40e26',
  brightGreen: '#1a7f37',
  brightYellow: '#b35900',
  brightBlue: '#0969da',
  brightMagenta: '#a475f9',
  brightCyan: '#1b9aaa',
  brightWhite: '#8c959f'
}

export function themeFor(mode: ThemeMode): TerminalPalette {
  return mode === 'light' ? LIGHT : DARK
}

export function applyDocumentTheme(mode: ThemeMode): void {
  const palette = themeFor(mode)
  const root = document.documentElement
  root.style.setProperty('--zl-bg', palette.background)
  root.style.setProperty('--zl-fg', palette.foreground)
  root.style.setProperty('--zl-cursor', palette.cursor)
  root.style.setProperty('--zl-selection', palette.selectionBackground)
  root.dataset.theme = mode
}
