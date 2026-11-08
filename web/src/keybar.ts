/**
 * 终端底部快捷按键栏。
 *
 * 两行五键（按用户给的图）：
 *   Alt Shift Ctrl ↑ Enter
 *   Esc Tab ← ↓ →
 * - 按键栏常驻，没有折叠按钮，也没有折叠状态。
 * - 修饰键 Alt/Shift/Ctrl 只是普通开关：点一下高亮 `.on`，再点一下关闭。
 *   高亮只作用于下一次按键，任何动作键之后自动清除（粘滞一次语义）。
 * - 动作键直接发转义序列（Escape/Tab/方向键/Enter），长按按 keyrepeat 的
 *   400ms 后每 70ms 连发。
 * - touchstart 立即发送并 preventDefault，避免点按把软键盘顶出来。
 */
import { KeyRepeater, sequenceFor } from './keyrepeat'
import { CR, LF } from './enter'

export type Mod = 'alt' | 'shift' | 'ctrl'

interface KeySpec {
  label: string
  mod?: Mod
  key?: string
}

const ROWS: KeySpec[][] = [
  [
    { label: 'Alt', mod: 'alt' },
    { label: 'Shift', mod: 'shift' },
    { label: 'Ctrl', mod: 'ctrl' },
    { label: '↑', key: 'ArrowUp' },
    { label: 'Enter', key: 'Enter' }
  ],
  [
    { label: 'Esc', key: 'Escape' },
    { label: 'Tab', key: 'Tab' },
    { label: '←', key: 'ArrowLeft' },
    { label: '↓', key: 'ArrowDown' },
    { label: '→', key: 'ArrowRight' }
  ]
]

/** 带修饰键的 CSI 参数：1 + shift + 2*alt + 4*ctrl。 */
const CSI_FINAL: Record<string, string> = {
  ArrowUp: 'A',
  ArrowDown: 'B',
  ArrowRight: 'C',
  ArrowLeft: 'D',
  Home: 'H',
  End: 'F'
}

/**
 * 不再区分「可重复键」：键栏里的每个动作键（含 Enter / Esc / Tab）
 * 都支持长按连发。是否可编码由 keyrepeat.isRepeatKey 兜底，
 * 键栏现用的键全都在 KEY_SEQUENCES 里，arm() 不会空转。
 */

/**
 * 把「按键 + 粘滞修饰键」编码成要写进 PTY 的字节。
 *
 * Enter 需要单独处理：裸 Enter 是提交（CR），Shift/Ctrl+Enter 是插入换行。
 * 后者不能交给 xterm —— 它会把修饰键折叠掉，只发 CR。具体字节由调用方按
 * 当前终端协议（Kitty / 括号粘贴）算好后通过 [enterNewline] 传入。
 */
export function encodeKey(key: string, mods: Mod[], enterNewline: string = LF): string {
  const seq = sequenceFor(key)
  if (seq == null) return ''

  const alt = mods.indexOf('alt') >= 0
  const shift = mods.indexOf('shift') >= 0
  const ctrl = mods.indexOf('ctrl') >= 0

  if (key === 'Enter') {
    // Alt+Enter 保持旧行为（ESC CR，即 readline/TUI 的 alt+return），它本来就是
    // 换行而不是提交；Shift/Ctrl 才是之前被吞掉的两个组合。
    if (alt && !shift && !ctrl) return `\x1b${CR}`
    if (shift || ctrl) return enterNewline
    return CR
  }

  if (mods.length === 0) return seq

  const final = CSI_FINAL[key]
  if (final != null) {
    const param = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0)
    return `\x1b[1;${param}${final}`
  }
  if (key === 'Tab' && shift && !alt && !ctrl) return '\x1b[Z'
  // Ctrl + 单字母 → 控制字符（Ctrl+C = \x03、Ctrl+D = \x04），与 xterm 默认编码一致。
  if (ctrl && !alt && key.length === 1) {
    const code = key.toUpperCase().charCodeAt(0)
    if (code >= 65 && code <= 90) return String.fromCharCode(code - 64)
  }
  // 其它键没有可靠的 CSI 修饰形式，退化成「Alt = ESC 前缀」。
  if (alt && !ctrl) return `\x1b${seq}`
  return seq
}

