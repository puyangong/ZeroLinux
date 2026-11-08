/**
 * ZeroLinux xterm.js 终端页。
 *
 * 一个 WebView 里放多个 xterm 实例，按 sid 切换。
 *
 * JS 侧对 Kotlin 暴露两套接口：
 *   window.zlTerm.create(sid, containerUuid, cols, rows) / show / dispose / setTheme / setFontSize
 *   window.__zlPty.onData(id, base64) / onExit(id, code) / onTitle(id, title)
 *
 * Kotlin 侧对 JS 暴露 window.zlPty（见 bridge.ts）。
 */
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import './style.css'
import { applyDocumentTheme, themeFor, type ThemeMode } from './theme'
import { bridge, decodeBase64, isNative, writeKey } from './bridge'
import { SelectionController, ensureTextInputAttrs } from './selection'
import { KeyBar, applyTextMods } from './keybar'
import { KeyRepeater, attachKeyRepeat, isRepeatKey, sequenceFor } from './keyrepeat'
import { installImeGuard } from './ime-guard'
import { enterSequence, KeyProtocolTracker, type EnterProtocol } from './enter'

interface SessionView {
  sid: string
  containerUuid: string
  root: HTMLDivElement
  host: HTMLDivElement
  layer: HTMLDivElement
  term: Terminal
  fit: FitAddon
  selection: SelectionController
  keybar: KeyBar
  keys: KeyRepeater
  imeGuard: () => void
  /** 前台程序是否启用了 Kitty 键盘协议；决定 Shift/Ctrl+Enter 的编码。 */
  keyProto: KeyProtocolTracker
  disposed: boolean
  /** 选区/长按期间被推迟的 fit，选区结束后补做。 */
  fitDeferred?: boolean
}

const stage = document.getElementById('stage') as HTMLDivElement
const emptyEl = document.getElementById('empty') as HTMLDivElement

const views = new Map<string, SessionView>()
/** 已登记但还没建 xterm 的会话（真正切过去时才 build）。 */
const pending = new Map<string, { containerUuid: string; cols: number; rows: number }>()
/** 懒加载会话在 build 之前收到的输出，建好后按顺序补写。 */
const pendingData = new Map<string, string[]>()
let activeSid: string | null = null
let mode: ThemeMode = 'dark'
let fontSize = 13

const FONT_STACK =
  "'JetBrains Mono', 'Cascadia Code', 'SFMono-Regular', Consolas, monospace"

function syncEmpty(): void {
  emptyEl.style.display = views.size + pending.size === 0 ? 'flex' : 'none'
}

function activeView(): SessionView | null {
  return activeSid ? views.get(activeSid) ?? null : null
}

/**
 * 当前可见会话的 id。
 * 非当前会话的 `.term-host` 会被摘出 DOM（见 setVisible），所以 querySelector
 * 拿到的就是正在显示的那一个；拿不到再退回建视图时的 sid。
 */
function currentSid(fallback: string): string {
  const active = document.querySelector<HTMLElement>('.term-host[data-sid]')
  return active?.dataset.sid || fallback
}

/**
 * 只把当前会话的 DOM 挂在 stage 上。
 *
 * 非当前会话如果只 display:none，仍然参与布局与绘制（xterm 的 renderer 还在
 * 跑）。这里直接把 root 从 DOM 摘掉：xterm 实例继续接收 PTY 输出、保留
 * scrollback，但不占布局、不出图层；切回来再 append + fit + 重绘一屏。
 */
function setVisible(view: SessionView, visible: boolean): void {
  if (!visible) {
    view.root.style.display = 'none'
    if (view.root.parentNode === stage) stage.removeChild(view.root)
    return
  }
  if (view.root.parentNode !== stage) stage.appendChild(view.root)
  view.root.style.display = 'block'
  view.root.style.visibility = 'visible'
  requestAnimationFrame(() => {
    try {
      view.fit.fit()
    } catch {
      /* 容器尺寸还没就绪，下一次 ResizeObserver 会补上 */
    }
    try {
      view.term.refresh(0, Math.max(0, view.term.rows - 1))
    } catch {
      /* xterm 还没准备好时忽略 */
    }
  })
}

