/**
 * 与 Android 侧的 JS 桥。
 *
 * JS → Native：`window.zlPty`（addJavascriptInterface 注入，见 PtyWebBridge.kt）
 * Native → JS：`window.__zlPty`（Kotlin 用 evaluateJavascript 调用）
 */

import { COMPOSITION_END_EVENT } from './keyrepeat'

export interface ZlPtyBridge {
  open(id: string, containerUuid: string, cols: number, rows: number): void
  write(id: string, data: string): void
  resize(id: string, cols: number, rows: number): void
  close(id: string): void
  /** 最近输出（base64）。WebView 被系统回收重建后，用它补回终端历史。 */
  history(id: string): string
  copy(text: string): void
  /** 读系统剪贴板；WebView 里 navigator.clipboard 不可用，必须走原生。 */
  readClipboard(): string
  vibrate(ms: number): void
  theme(mode: string): void
  log(message: string): void
  swipe(dir: string): void
}

type NativeBridge = ZlPtyBridge & { __native: true }

const globalAny = window as unknown as Record<string, unknown>

function injected(): ZlPtyBridge | null {
  const raw = globalAny['zlPty']
  if (!raw || typeof raw !== 'object') return null
  const obj = raw as Partial<ZlPtyBridge>
  if (typeof obj.open !== 'function') return null
  return obj as ZlPtyBridge
}

/**
 * 浏览器里直接打开页面时的降级实现，保证前端可以独立调试。
 */
const stub: ZlPtyBridge = {
  open() {
    console.info('[zl] stub open')
  },
  write(_id: string, data: string) {
    // 极简回显，便于验证按键链路
    if (data === '\r') window.__zlPty?.onData(_id, btoa('\r\n'))
    else window.__zlPty?.onData(_id, btoa(data))
  },
  resize() {},
  close(id: string) {
    window.__zlPty?.onExit(id, 0)
  },
  history() {
    return ''
  },
  copy(text: string) {
    void navigator.clipboard?.writeText(text).catch(() => undefined)
  },
  readClipboard() {
    return ''
  },
  vibrate(ms: number) {
    void navigator.vibrate?.(ms)
  },
  theme() {},
  log(message: string) {
    console.info('[zl]', message)
  },
  swipe(dir: string) {
    console.info('[zl] swipe', dir)
  }
}

/**
 * 每次调用时重新解析注入对象。
 *
 * 启动顺序不受我们控制：脚本可能在 `addJavascriptInterface` 之前执行，
 * 这时 `injected()` 为 null；把结果缓存成常量会让 `write()` 永远是空实现
 * （键栏 / 输入法全都不回显）。延迟解析后，桥一注入就立刻生效。
 */
function target(): ZlPtyBridge {
  return injected() ?? stub
}

/**
 * 相邻「完全相同」chunk 的去重窗口。
 *
 * 去重的唯一目的：挡住同一次上屏被写两遍（xterm 的 input/composition 双路径）。
 * 判据是「同一个 id + 完全相同的 data，且紧跟在上一次写入之后」，因此不会
 * 把 `abc` 与 `abd` 混为一谈。
 */
export const DUPLICATE_CHUNK_MS = 30
/**
 * 单字符 chunk 的窗口：同一键的双发发生在同一个事件循环里（几 ms 内），
 * 20ms 已足够挡住双发，又不会把用户快速连按的两个相同字符（`ll`、`00`）吞掉
 * ——人工连按的间隔通常 >40ms。
 */
export const DUPLICATE_CHAR_MS = 20
/** 组词结束后的提交窗口：xterm 的 `_finalizeComposition` 在中文下会重复提交。 */
export const IME_COMMIT_MS = 60

/** 同一段文本只写给 PTY 一次：只有 id+data 与上一次写入完全一致才可能被挡。 */
let lastWriteKey = ''
let lastWriteAt = 0
/** 最近一次 compositionend 的时刻（由 keyrepeat.ts 的自定义事件上报，本身不写 PTY）。 */
let compositionEndAt = 0