/** Ctrl + 非字母字符的映射（与 xterm / readline 一致）。 */
const CTRL_MAP: Record<string, number> = { '@': 0, '[': 27, '\\': 28, ']': 29, '^': 30, '_': 31, '?': 127, ' ': 0 }

/**
 * 把粘滞修饰键应用到软键盘输入的单个字符上。
 * 多字符（粘贴 / IME 组词结果 / 转义序列）一律原样返回，避免误改。
 */
export function applyTextMods(text: string, mods: Mod[]): string {
  if (text.length !== 1) return text
  let out = text
  if (mods.includes('shift')) out = out.toUpperCase()
  if (mods.includes('ctrl')) {
    const c = out.charCodeAt(0)
    if (c >= 97 && c <= 122) out = String.fromCharCode(c - 96)
    else if (c >= 65 && c <= 90) out = String.fromCharCode(c - 64)
    else if (CTRL_MAP[out] !== undefined) out = String.fromCharCode(CTRL_MAP[out])
  }
  if (mods.includes('alt')) out = '\x1b' + out
  return out
}

export interface KeyBarOptions {
  onSend: (data: string) => void
  /**
   * Shift/Ctrl+Enter 的换行字节。由调用方按当前终端的键盘协议决定
   * （Kitty / 括号粘贴 / 纯 LF），不传则退回 LF。
   */
  enterSequence?: (mods: readonly Mod[]) => string
}

export class KeyBar {
  readonly el: HTMLDivElement

  private readonly repeater: KeyRepeater
  private readonly mods = new Set<Mod>()
  private readonly modButtons = new Map<Mod, HTMLButtonElement>()
  private readonly options: KeyBarOptions
  /** 连发期间复用的首帧 payload：修饰键只作用于首帧，后续帧原样重发。 */
  private repeatKey: string | null = null
  private repeatData = ''

  constructor(options: KeyBarOptions) {
    this.options = options
    this.repeater = new KeyRepeater((key) => this.repeat(key))

    this.el = document.createElement('div')
    this.el.className = 'zl-keybar'
    this.el.setAttribute('role', 'toolbar')

    ROWS.forEach((rowKeys) => {
      const row = document.createElement('div')
      row.className = 'zl-kb-row'
      rowKeys.forEach((spec) => row.appendChild(this.makeKey(spec)))
      this.el.appendChild(row)
    })
  }

  /** 挂到会话根节点：只有按键栏本体，没有折叠按钮。 */
  attach(root: HTMLElement): void {
    root.appendChild(this.el)
  }

  /** 键盘长按期间收起按键栏时把 repeat 也停掉。 */
  stopRepeat(): void {
    this.repeater.stop()
  }

  dispose(): void {
    this.repeater.dispose()
  }

  /** 当前生效的粘滞修饰键（软键盘 / IME 输入通道读取用）。 */
  getMods(): Mod[] {
    return this.activeMods()
  }

  /** 软键盘输入消费掉粘滞修饰键：语义与动作键一致（只用一次）。 */
  consumeModsNow(): void {
    this.consumeMods()
  }