function pushResize(view: SessionView): void {
  if (view.disposed) return
  const cols = view.term.cols
  const rows = view.term.rows
  if (cols <= 0 || rows <= 0) return
  bridge.resize(view.sid, cols, rows)
}

function create(sid: string, containerUuid: string, cols: number, rows: number): void {
  if (views.has(sid)) {
    show(sid)
    return
  }
  pending.set(sid, { containerUuid, cols, rows })
  // 第一个会话立即建；后续会话等真正 show 时再创建 xterm 与 DOM。
  if (activeSid == null) show(sid)
  syncEmpty()
}

function build(
  sid: string,
  containerUuid: string,
  cols: number,
  rows: number
): SessionView | null {
  const root = document.createElement('div')
  root.className = 'term-host'
  root.dataset.sid = sid
  // 用 visibility 而不是 display:none：xterm 在 open() 时要测量字符宽度与
  // 容器尺寸，display:none 会测到 0，第一会话的字体/光标会偏一格。这里先
  // 保留布局但不可见，show() 时再打开。
  root.style.display = 'block'
  root.style.visibility = 'hidden'

  const host = document.createElement('div')
  host.className = 'term-surface'
  root.appendChild(host)

  // 选择手柄/高亮自成一层，避免和 xterm 自己的 canvas 抢 z-index
  const layer = document.createElement('div')
  layer.className = 'zl-sel-layer'
  root.appendChild(layer)

  stage.appendChild(root)

  // 前台程序（opencode 等 TUI）的键盘协议状态，随 PTY 输出更新。
  const keyProto = new KeyProtocolTracker()

  // 底部快捷按键栏：常驻，建栏即给 .term-host 挂 zl-keybar-open，CSS 把
  // .term-surface 的 bottom 顶上去；随后在初次布局完成后跑一次 fit()。
  const keybar = new KeyBar({
    // 键栏字节直写：连发帧与上一帧完全相同，不能进 bridge 的去重窗口。
    onSend: (data) => writeKey(currentSid(sid), data),
    // 键栏上的 Shift/Ctrl+Enter 与硬件键盘走同一套编码：裸 Enter 提交，
    // 带修饰键则插入换行，而不是被折叠成 CR。
    enterSequence: (mods) => {
      const protocol: EnterProtocol = {
        kitty: keyProto.kitty,
        bracketedPaste: term.modes.bracketedPasteMode,
      }
      return enterSequence(
        {
          shift: mods.indexOf('shift') >= 0,
          ctrl: mods.indexOf('ctrl') >= 0,
          alt: mods.indexOf('alt') >= 0,
        },
        protocol
      )
    }
  })
  keybar.attach(root)
  root.classList.add('zl-keybar-open')

  // 长按 auto-repeat：控制键首帧发一次，400ms 后每 70ms 补发。
  // 可打印字符不接管（输入法自己会处理），否则会重复出字。
  const keys = new KeyRepeater((key) => {
    const seq = sequenceFor(key)
    if (seq != null) writeKey(currentSid(sid), seq)
  })

  const stopRepeat = (): void => {
    keys.stop()
    keybar.stopRepeat()
  }

  const onVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden') stopRepeat()
  }

  window.addEventListener('blur', stopRepeat)
  document.addEventListener('visibilitychange', onVisibilityChange)

  // 会话销毁时把这些全局监听一并摘掉，否则 WebView 重载后会叠加。
  let detachKeys: (() => void) | null = null
  root.addEventListener('zl:dispose', () => {
    window.removeEventListener('blur', stopRepeat)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    detachKeys?.()
    detachKeys = null
  })

  const term = new Terminal({
    fontFamily: FONT_STACK,
    fontSize,
    lineHeight: 1.18,
    cursorBlink: true,
    cursorStyle: 'bar',
    cursorInactiveStyle: 'outline',
    scrollback: 5000,
    convertEol: false,
    allowProposedApi: true,
    theme: themeFor(mode),
  })
  const fit = new FitAddon()
  term.loadAddon(fit)
  term.open(host)

  // WebView 被系统回收或页面重载后，xterm 是全新空白屏。向原生要最近一段
  // 原始输出回放一次，用户回来时仍能看到离开前的内容（含颜色）。
  try {
    const history = bridge.history(sid)
    if (history) {
      const bytes = decodeBase64(history)
      // 回放里也可能带着前台程序启动时发出的 Kitty 协议切换。
      keyProto.feed(bytes)
      term.write(bytes)
    }
  } catch {
    // 旧版原生桥没有 history：保持空屏，不影响新建会话。
  }

  // 安卓输入法修法：document 捕获阶段清空合成缓冲，先于 xterm 的 textarea 监听。
  // textarea 惰性获取，兼容 WebView 重建。
  const imeGuard = installImeGuard(() => term.textarea ?? null)

  // 软键盘/输入法属性：xterm 默认不设 inputmode 与 autocomplete，WebView 会对
  // 英文做首字母大写与联想，中文组词也可能因此走偏。只补属性，不动 readonly。
  {
    const area = host.querySelector<HTMLTextAreaElement>('textarea.xterm-helper-textarea')
    if (area != null) ensureTextInputAttrs(area)
  }

  // host 捕获层已经拦下控制键，这里再兜一层：万一事件从别处冒进来，
  // xterm 也不会把同一个 keydown 处理两次。
  term.attachCustomKeyEventHandler((event) => {
    if (event.type !== 'keydown') return true

    // Shift/Ctrl+Enter 必须先拦下来自己编码。xterm 6 的 Enter 分支只看
    // altKey，会把这两个组合折叠成 CR，前台 TUI 只会当成「提交」，多行输入
    // 因此无法生效。Alt+Enter 仍交给 xterm（它本来就发 ESC CR）。
    if (
      (event.key === 'Enter' || event.key === 'Return' || event.keyCode === 13) &&
      (event.shiftKey || event.ctrlKey) &&
      !event.altKey &&
      !event.isComposing &&
      event.keyCode !== 229
    ) {
      event.preventDefault()
      event.stopPropagation()
      writeKey(
        currentSid(sid),
        enterSequence(
          { shift: event.shiftKey, ctrl: event.ctrlKey },
          { kitty: keyProto.kitty, bracketedPaste: term.modes.bracketedPasteMode }
        )
      )
      return false
    }

    // 其余 Shift 组合交给 xterm：Shift+Tab / Shift+方向键 有独立的 CSI 编码，
    // 拦截重放会让它们退化成裸键。
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return true
    return !isRepeatKey(event.key)
  })

  // 键盘连发：硬件键盘走 host 上的 keydown；软键盘（搜狗等）长按退格不产生
  // 连续 keydown，只有 xterm 隐藏 textarea 上的 beforeinput/input
  // （inputType === "deleteContentBackward"）以及 keyCode 229，统一接进来。
  //
  // 文本与组词结果不经过这里：xterm 自己是唯一写入路径，本模块只上报
  // compositionend 的时间戳（自定义事件），由 bridge 做 60ms 去重兜底。
  const keyBinding = attachKeyRepeat(host, keys)
  detachKeys = () => keyBinding.dispose()

  const screen = host.querySelector('.xterm-screen') as HTMLElement | null

  // 触摸拖动滚动：手机没有滚轮，xterm 的 viewport 也不响应手指拖动。
  // 这里做 1:1 跟手（位移每满一格滚一行），抬手后按速度给一小段惯性。
  {
    let dragging = false
    let moved = false
    let lastY = 0
    let lastX = 0
    let downY = 0
    let downX = 0
    let lastT = 0
    let acc = 0
    let vel = 0
    let cellH = 18
    let raf = 0

    host.style.touchAction = 'none'

    const viewport = host.querySelector('.xterm-viewport') as HTMLElement | null

    const measure = () => {
      const rows = Math.max(1, term.rows)
      cellH = Math.max(6, (screen?.clientHeight ?? 400) / rows)
    }

    const step = (dy: number) => {
      acc += dy
      // 手指下移（dy>0）= 看更早的历史 = scrollLines(-1)
      while (acc >= cellH) { term.scrollLines(-1); acc -= cellH }
      while (acc <= -cellH) { term.scrollLines(1); acc += cellH }
    }

    const momentum = () => {
      raf = 0
      step(vel * 16)
      vel *= 0.93
      if (Math.abs(vel) >= 0.03) raf = requestAnimationFrame(momentum)
      else vel = 0
    }

    host.addEventListener('pointerdown', (e) => {
      if (e.pointerType !== 'touch') return
      if (raf) { cancelAnimationFrame(raf); raf = 0 }
      measure()
      dragging = true
      moved = false
      lastY = e.clientY
      downY = e.clientY
      downX = e.clientX
      lastX = e.clientX
      lastT = performance.now()
      acc = 0
      vel = 0
    }, { passive: true })

    host.addEventListener('pointermove', (e) => {
      if (!dragging || e.pointerType !== 'touch') return
      // 长按选中 / 拖动扩选期间手势归选区，不滚动、不累积横滑位移。
      if (selection.isSelecting()) return
      const now = performance.now()
      const dy = e.clientY - lastY
      lastY = e.clientY
      lastX = e.clientX
      const dt = Math.max(1, now - lastT)
      lastT = now
      if (!moved && Math.abs(dy) > 2) moved = true
      if (!moved) return
      if (e.cancelable) e.preventDefault()
      step(dy)
      vel = Math.max(-2, Math.min(2, dy / dt))
    }, { passive: false })

    const end = () => {
      if (!dragging) return
      dragging = false
      // 正在选择（长按扩选/拖手柄）时抬手：既不惯性滚动，也不触发抽屉横滑。
      if (selection.isSelecting()) {
        acc = 0
        vel = 0
        moved = false
        return
      }
      // 明确的横向甩动（横移大、竖移小）用来开抽屉：
      // 屏幕边缘那 24dp 归系统的返回手势，从最边缘起手根本滑不开。
      const dx = lastX - downX
      const dyTotal = lastY - downY
      if (Math.abs(dx) > 70 && Math.abs(dyTotal) < 40) {
        acc = 0
        vel = 0
        moved = false
        bridge.swipe(dx > 0 ? 'right' : 'left')
        return
      }
      if (!moved) { acc = 0; vel = 0; return }
      if (Math.abs(vel) > 0.35) raf = requestAnimationFrame(momentum)
      else vel = 0
      acc = 0
      moved = false
    }
    host.addEventListener('pointerup', end, { passive: true })
    host.addEventListener('pointercancel', end, { passive: true })
  }

  const selection = new SelectionController(
    term,
    { screen: screen ?? host, layer },
    (ms) => bridge.vibrate(ms),
    (text) => bridge.copy(text),
    () => {
      // 粘贴必须走 xterm 自己的 paste：它会归一化 CR/LF，并在前台程序启用
      // 括号粘贴时包上 ESC[200~…ESC[201~。直接 bridge.write 会把多行文本
      // 按裸字节灌进 PTY，shell 会逐行立即执行，看起来就是「粘贴被提前提交」。
      const pasteText = (text: string): void => {
        if (!text) return
        try {
          term.paste(text)
        } catch {
          bridge.write(currentSid(sid), text)
        }
      }
      let clip = ''
      try {
        clip = bridge.readClipboard()
      } catch {
        clip = ''
      }
      if (clip) {
        pasteText(clip)
        return
      }
      const nav = navigator as Navigator & { clipboard?: Clipboard }
      nav.clipboard?.readText?.().then(pasteText).catch(() => undefined)
    }
  )

  // CDP 验证用的小钩子：不参与业务逻辑，只读。
  const debugWindow = window as Window & {
    __zlSel?: {
      text: () => string
      dragging: () => boolean
      range: () => unknown
    }
  }
  debugWindow.__zlSel = {
    text: () => selection.selectedText(),
    dragging: () => selection.isSelecting(),
    range: () => selection.currentRange(),
  }

  term.onData((data) => {
    let out = data
    // 仅单个可打印 ASCII 才应用粘滞修饰键；粘贴/IME 多字符/转义序列一律原样放行。
    if (data.length === 1) {
      const c = data.charCodeAt(0)
      if (c >= 0x20 && c <= 0x7e) {
        const mods = keybar.getMods()
        if (mods.length > 0) {
          keybar.consumeModsNow()
          out = applyTextMods(data, mods)
        }
      }
    }
    bridge.write(currentSid(sid), out)
  })
  term.onTitleChange((title) => {
    // 标题变化只在原生侧做日志，UI 顶栏由 Compose 负责
    bridge.log(`title ${sid}: ${title}`)
  })

  const view: SessionView = {
    sid,
    containerUuid,
    root,
    host,
    layer,
    term,
    fit,
    selection,
    keybar,
    keys,
    imeGuard,
    keyProto,
    disposed: false,
  }
  views.set(sid, view)
  // 按键栏常驻：类在上面建栏时就挂了，这里再排一次 fit，保证可用高度已让位。
  scheduleFit(view)

  // 初次布局：给 xterm 一个真实尺寸，否则 cols/rows 会是 0
  const bootCols = cols > 0 ? cols : 80
  const bootRows = rows > 0 ? rows : 24
  try {
    fit.fit()
  } catch {
    /* ignore */
  }
  if (term.cols !== bootCols || term.rows !== bootRows) {
    // 以实际测量值为准，但保证非零
    if (term.cols <= 0 || term.rows <= 0) {
      term.resize(bootCols, bootRows)
    }
  }

  const observer = new ResizeObserver(() => scheduleFit(view))
  observer.observe(root)
  view.root.addEventListener('zl:dispose', () => observer.disconnect())

  window.addEventListener('resize', () => scheduleFit(view))
  window.visualViewport?.addEventListener('resize', () => scheduleFit(view))

  bridge.log(`session ${sid} ready ${term.cols}x${term.rows}`)

  // 懒加载期间先到的输出：xterm 建好后按顺序补写。
  const queued = pendingData.get(sid)
  if (queued != null) {
    pendingData.delete(sid)
    queued.forEach((chunk) => {
      const bytes = decodeBase64(chunk)
      keyProto.feed(bytes)
      term.write(bytes)
    })
  }

  return view
}