/** 记录一次组词结束，供随后的提交做 60ms 去重；不涉及任何写入。 */
export function noteCompositionEnd(): void {
  compositionEndAt = performance.now()
}

if (typeof window !== 'undefined') {
  window.addEventListener(COMPOSITION_END_EVENT, noteCompositionEnd)
}

function isDuplicateWrite(id: string, data: string): boolean {
  if (!data) return false
  const now = performance.now()
  const key = `${id}\u0000${data}`

  // 窗口：组词结束后的 60ms 最宽（IME 会把同一次上屏提交两遍），
  // 多字符 30ms，单字符 20ms。只有「同一 id + 完全相同 data」才可能被挡。
  let windowMs = data.length > 1 ? DUPLICATE_CHUNK_MS : DUPLICATE_CHAR_MS
  if (compositionEndAt !== 0) {
    if (now - compositionEndAt > IME_COMMIT_MS) compositionEndAt = 0
    // 60ms 组词窗口只对多字符提交生效：单字符（Enter/退格/字母）本来就可能
    // 连续快速触发，套上最宽窗口会把键栏连发整段吞掉。
    else if (data.length > 1) windowMs = IME_COMMIT_MS
  }

  if (key === lastWriteKey && now - lastWriteAt < windowMs) {
    // 命中判重时把时间戳回拨半个窗口，而不是直接置为 now：
    // 置 now 会让窗口随每份副本不断后移，连发（间隔≈窗口）永远命中而自锁；
    // 回拨半个窗口既能挡住同一次上屏紧挨着的 2~3 份副本，
    // 又保证窗口从首份副本起算、不会无限延长。
    lastWriteAt = now - windowMs / 2
    return true
  }
  lastWriteKey = key
  lastWriteAt = now
  return false
}

/**
 * 键栏 / 真实按键的直写通道：不参与「同 chunk 去重」。
 *
 * 连发帧每 70ms 就会写出与上一帧完全相同的字节，走带去重的 write 会被当成
 * 同一次上屏的副本吞掉（表现为「长按只发一次」）。这里只服务
 * 「一次按键事件 = 一次写入」的路径；输入法文本仍必须走 bridge.write。
 */
export function writeKey(id: string, data: string): void {
  if (!data) return
  target().write(id, data)
}

export const bridge: ZlPtyBridge = {
  open(id, containerUuid, cols, rows) {
    target().open(id, containerUuid, cols, rows)
  },
  write(id, data) {
    if (isDuplicateWrite(id, data)) return
    target().write(id, data)
  },
  resize(id, cols, rows) {
    target().resize(id, cols, rows)
  },
  close(id) {
    target().close(id)
  },
  history(id) {
    // 旧版原生桥没有 history：返回空串，退化为「没有历史」而不是抛异常。
    try {
      const t = target()
      return typeof t.history === 'function' ? t.history(id) : ''
    } catch {
      return ''
    }
  },
  copy(text) {
    target().copy(text)
  },
  readClipboard() {
    return target().readClipboard()
  },
  vibrate(ms) {
    target().vibrate(ms)
  },
  theme(mode) {
    target().theme(mode)
  },
  log(message) {
    target().log(message)
  },
  swipe(dir) {
    target().swipe(dir)
  }
}

/** 仅用于启动日志：桥可能晚于脚本注入，运行期以 [target] 为准。 */
export const isNative = injected() != null

export interface NativeToJs {
  onData(id: string, base64: string): void
  onExit(id: string, code: number): void
  onTitle(id: string, title: string): void
}

declare global {
  interface Window {
    __zlPty?: NativeToJs
  }
}

/** UTF-8 安全的 base64 → 字节数组。 */
export function decodeBase64(input: string): Uint8Array {
  if (!input) return new Uint8Array(0)
  const binary = atob(input)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}
