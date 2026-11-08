/**
 * Enter / 换行编码。
 *
 * 终端里「多行输入」不是把整段文本攒在本地，而是让前台程序（bash/readline、
 * opencode 等 TUI）把换行插入自己的编辑缓冲，只有裸 Enter 才提交。能否做到
 * 取决于终端发出的字节：
 *
 * - 裸 Enter 仍发 CR(0x0D)，与旧行为完全一致；
 * - Shift/Ctrl + Enter 必须与裸 Enter 区分开。xterm.js 6 在 `evaluateKeyboardEvent`
 *   的 Enter 分支只判断了 altKey，Shift/Ctrl 会被折叠成 CR，前台程序因此只能
 *   看到「提交」；
 * - 前台程序启用了 Kitty 键盘协议时，用 CSI-u(`\x1b[13;2u` / `\x1b[13;5u`)
 *   直接上报带修饰键的 Enter；
 * - 否则退回 Ctrl+J 的 LF(0x0A)。LF 是 opencode / Claude Code / Codex 等 TUI
 *   都支持的换行键；
 * - 如果前台程序启用了括号粘贴模式（bash 默认开启），把单个 LF 包进
 *   `ESC[200~ ... ESC[201~`，readline 会把它当作粘贴文本插入编辑缓冲，
 *   不会执行命令。
 */

export const CR = '\r'
export const LF = '\n'
export const KITTY_SHIFT_ENTER = '\x1b[13;2u'
export const KITTY_CTRL_ENTER = '\x1b[13;5u'
const BRACKET_PASTE_OPEN = '\x1b[200~'
const BRACKET_PASTE_CLOSE = '\x1b[201~'

export interface EnterMods {
  shift?: boolean
  ctrl?: boolean
  alt?: boolean
}

export interface EnterProtocol {
  /** 前台程序是否启用了 Kitty 键盘协议（CSI-u）。 */
  kitty: boolean
  /** 前台程序是否启用了括号粘贴模式。 */
  bracketedPaste: boolean
}

/** 把一次 Enter（可能带修饰键）编码成发给 PTY 的字节。 */
export function enterSequence(mods: EnterMods, protocol: EnterProtocol): string {
  const shift = mods.shift === true
  const ctrl = mods.ctrl === true
  const alt = mods.alt === true

  // 没有任何修饰键：保持原来的 CR，shell/TUI 的提交语义不变。
  if (!shift && !ctrl) return alt ? `\x1b${CR}` : CR

  const modifiers = 1 + (shift ? 1 : 0) + (ctrl ? 4 : 0) + (alt ? 2 : 0)
  if (protocol.kitty) return `\x1b[13;${modifiers}u`

  // Ctrl+J / LF 是各 TUI 通用的「插入换行」键。
  const newline = LF
  if (protocol.bracketedPaste) {
    // 括号粘贴只对「文本」生效：readline 会把它插入当前行，而不是执行。
    return `${BRACKET_PASTE_OPEN}${newline}${BRACKET_PASTE_CLOSE}`
  }
  return newline
}

/**
 * 跟踪 PTY 输出里的键盘协议切换。
 *
 * Kitty：`CSI > flags u` push（启用）、`CSI < n u` pop（恢复）。
 *
 * 直接在字节流上扫描，不把终端输出整段解码成字符串：`cat` 大文件时每帧
 * 多一次 UTF-8 解码会明显增加主线程开销。输出可能被 PTY 切成多段，所以保留
 * 一小段尾巴，跨 chunk 的序列也能识别。
 */
export class KeyProtocolTracker {
  kitty = false
  private tail = new Uint8Array(0)

  reset(): void {
    this.kitty = false
    this.tail = new Uint8Array(0)
  }

  feed(bytes: Uint8Array): void {
    if (bytes.length === 0) return
    let buf: Uint8Array
    if (this.tail.length === 0) {
      buf = bytes
    } else {
      buf = new Uint8Array(this.tail.length + bytes.length)
      buf.set(this.tail, 0)
      buf.set(bytes, this.tail.length)
    }

    const ESC = 0x1b
    const LBRACKET = 0x5b
    let i = 0
    while (i + 2 < buf.length) {
      if (buf[i] !== ESC || buf[i + 1] !== LBRACKET) {
        i++
        continue
      }
      const kind = buf[i + 2]
      if (kind !== 0x3e && kind !== 0x3c) {
        i += 2
        continue
      }
      let end = -1
      for (let j = i + 3; j < buf.length; j++) {
        if (buf[j] === 0x75) {
          end = j
          break
        }
      }
      if (end < 0) break
      this.kitty = kind === 0x3e
      i = end + 1
    }

    // 只保留可能还没收全的 CSI 前缀，避免无限增长。
    this.tail = buf.slice(Math.max(0, buf.length - 32))
  }
}