const fitTimers = new WeakMap<SessionView, number>()

function scheduleFit(view: SessionView): void {
  if (view.disposed) return
  // 输入法开合期间不重排：fit 会改 cols/rows，光标与选区跟着漂。
  if ((window as any).__zlImeOn) return
  // 正在长按扩选/拖手柄时同样冻结：fit 会触发 PTY 重绘，选区文本会被换掉。
  if (view.selection.isSelecting()) {
    view.fitDeferred = true
    return
  }
  const prev = fitTimers.get(view)
  if (prev !== undefined) window.clearTimeout(prev)
  const timer = window.setTimeout(() => {
    fitTimers.delete(view)
    if (view.disposed) return
    if (view.selection.isSelecting()) {
      view.fitDeferred = true
      return
    }
    const rect = view.host.getBoundingClientRect()
    if (rect.width < 20 || rect.height < 20) return
    const beforeY = view.term.buffer.active.viewportY
    try {
      view.fit.fit()
    } catch {
      return
    }
    const afterY = view.term.buffer.active.viewportY
    if (beforeY !== afterY) {
      try {
        view.term.scrollLines(beforeY - afterY)
      } catch {
        /* 恢复失败时保持 fit 后的位置 */
      }
    }
    pushResize(view)
    view.fitDeferred = false
  }, 180)
  fitTimers.set(view, timer)
}

