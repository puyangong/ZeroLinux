/**
 * 长按连续输入（auto-repeat）。
 *
 * Android 输入法对退格 / 方向键基本只发一次 keydown，长按光标不会连续移动。
 * 这里自己做：首帧立即发一次，[REPEAT_DELAY_MS] 后每 [REPEAT_INTERVAL_MS]
 * 补发一次，keyup / blur / 页面隐藏时停。
 *
 * 只接管控制键：可打印字符交给输入法自己处理，否则会出现重复字符。
 *
 * 两套输入源共用同一个 [KeyRepeater]：
 *   1. 硬件键盘 / 系统键盘：host 上的 keydown（含系统自己的 repeat）。
 *   2. 软键盘（搜狗等）：长按退格不产生连续 keydown，而是 KeyEvent 229 +
 *      持续的 beforeinput/input（inputType === "deleteContentBackward"），
 *      或者只来一次 keydown 再跟 IME 自己的重复。监听 xterm 的隐藏
 *      textarea.xterm-helper-textarea，把断续事件折算成同一个定时器。
 */

export const REPEAT_DELAY_MS = 400
/**
 * 连发间隔。必须大于 bridge 的 IME_COMMIT_MS(60)：相同字节的连发帧若落在
 * 60ms 窗口内会被当作同一次上屏的副本吞掉（自锁成「按住只发一次」）。
 */
export const REPEAT_INTERVAL_MS = 70

/** 软键盘连续 beforeinput 的判定间隔：小于它视为长按连发。 */
export const SOFT_KEY_GAP_MS = 80

/**
 * 「组词刚结束」的自定义事件，由本模块派发、由 bridge 监听。
 *
 * 文本写入只有一条路径：xterm 自己的 textarea 处理。这里只上报时间戳，
 * 供 bridge 在组词结束后对完全相同的 chunk 做一次 60ms 去重。
 * 本模块绝不写 PTY，也绝不改 `textarea.value`——清空会让 xterm 的
 * `_finalizeComposition` 读到空串，表现为「吞字」。
 */
export const COMPOSITION_END_EVENT = 'zl:composition-end'

/**
 * 最后一次外部事件（keydown / beforeinput / input）之后的静默上限。
 * 软键盘长按退格常常没有 keyup，没有这道闸门会一直删到光标顶。
 */
export const REPEAT_STALE_MS = 3000

/**
 * 能 auto-repeat 的按键 → 发给 PTY 的转义序列。
 * 与 xterm 的默认编码保持一致（DEL / CR / TAB / ESC / CSI ...）。
 */
export const KEY_SEQUENCES: Record<string, string> = {
  Backspace: '\x7f',
  Enter: '\r',
  Tab: '\t',
  Escape: '\x1b',
  ArrowUp: '\x1b[A',
  ArrowDown: '\x1b[B',
  ArrowRight: '\x1b[C',
  ArrowLeft: '\x1b[D',
  Delete: '\x1b[3~',
  Insert: '\x1b[2~',
  Home: '\x1b[H',
  End: '\x1b[F',
  PageUp: '\x1b[5~',
  PageDown: '\x1b[6~'
}

export function isRepeatKey(key: string): boolean {
  return Object.prototype.hasOwnProperty.call(KEY_SEQUENCES, key)
}

export function sequenceFor(key: string): string | null {
  return isRepeatKey(key) ? KEY_SEQUENCES[key] : null
}

/**
 * 一个按键 = 一个 repeater。按下时立即发一次，松开 / 失焦时停。
 * 重复按下同一个键（例如输入法自己带的 repeat）不会重复首发。
 */
export class KeyRepeater {
  private timer = 0
  private active: string | null = null
  private guard: (() => boolean) | null = null
  private readonly delay: number
  private readonly interval: number
  private readonly send: (key: string) => void

  constructor(
    send: (key: string) => void,
    delay: number = REPEAT_DELAY_MS,
    interval: number = REPEAT_INTERVAL_MS
  ) {
    this.send = send
    this.delay = delay
    this.interval = interval
  }

  /** 正在连发的键；null 表示没在 repeat。软键盘去重靠它判断「同一次长按」。 */
  get current(): string | null {
    return this.active
  }

  /**
   * 每次 tick 前问一句还要不要继续。软键盘没有可靠 keyup，
   * 用「外部事件是否还在来」当作长按是否结束的判据。
   */
  setGuard(guard: (() => boolean) | null): void {
    this.guard = guard
  }

  /** 按下：立即发一次并排上 auto-repeat。 */
  start(key: string): void {
    this.arm(key, true)
  }

  /** 只发一次，不排定时器（软键盘首击交给浏览器自己删时用不到，保留给按键栏）。 */
  tap(key: string): void {
    if (isRepeatKey(key)) this.send(key)
  }