  private makeKey(spec: KeySpec): HTMLButtonElement {
    const btn = document.createElement('button')
    btn.type = 'button'
    // 键位栏按钮不参与焦点：按钮一旦拿到焦点，宿主会把焦点抢回
    // xterm 的 helper textarea，软键盘随之弹出并盖住整条键栏 —— 之后所有
    // 点按都落到 Gboard 上，正是「按 Enter 却出现 Ctrl/字符乱序」的来源。
    btn.tabIndex = -1

    if (spec.mod) {
      const mod = spec.mod
      btn.className = 'zl-kb-key zl-kb-mod'
      btn.textContent = spec.label
      btn.dataset.mod = mod
      this.modButtons.set(mod, btn)
      // 修饰键不重复：一次点按只切换一次状态。
      btn.addEventListener('touchstart', (event) => {
        event.preventDefault()
        event.stopPropagation()
        this.tapMod(mod)
      }, { passive: false })
      btn.addEventListener('pointerdown', (event) => {
        if (event.pointerType === '' || event.pointerType === 'touch') return
        event.preventDefault()
        this.tapMod(mod)
      })
      return btn
    }

    const key = spec.key ?? ''
    btn.className = 'zl-kb-key zl-kb-act'
    btn.textContent = spec.label
    btn.dataset.key = key
    this.bindRepeat(btn, key)
    return btn
  }

  private bindRepeat(btn: HTMLButtonElement, key: string): void {
    const down = (event: Event): void => {
      event.preventDefault()
      event.stopPropagation()
      btn.classList.add('down')
      // 动作键首次按下立即发送，不走重复器的首帧延迟（否则 400ms 内没有任何字节）。
      // 先取一次「按键 + 粘滞修饰键」的编码结果并缓存：连发帧复用同一份 payload，
      // 否则每帧重新 encodeKey 都会把已清空的修饰键当成裸键，Alt+→ 变 →。
      const mods = this.activeMods()
      const newline = this.options.enterSequence?.(mods) ?? LF
      const data = encodeKey(key, mods, newline)
      this.consumeMods()
      this.repeatKey = key
      this.repeatData = data
      if (data) this.options.onSend(data)
      // 所有动作键都可长按连发（Enter / Esc / Tab 不再排除）；
      // 首发已在上面发过，这里 sendFirst = false。
      this.repeater.arm(key, false)
    }
    const up = (): void => {
      btn.classList.remove('down')
      this.repeater.stop()
    }
    // touch 的抬手由 touchend / touchcancel 负责：pointerup / pointercancel /
    // pointerleave 在触摸上会紧跟按下到达（尤其 pointerleave），会把连发秒停。
    const upPointer = (event: PointerEvent): void => {
      if (event.pointerType === '' || event.pointerType === 'touch') return
      up()
    }

    btn.addEventListener('touchstart', down, { passive: false })
    btn.addEventListener('touchend', up)
    btn.addEventListener('touchcancel', up)
    btn.addEventListener('pointerdown', (event) => {
      if (event.pointerType === '' || event.pointerType === 'touch') return
      down(event)
    })
    btn.addEventListener('pointerup', upPointer)
    btn.addEventListener('pointercancel', upPointer)
    btn.addEventListener('pointerleave', upPointer)
  }

  /** 重复器回调：同键复用首帧 payload（保留首帧的修饰键语义）。 */
  private repeat(key: string): void {
    const data =
      key === this.repeatKey && this.repeatData !== ''
        ? this.repeatData
        : encodeKey(key, [])
    if (data) this.options.onSend(data)
  }

  private activeMods(): Mod[] {
    const out: Mod[] = []
    this.mods.forEach((mod) => out.push(mod))
    return out
  }

  /** 粘滞修饰键只作用于下一次按键：真正发过键之后一律清空。 */
  private consumeMods(): void {
    if (this.mods.size === 0) return
    this.mods.clear()
    this.renderMods()
  }

  /** 单击 = 打开，再单击 = 关闭。没有双击/长按锁定。 */
  private tapMod(mod: Mod): void {
    if (this.mods.has(mod)) this.mods.delete(mod)
    else this.mods.add(mod)
    this.renderMods()
  }

  private renderMods(): void {
    this.modButtons.forEach((btn, mod) => {
      const on = this.mods.has(mod)
      btn.classList.toggle('on', on)
      btn.setAttribute('aria-pressed', on ? 'true' : 'false')
    })
  }
}