// 输入法收起后由宿主调用：IME 期间 scheduleFit 被跳过，这里补一次。
;(window as any).__zlRefit = () => {
  window.dispatchEvent(new Event('resize'))
}

// 选区结束（点空白/切换/销毁）后，补做选区期间被冻结的 fit。
window.addEventListener('zl:selection-end', () => {
  const view = activeView()
  if (view != null && view.fitDeferred) {
    view.fitDeferred = false
    scheduleFit(view)
  }
})

function show(sid: string): void {
  let view = views.get(sid)
  if (view == null) {
    const cfg = pending.get(sid)
    if (cfg == null) return
    pending.delete(sid)
    view = build(sid, cfg.containerUuid, cfg.cols, cfg.rows)
  }
  if (view == null) return
  activeSid = sid
  views.forEach((v, key) => setVisible(v, key === sid))
  // 不主动 focus：新会话/切换会话不应自动弹出输入法，等用户点终端再聚焦。
  scheduleFit(view)
  syncEmpty()
}

/**
 * 把当前会话滚到最新一行。
 *
 * 键盘弹起时 WebView 会变矮，xterm 的 fit() 在 180ms 后重排，并按「保持原
 * 视口位置」的规则补 scroll；用户此前翻到历史里时就会停在中间，输入时看不
 * 到提示符。宿主在输入法真正显示后调这个接口，把视口拉回底部。
 */