  /**
   * 排上 auto-repeat。`sendFirst` 为 false 时首发不在这里发：
   * 软键盘的首次删除由 IME/浏览器完成，我们只接管 400ms 之后的连发。
   */
  arm(key: string, sendFirst: boolean): void {
    if (!isRepeatKey(key)) return
    // 同一个键已经在连发中：不叠加第二个定时器
    if (this.active === key && this.timer !== 0) return
    this.clearTimer()
    this.active = key
    if (sendFirst) this.send(key)
    this.timer = window.setTimeout(() => this.tick(), this.delay)
  }

  private tick(): void {
    this.timer = 0
    const key = this.active
    if (key == null) return
    if (this.guard != null && !this.guard()) {
      this.stop()
      return
    }
    this.send(key)
    this.timer = window.setTimeout(() => this.tick(), this.interval)
  }

  stop(): void {
    this.clearTimer()
    this.active = null
  }

  private clearTimer(): void {
    if (this.timer !== 0) {
      window.clearTimeout(this.timer)
      this.timer = 0
    }
  }

  get repeating(): boolean {
    return this.active != null
  }

  dispose(): void {
    this.setGuard(null)
    this.stop()
  }
}

export interface KeyRepeatBinding {
  dispose(): void
}

function inputKeyOf(event: InputEvent): string | null {
  const type = event.inputType || ''
  if (
    type === 'deleteContentBackward' ||
    type === 'deleteWordBackward' ||
    type === 'deleteSoftLineBackward'
  ) {
    return 'Backspace'
  }
  if (type === 'insertLineBreak' || type === 'insertParagraph') return 'Enter'
  return null
}

/**
 * 把 host（含 xterm 注入的隐藏 textarea）上的键盘事件接到 [repeater]。
 *
 * 关键约定：
 * - 首击由我们发（xterm 侧 attachCustomKeyEventHandler 已挡掉控制键），
 *   并置 `suppressNextNative`，紧随其后的 beforeinput 直接吞掉，避免双删。
 * - repeat 阶段全部 stopPropagation，xterm / IME 都拿不到，只有我们一套定时器。
 * - 软键盘没有 keyup 时靠 [REPEAT_STALE_MS] 的 guard 自动停表。
 * - **只接管控制键**：文本与组词结果一律放行给 xterm，本函数不写 PTY、
 *   不清空 textarea.value（双写 + 清空正是「吞字」的根因）。
 */