function scrollToBottom(): void {
  const view = activeView()
  if (view == null || view.disposed) return
  const run = (): void => {
    if (view.disposed) return
    try {
      view.term.scrollToBottom()
    } catch {
      /* xterm 还没准备好时忽略 */
    }
  }
  run()
  requestAnimationFrame(run)
}

function dispose(sid: string): void {
  pending.delete(sid)
  pendingData.delete(sid)
  const view = views.get(sid)
  if (!view) {
    syncEmpty()
    return
  }
  view.disposed = true
  view.root.dispatchEvent(new Event('zl:dispose'))
  try {
    view.keys.dispose()
    view.keybar.dispose()
  } catch {
    /* ignore */
  }
  try {
    view.selection.dispose()
    view.imeGuard()
  } catch {
    /* ignore */
  }
  try {
    view.term.dispose()
  } catch {
    /* ignore */
  }
  view.root.remove()
  views.delete(sid)
  if (activeSid === sid) {
    activeSid = null
    const nextView = views.keys().next()
    const nextPending = pending.keys().next()
    if (!nextView.done) show(nextView.value)
    else if (!nextPending.done) show(nextPending.value)
  }
  syncEmpty()
}

/** 关闭全部会话（Kotlin 侧“全部关闭”直接调这个，避免遗留 xterm 实例）。 */
function disposeAll(): void {
  // 先清 active，dispose 里就不会边删边 show 下一个。
  activeSid = null
  Array.from(views.keys()).forEach((sid) => dispose(sid))
  pending.clear()
  pendingData.clear()
  syncEmpty()
}