export function attachKeyRepeat(host: HTMLElement, repeater: KeyRepeater): KeyRepeatBinding {
  const cleanups: Array<() => void> = []
  let lastSignalAt = 0
  let lastBackspaceAt = 0
  let suppressNextNative = false
  let textarea: HTMLTextAreaElement | null = null
  let observer: MutationObserver | null = null
  /** IME 组词中：组词期间只放行，不做任何写入。 */
  let composing = false

  const touch = (): void => {
    lastSignalAt = performance.now()
  }

  repeater.setGuard(() => performance.now() - lastSignalAt <= REPEAT_STALE_MS)

  const stopAll = (): void => {
    repeater.stop()
    suppressNextNative = false
    lastBackspaceAt = 0
  }

  const onKeyDown = (event: KeyboardEvent): void => {
    // Shift/Ctrl 组合同样不接管：Shift+Tab / Shift+方向键 仍由 xterm 编码；
    // Shift/Ctrl+Enter 由 main.ts 的 custom key handler 编码成换行（xterm 自己
    // 只会把 Enter 折叠成 CR）。修饰键（alt/ctrl/meta）与 shift 一律放行。
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
    // 输入法：229 / Unidentified 表示「事件被 IME 接管」，这里不能吞，
    // 否则输入法收不到按键；交给随后的 beforeinput / input 判定。
    if (event.keyCode === 229 || event.key === 'Unidentified' || event.key === 'Process') {
      touch()
      return
    }
    if (event.isComposing) {
      stopAll()
      return
    }
    const key = event.key
    if (!isRepeatKey(key)) return
    touch()

    if (repeater.current === key) {
      // 系统 / IME 自己的 keydown repeat：只拦事件，绝不叠加定时器
      event.preventDefault()
      event.stopPropagation()
      return
    }

    // 首击（event.repeat 为 true 也照样接管）：自己发一次，再排 400ms 连发
    event.preventDefault()
    event.stopPropagation()
    suppressNextNative = true
    // 软键盘不一定有 keyup：标记只在同一击的窗口内有效，过后自动失效
    window.setTimeout(() => {
      suppressNextNative = false
    }, SOFT_KEY_GAP_MS)
    repeater.arm(key, true)
  }

  const onBeforeInput = (event: Event): void => {
    const input = event as InputEvent
    // 组词期间一律放行：候选/上屏交给 xterm 自己提交，绝不 preventDefault，
    // 否则中文（搜狗英文模式也走组词通道）会被吞掉。
    if (input.isComposing || composing) {
      touch()
      return
    }

    if ((input.inputType || '') === 'insertText') {
      // 可打印字符：只放行，不写 PTY、不动 textarea.value。
      // 写入由 xterm 自己的 input 处理完成；我们在 capture 层清空 value 会让
      // xterm 读到空串，再叠上我们自己那次 write，就是「有时不出、有时少字」。
      touch()
      return
    }

    const key = inputKeyOf(input)
    if (key == null) return
    touch()
    const now = performance.now()

    if (suppressNextNative) {
      // 同一次按键：keydown 已经发过，这里再删一次就是双删
      suppressNextNative = false
      input.preventDefault()
      return
    }

    if (repeater.current === key) {
      // 长按连发中：IME 还在送事件，统一由定时器发，拦掉原生删除
      input.preventDefault()
      return
    }

    const gap = now - lastBackspaceAt
    lastBackspaceAt = now

    if (gap < SOFT_KEY_GAP_MS) {
      // 距上一击不到 80ms：判定为长按连发，交给定时器（不重复叠加），拦掉这一击
      input.preventDefault()
      repeater.arm(key, false)
      return
    }

    // 首击：让 IME/浏览器自己删一个字符，我们只排 400ms 后的连发
    repeater.arm(key, false)
  }

  const onCompositionStart = (): void => {
    composing = true
    stopAll()
  }

  const onCompositionUpdate = (): void => {
    // 组词中：只更新候选，不写 PTY。
    touch()
  }

  const onCompositionEnd = (): void => {
    composing = false
    stopAll()
    // 只上报「组词刚结束」：文本交给 xterm 提交，随后由 bridge 对完全相同的
    // chunk 做 60ms 去重（xterm 的 _finalizeComposition 在中文下会重复提交）。
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new Event(COMPOSITION_END_EVENT))
    }
  }

  const onKeyUp = (event: KeyboardEvent): void => {
    if (event.keyCode === 229 || event.key === 'Unidentified') {
      stopAll()
      return
    }
    if (isRepeatKey(event.key)) stopAll()
  }

  const onBlur = (): void => {
    composing = false
    stopAll()
  }

  const onVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden') stopAll()
  }

  // ---- xterm 隐藏 textarea（软键盘真正的事件源） ----------------------------
  const capture: AddEventListenerOptions = { capture: true }

  const bindTextarea = (): void => {
    if (textarea != null) return
    const found = host.querySelector('textarea.xterm-helper-textarea') as HTMLTextAreaElement | null
    if (found == null) return
    const el = found
    textarea = el
    // 只保留这一套监听：xterm 自身 + 我们这一条。不要再叠匿名 capture 监听。
    el.addEventListener('beforeinput', onBeforeInput, capture)
    el.addEventListener('compositionstart', onCompositionStart, capture)
    el.addEventListener('compositionupdate', onCompositionUpdate, capture)
    el.addEventListener('compositionend', onCompositionEnd, capture)
    el.addEventListener('blur', onBlur, capture)
    cleanups.push(() => {
      el.removeEventListener('beforeinput', onBeforeInput, capture)
      el.removeEventListener('compositionstart', onCompositionStart, capture)
      el.removeEventListener('compositionupdate', onCompositionUpdate, capture)
      el.removeEventListener('compositionend', onCompositionEnd, capture)
      el.removeEventListener('blur', onBlur, capture)
    })
  }

  host.addEventListener('keydown', onKeyDown, capture)
  host.addEventListener('keyup', onKeyUp, capture)
  window.addEventListener('blur', onBlur)
  document.addEventListener('visibilitychange', onVisibilityChange)
  cleanups.push(() => {
    host.removeEventListener('keydown', onKeyDown, capture)
    host.removeEventListener('keyup', onKeyUp, capture)
    window.removeEventListener('blur', onBlur)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  bindTextarea()
  if (textarea == null && typeof MutationObserver !== 'undefined') {
    // term.open() 之后 textarea 才会出现；晚到就等它一次，别每帧轮询
    observer = new MutationObserver(() => {
      bindTextarea()
      if (textarea != null && observer != null) {
        observer.disconnect()
        observer = null
      }
    })
    observer.observe(host, { childList: true, subtree: true })
  }

  return {
    dispose(): void {
      if (observer != null) {
        observer.disconnect()
        observer = null
      }
      cleanups.forEach((fn) => fn())
      cleanups.length = 0
      repeater.setGuard(null)
      repeater.stop()
      textarea = null
    }
  }
}