function setTheme(next: ThemeMode): void {
  mode = next
  applyDocumentTheme(next)
  views.forEach((view) => {
    view.term.options.theme = themeFor(next)
  })
}

function setFontSize(px: number): void {
  const next = Math.max(10, Math.min(26, Math.round(px)))
  if (next === fontSize) return
  fontSize = next
  views.forEach((view) => {
    view.term.options.fontSize = next
    try {
      view.fit.fit()
    } catch {
      /* ignore */
    }
    pushResize(view)
  })
}

// ---- Native → JS 回调 -------------------------------------------------------

window.__zlPty = {
  onData(id: string, base64: string): void {
    const view = views.get(id)
    if (view != null && !view.disposed) {
      const bytes = decodeBase64(base64)
      // 前台程序可能在任意时刻开关 Kitty 键盘协议；先更新编码状态再写屏。
      view.keyProto.feed(bytes)
      view.term.write(bytes)
      return
    }
    // 懒加载会话还没建 xterm：留最后 50 段，build 时补写。
    if (pending.has(id)) {
      const chunks = pendingData.get(id) ?? []
      chunks.push(base64)
      if (chunks.length > 50) chunks.shift()
      pendingData.set(id, chunks)
    }
  },
  onExit(id: string, code: number): void {
    const view = views.get(id)
    if (!view || view.disposed) return
    const shown = code < 0 ? `signal ${-code}` : `exit ${code}`
    view.term.write(`\r\n\u001b[2m[${shown}]\u001b[0m\r\n`)
  },
  onTitle(id: string, title: string): void {
    const view = views.get(id)
    if (!view || view.disposed) return
    view.root.dataset.title = title
  },
}

/**
 * Kotlin 侧入口。用 addJavascriptInterface 不行（需要返回值/回调），
 * 统一用 evaluateJavascript 调这些方法。
 */
window.zlTerm = {
  create,
  show,
  dispose,
  disposeAll,
  setTheme,
  setFontSize,
  scrollToBottom,
  activeSid: () => activeSid,
  sessionCount: () => views.size + pending.size,
  ready: () => true,
  native: isNative,
}

// 启动时按系统配色初始化一次，随后由 Kotlin 调 setTheme 覆盖
const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)')?.matches ?? true
applyDocumentTheme(prefersDark ? 'dark' : 'light')
syncEmpty()
bridge.log(`zerolinux terminal booted (native=${isNative})`)
