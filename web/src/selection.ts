/**
 * 长按选中 + 可拖拽手柄。
 *
 * 常量：HANDLE_RADIUS / HANDLE_GAP / 460ms / 10px，
 * 44px 命中区 / 26px 圆点，选区按 expandSelection 逻辑扩展。
 *
 * 高亮层由本模块自绘，不依赖 xterm 的选区 API —— 多行选区在各版本
 * xterm 上的行为差异太大，自绘更可控，复制文本也从 buffer 直接取。
 * 插入光标完全交给 xterm 原生 bar 光标（cursorStyle:'bar'），本模块
 * 不再画任何竖条，避免和光标混淆。
 */

/**
 * 选区模块需要的最小终端能力。
 *
 * xterm 的 `Terminal` 在结构上满足它，终端页直接传实例。
 * 选区逻辑只有这一份。
 */
export interface SelectionLine {
  readonly isWrapped: boolean
  translateToString(trimRight?: boolean): string
}

export interface SelectionTerminal {
  readonly rows: number
  readonly cols: number
  readonly buffer: {
    readonly active: {
      readonly viewportY: number
      /** 缓冲区底部对应的绝对行号（未滚出历史时与 viewportY 相同）。 */
      readonly baseY?: number
      getLine(y: number): SelectionLine | null | undefined
    }
  }
  clearSelection(): void
  /**
   * 尺寸变化回调（软键盘弹出 → fit 改 cols/rows → 旧的行列号失效）。
   * xterm 的 `Terminal` 原生满足这个签名；不支持时省略即可，
   * 由 resize / visualViewport 事件兜底。
   */
  onResize?(
    listener: (event: { cols: number; rows: number }) => void
  ): { dispose(): void } | undefined
}

/**
 * 锚定文本的行列换算：CJK 宽字符在 buffer 里占两列，而
 * `translateToString()` 只输出一个码位。字符串下标 → 列号必须按显示宽度累加，
 * 否则中文选区在 reflow 后会整体左移一列。
 */
function charCells(ch: string): number {
  const cp = ch.codePointAt(0)
  if (cp == null) return 1
  return isWideCodePoint(cp) ? 2 : 1
}

function isWideCodePoint(cp: number): boolean {
  return (
    (cp >= 0x1100 && cp <= 0x115f) ||
    (cp >= 0x2e80 && cp <= 0x303e) ||
    (cp >= 0x3041 && cp <= 0x33ff) ||
    (cp >= 0x3400 && cp <= 0x4dbf) ||
    (cp >= 0x4e00 && cp <= 0x9fff) ||
    (cp >= 0xa000 && cp <= 0xa4cf) ||
    (cp >= 0xac00 && cp <= 0xd7a3) ||
    (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0xfe10 && cp <= 0xfe19) ||
    (cp >= 0xfe30 && cp <= 0xfe6f) ||
    (cp >= 0xff00 && cp <= 0xff60) ||
    (cp >= 0xffe0 && cp <= 0xffe6) ||
    (cp >= 0x1f300 && cp <= 0x1f64f) ||
    (cp >= 0x1f900 && cp <= 0x1f9ff) ||
    (cp >= 0x20000 && cp <= 0x3fffd)
  )
}

/** 字符串下标（码位偏移）→ 该行内的显示列号。 */
function colAtUnit(lineText: string, unitOffset: number): number {
  let col = 0
  let i = 0
  for (const ch of lineText) {
    if (i >= unitOffset) break
    i += ch.length
    col += charCells(ch)
  }
  return col
}

/** 圆点视觉半径；对应 .zl-sel-handle-dot 的 26px 直径。 */
export const HANDLE_RADIUS = 13
/** 圆心相对字符行的外扩距离：加大后圆点离文字更远，不再压住字形。 */
export const HANDLE_GAP = 30
/** 圆点在视觉半径之外再外扩的热区（拇指触控容错）。 */
export const DOT_HIT_PAD = 43
/** 命中半径 = 视觉半径 + 热区 = 56px；.zl-sel-handle 的 112px 命中盒与之对应。 */
export const DOT_HIT_RADIUS = HANDLE_RADIUS + DOT_HIT_PAD

const LONG_PRESS_MS = 460
const MOVE_TOLERANCE = 10
/** 长按选中后，手指移动超过这个距离才把选区交给拖动扩选。 */
const PRESS_DRAG_THRESHOLD = 8
const SUPPRESS_MS = 1000
const RESTORE_DELAY_MS = 40

/**
 * 是否有 IME 组词会话正在进行（document 级 compositionstart/end 维护）。
 *
 * Android WebView + 搜狗在 `textarea.xterm-helper-textarea` 上组词时，会建立
 * 自己的合成选区；此时 document 的 selectionchange 也会带着 textarea 内的
 * anchor 触发。组词期间一律不清原生选区，否则候选条永远出不来。
 */
let composing = false

/** 是否是可输入文本的元素 —— IME 组词只可能发生在这些元素上。 */
function isTextEditable(el: Element | null): boolean {
  if (el == null) return false
  if (el instanceof HTMLTextAreaElement || el instanceof HTMLInputElement) return true
  return (el as HTMLElement).isContentEditable === true
}

/** 词边界字符（含中英文标点）。 */
const DELIMITERS = new Set(
  (' \t\n\r\f\v' +
    '!"#$%&\'()*+,;<>?@[\\]^`{|}~' +
    '，。、；：？！（）【】《》“”‘’…—·').split('')
)

export function expandSelection(text: string, index: number): [number, number] {
  if (text.length === 0) return [0, 0]
  let i = Math.max(0, Math.min(text.length - 1, index))
  if (DELIMITERS.has(text[i])) {
    // 落在分隔符上：直接选中这个字符
    return [i, i + 1]
  }
  let start = i
  while (start > 0 && !DELIMITERS.has(text[start - 1])) start--
  let end = i + 1
  while (end < text.length && !DELIMITERS.has(text[end])) end++
  return [start, end]
}

export interface SelectionRange {
  /** viewport 行号（0 = 屏幕第一行） */
  startRow: number
  startCol: number
  endRow: number
  endCol: number
}

export interface SelectionHost {
  /** xterm 的 .xterm-screen 元素，用来算 cell 几何 */
  screen: HTMLElement
  /** 覆盖层容器，和 screen 同尺寸 */
  layer: HTMLElement
  /**
   * 这一层是否拥有当前手势。省略时沿用祖先 `.term-host` 约定；
   * 也可以自行判定「是否落在终端上」。
   */
  owns?: (target: HTMLElement | null) => boolean
  /**
   * 是否创建选区两端的拖拽圆点，默认 true。
   * 传 false 后不再创建手柄，也不做距离命中。
   */
  handles?: boolean
  /**
   * 由本模块独占终端区域的指针事件（默认 false）。
   *
   * true 时，document 捕获阶段对落在 `.xterm-screen` 里的 pointerdown /
   * pointermove / pointerup 一律 `stopPropagation()`：宿主 xterm 的选区与
   * 长按逻辑、以及 WebView 自带的长按选词/ActionMode 完全收不到事件。
   * 长按选词只由本模块的 `selectWordAt()` 产生。
   */
  capturePointer?: boolean
}

interface Cell {
  row: number
  col: number
}

interface DragState {
  which: 'start' | 'end'
  /** 拖动时固定不动的那一端 */
  anchor: Cell
  /**
   * handle = 抓圆点拖动，按 cell 精确扩选；
   * press  = 长按选词后不抬手直接拖，按词边界扩选。
   */
  mode: 'handle' | 'press'
  /** 起手点坐标，press 模式用来做 8px 阈值。 */
  originX: number
  originY: number
  armed: boolean
  /**
   * 抓圆点时的端点基准 cell。纯偏移算法 = base + round(位移 / cell 尺寸)，
   * 与指针当前落在哪个 cell 无关（圆点可能被裁到可视区外）。
   */
  baseRow?: number
  baseCol?: number
  /**
   * 两个圆点重叠（选区被拖到零宽）时，按下无法分辨抓的是哪一端：
   * 先不下结论，等第一次有效位移的方向再决定扩起点还是终点。
   */
  pair?: boolean
  /** press 模式：长按原始选区的词尾，向左扩选时右端固定在这里。 */
  baseEnd?: Cell
}

export class SelectionController {
  private range: SelectionRange | null = null
  private orb: HTMLElement | null = null
  private dotStart: HTMLElement | null = null
  private dotEnd: HTMLElement | null = null
  private shield: HTMLElement | null = null
  private copyBtn: HTMLButtonElement | null = null
  /** 复制/粘贴点过后先收起按钮组，选区保持到用户下一次操作。 */
  private menuHidden = false
  private pasteBtn: HTMLButtonElement | null = null

  /** 两个圆点的实际中心（layer 坐标系），供「距离命中」使用。 */
  private dotPos: {
    start: { x: number; y: number }
    end: { x: number; y: number }
  } | null = null

  private suppressUntil = 0
  private drag: DragState | null = null
  private timer: number | null = null
  /** 上次锚定时的终端几何；键盘开合若未改 cols/rows，选区只需重画、无需重锚。 */
  private lastCols = 0
  private lastRows = 0
  private startX = 0
  private startY = 0
  private restoreTimer: number | null = null

  // ------------------------------------------------------- 手势热路径缓存
  // pointermove 挂在 document 捕获阶段。平时（滚动/聊天页点击）不参与选区，
  // 若每个 move 都 getBoundingClientRect()，120Hz 下就是每秒上百次强制 layout，
  // 整页都会掉帧。rect 短 TTL 缓存 + 只有拖拽/长按等待时才处理 move。
  private rectCache: DOMRect | null = null
  private rectAt = 0
  /** 只在拖手柄期间挂上的非 passive 监听，用来阻止页面滚动；平时 move 保持 passive。 */
  private dragBlock: ((event: PointerEvent) => void) | null = null
  private renderRaf: number | null = null

  // ------------------------------------------------------- 选区锚定（reflow）
  /**
   * 选区建立时的文本快照与起始绝对行号。
   *
   * range 里存的是 viewport 行列号，软键盘弹出 → fit 改变 cols/rows → xterm
   * 重新折行后这些行列号就指向了别的文字。锚定文本 + 绝对行号让 reanchor()
   * 能在新 buffer 里重新找回同一段文字，选区因此不会漂到别处。
   */
  private anchorText: string | null = null
  private anchorStartRow = 0
  /** 180ms 防抖 + 一帧 rAF：等 xterm 把新尺寸下的行内容刷完再找锚。 */
  private reanchorTimer: number | null = null
  private reanchorRaf: number | null = null
  /** term.onResize 的退订句柄。 */
  private termResizeSub: { dispose(): void } | null = null
  /** 键盘动画结束后再摆一次菜单/圆点，避免停在动画中的旧位置。 */
  private repositionTimer: number | null = null

  /** 是否启用两端拖拽圆点（默认 true）。 */
  private readonly handles: boolean

  /** 是否独占终端区域内的指针事件（见 SelectionHost.capturePointer）。 */
  private readonly capturePointer: boolean

  /**
   * 本次 pointerdown 是否已被选区控制器接管。
   *
   * .zl-sel-shield 是 body 级 fixed 覆盖层，render() 一执行它就变成
   * pointer-events:auto 的命中目标；同一手势里冒泡到 shield 的 pointerdown
   * 会无条件 clear()，把刚画好的选区整层隐藏。document 捕获阶段先把这个标记
   * 置上，shield 的内部 handler 看到就只交还焦点、不再清理。
   */
  private ownedDown = false

  /** 本次手势的落点是否在终端屏幕矩形内（抬手时据此把焦点交回 xterm）。 */
  private downInScreen = false

  /** 本次手势自己刚画出选区（长按选词 / 扩选）时为 true：抬手别抢焦点。 */
  private justSelected = false

  /**
   * 本次手势是否已经产生明显位移。
   *
   * 终端页把「触点拖动」用于滚动历史；如果抬手仍按普通点按处理，就会把焦点
   * 塞回 textarea 并弹出软键盘，表现为「一滑就弹键盘」。原生终端不会这样，
   * 因此位移超过阈值后，本次手势不再触发 focusTerminal()。
   */
  private moved = false

  constructor(
    private readonly term: SelectionTerminal,
    private readonly host: SelectionHost,
    private readonly vibrate: (ms: number) => void,
    private readonly copy: (text: string) => void,
    private readonly paste?: () => void
  ) {
    this.handles = this.host.handles !== false
    this.capturePointer = this.host.capturePointer === true
    this.orb = document.createElement('div')
    this.orb.className = 'zl-sel-orb'
    this.host.layer.appendChild(this.orb)

    if (this.handles) {
      this.dotStart = this.makeDot('start')
      this.dotEnd = this.makeDot('end')
    }

    this.shield = document.createElement('div')
    this.shield.className = 'zl-sel-shield'
    this.shield.addEventListener('pointerdown', (e) => {
      // 自愈：走到这说明盾层是 auto 的。若此刻已经没有选区，它就是上一次
      // 手势/渲染留下的空壳，直接整层复位，让下一次点击能落到页面上。
      if (!this.range) {
        e.stopPropagation()
        this.clear()
        return
      }
      // 选中态下遮挡层会吞掉整片触摸事件。点一下应当取消选择并让后续手势
      // 回到终端，否则选区永远消不掉、也再点不出新的长按。
      // 但这一次 pointerdown 若已被 document 捕获阶段的 onPointerDown 接管
      // （ownedDown），这里绝不能再 clear()：shield 在 render() 之后
      // 立刻是命中目标，重复清理会把长按刚画出来的选区整层隐藏。
      // 不再 preventDefault：落点在终端内时顺手把焦点交回 xterm，保证
      // 一次点击既能收起选区，又能直接接着输入。
      // 选区手势不碰 IME：这里既不 focusTerminal()，也不让 pointerdown 的默认
      // 焦点迁移把 textarea 弄失焦（失焦 = Android 立刻收起输入法）。
      // 点盾层只负责收起选区，键盘与焦点一律保持原样。
      e.preventDefault()
      e.stopPropagation()
      // 落在键栏上：这是按键手势，绝不能把焦点交给终端（会弹软键盘盖住键栏），
      // 也不该顺手清掉选区。
      if (this.isKeybarPoint(e.clientX, e.clientY)) return
      if (!this.ownedDown) this.clear()
    })
    this.host.layer.appendChild(this.shield)

    this.copyBtn = document.createElement('button')
    this.copyBtn.className = 'zl-sel-copy'
    this.copyBtn.textContent = '复制'
    this.copyBtn.addEventListener('pointerdown', (e) => {
      // 阻止 <button> 的默认聚焦：按钮一旦夺走焦点，textarea 失焦，Android
      // 会顺手把输入法收起来。复制只该处理剪贴板，不该改变 IME 状态。
      e.preventDefault()
      e.stopPropagation()
    })
    this.copyBtn.addEventListener('click', (e) => {
      e.stopPropagation()
      const text = this.selectedText()
      if (text) {
        this.copy(text)
        this.vibrate(12)
        this.menuHidden = true
        this.copyBtn.style.display = 'none'
        if (this.pasteBtn) this.pasteBtn.style.display = 'none'
        // 复制后**不** clear()：选区高亮与手柄必须留在原位，锚定文本一并保留，
        // 这样紧接着弹出的输入法触发 reflow 时还能把选区 reanchor 回原文字。
      }
    })
    this.host.layer.appendChild(this.copyBtn)

    if (this.paste) {
      this.pasteBtn = document.createElement('button')
      this.pasteBtn.className = 'zl-sel-paste'
      this.pasteBtn.textContent = '粘贴'
      this.pasteBtn.addEventListener('pointerdown', (e) => {
        e.preventDefault()
        e.stopPropagation()
      })
      this.pasteBtn.addEventListener('click', (e) => {
        e.stopPropagation()
        this.paste?.()
        this.vibrate(12)
        this.menuHidden = true
        this.clear()
      })
      this.host.layer.appendChild(this.pasteBtn)
    }

    this.install()
    this.hide()
  }

  private makeDot(which: 'start' | 'end'): HTMLElement {
    const hit = document.createElement('div')
    hit.className = 'zl-sel-handle'
    hit.dataset.which = which
    const dot = document.createElement('div')
    dot.className = 'zl-sel-handle-dot'
    hit.appendChild(dot)
    this.host.layer.appendChild(hit)
    return hit
  }

  private install(): void {
    // 选择层是 pointer-events:none（否则会吃掉输入），事件必须挂在 document
    // 的捕获阶段，才能「在终端任意位置长按即可选中」。
    document.addEventListener('pointerdown', this.onPointerDown, {
      capture: true,
      passive: false,
    })
    document.addEventListener('pointermove', this.onPointerMove, {
      capture: true,
      // 平时必须 passive：document 上的非 passive pointermove 会让合成器每帧
      // 等主线程，滚动/输入都会被拖慢。真正需要 preventDefault 的只有「拖手柄」
      // 那一段，届时临时挂一个非 passive 监听（见 beginDrag）。
      passive: true,
    })
    document.addEventListener('pointerup', this.onPointerUp, { capture: true })
    document.addEventListener('pointercancel', this.onPointerCancel, { capture: true })
    // 彻底掐掉 WebView 的原生文本选择：selectstart 直接拒绝，selectionchange
    // 作为兜底清掉已经产生的选区，原生蓝色手柄/ActionMode 就不会再出现。
    document.addEventListener('selectstart', this.onSelectStart, true)
    document.addEventListener('selectionchange', this.onSelectionChange)
    document.addEventListener('compositionstart', this.onCompositionStart, true)
    document.addEventListener('compositionend', this.onCompositionEnd, true)
    window.addEventListener('resize', this.onViewportChange, { passive: true })
    window.visualViewport?.addEventListener('resize', this.onViewportChange, { passive: true })
    // 软键盘收起/地址栏伸缩时 visualViewport 会滚动但尺寸不变，单听 resize 会漏。
    window.visualViewport?.addEventListener('scroll', this.onViewportChange, { passive: true })
    // xterm 自己 fit 完（cols/rows 变化）后回调：此时旧的行列号已彻底失效。
    this.termResizeSub = this.term.onResize?.(this.onTermResize) ?? null

    // 只读调试口：确认 end 圆点钳制基准到底该取 screen 底还是 layer 底。
    ;(window as unknown as Record<string, unknown>)['__zlMetrics'] = () => {
      const r = this.host.screen.getBoundingClientRect()
      const lr = this.host.layer.getBoundingClientRect()
      const { h } = this.metrics()
      const offsetY = r.top - lr.top
      const screenBottom = offsetY + this.term.rows * h
      return {
        offsetY,
        h,
        rows: this.term.rows,
        layerClientHeight: this.host.layer.clientHeight,
        screenH: this.host.screen?.clientHeight,
        vv: window.visualViewport?.height,
        screenRect: { top: r.top, bottom: r.bottom, height: r.height },
        layerRect: { top: lr.top, bottom: lr.bottom, height: lr.height },
        screenBottom,
        viewBottom: Math.min(screenBottom, this.host.layer.clientHeight),
        dotPos: this.dotPos,
      }
    }
  }

  dispose(): void {
    document.removeEventListener('pointerdown', this.onPointerDown, { capture: true } as never)
    document.removeEventListener('pointermove', this.onPointerMove, { capture: true } as never)
    document.removeEventListener('pointerup', this.onPointerUp, { capture: true } as never)
    document.removeEventListener('pointercancel', this.onPointerCancel, { capture: true } as never)
    document.removeEventListener('selectstart', this.onSelectStart, true)
    document.removeEventListener('selectionchange', this.onSelectionChange)
    document.removeEventListener('compositionstart', this.onCompositionStart, true)
    document.removeEventListener('compositionend', this.onCompositionEnd, true)
    window.removeEventListener('resize', this.onViewportChange)
    window.visualViewport?.removeEventListener('resize', this.onViewportChange)
    window.visualViewport?.removeEventListener('scroll', this.onViewportChange)
    this.termResizeSub?.dispose()
    this.termResizeSub = null
    this.cancelReanchor()
    this.clearTimers()
    this.ownedDown = false
    this.downInScreen = false
    this.justSelected = false
    this.moved = false
    ;(window as any).__zlSelectGesture = false
    // 销毁时连同 range 一起清掉：既复位盾层，也不让 isSelecting() 继续为真。
    this.range = null
    this.stopDrag()
    this.anchorText = null
    this.anchorStartRow = 0
    this.hide()
    this.notifySelectionEnd()
  }

  /**
   * 视口 / 缓冲区尺寸变化后的统一入口：只在有选区时做事，走防抖 + rAF。
   *
   * 没有锚定文本（理论上不该发生：applyRange 一定先快照）时退回旧的
   * `render(this.range)`，至少按新几何重画一遍高亮。
   */
  private onViewportChange = (): void => {
    // 键盘开合/页面滚动后屏幕矩形会变，缓存必须作废。
    this.rectCache = null
    this.rectAt = 0
    if (!this.range) return
    // 只有 cols/rows 真的变了（发生了终端重排）才需要按锚定文本重找；
    // 单纯可视区域移动（键盘开合）按原 range 重画即可，避免重锚失败清掉选区。
    const cols = this.term.cols
    const rows = this.term.rows
    const reflowed = cols !== this.lastCols || rows !== this.lastRows
    this.lastCols = cols
    this.lastRows = rows
    if (!reflowed) {
      this.render(this.range)
      this.scheduleReposition()
      return
    }
    if (this.anchorText == null) {
      this.render(this.range)
      this.scheduleReposition()
      return
    }
    this.scheduleReanchor()
  }

  /** term.onResize：xterm 已经按新 cols/rows 重排完，可以重新锚定。 */
  private onTermResize = (): void => {
    this.lastCols = this.term.cols
    this.lastRows = this.term.rows
    if ((window as any).__zlImeOn) return
    if (this.range) this.scheduleReanchor()
  }

  private cancelReanchor(): void {
    if (this.reanchorTimer != null) {
      window.clearTimeout(this.reanchorTimer)
      this.reanchorTimer = null
    }
    if (this.reanchorRaf != null) {
      cancelAnimationFrame(this.reanchorRaf)
      this.reanchorRaf = null
    }
  }

  /** 键盘动画约 250ms；结束再摆一次，菜单/圆点不会停在动画中的旧位置。 */
  private scheduleReposition(): void {
    if (this.repositionTimer != null) window.clearTimeout(this.repositionTimer)
    this.repositionTimer = window.setTimeout(() => {
      this.repositionTimer = null
      if (this.range) this.render(this.range)
    }, 260)
  }

  /** 180ms 防抖（等 IME 动完）+ 一帧 rAF（等 xterm 刷完新行）。 */
  private scheduleReanchor(): void {
    if ((window as any).__zlImeOn) return
    if (!this.range || this.anchorText == null) return
    if (this.reanchorTimer != null) window.clearTimeout(this.reanchorTimer)
    this.reanchorTimer = window.setTimeout(() => {
      this.reanchorTimer = null
      if (this.reanchorRaf != null) cancelAnimationFrame(this.reanchorRaf)
      this.reanchorRaf = requestAnimationFrame(() => {
        this.reanchorRaf = null
        this.reanchor()
      })
    }, 180)
  }

  /** viewport 行号 → 缓冲区绝对行号（getLine 用的是绝对下标）。 */
  private absoluteRow(viewportRow: number): number {
    const active = this.term.buffer.active
    return active.viewportY + viewportRow
  }

  /** 终端区域发起的原生选择一律拒绝，长按只走自绘选区。 */
  private onSelectStart = (event: Event): void => {
    const target = event.target as Node | null
    const el = target instanceof Element ? target : target?.parentElement ?? null
    // 可编辑元素（尤其是 xterm-helper-textarea）上的 selectstart 属于输入法
    // 建立组词/光标选区的一部分：放行，绝不 preventDefault。
    if (isTextEditable(el) || el?.closest?.('textarea, input')) return
    if (el?.closest?.('.xterm')) event.preventDefault()
  }

  private onCompositionStart = (): void => {
    composing = true
  }

  private onCompositionEnd = (): void => {
    composing = false
  }

  /**
   * 兜底：某些 WebView 仍会先建立原生选区，直接清空。
   *
   * 这条路径只允许清浏览器原生选区，绝不能调用 clear()/hide() —— 宿主
   * 自己的 xterm 也会建原生选区，清一次就等于把长按刚画出来的自绘选区藏掉。
   *
   * 组词期间或焦点在可编辑元素（textarea/input）上时直接跳过：Android WebView
   * 上 removeAllRanges() 会把 IME 的 composing region 一起打断，中文候选条
   * 因此始终不出现。
   */
  private onSelectionChange = (): void => {
    if (composing || isTextEditable(document.activeElement)) return
    const sel = document.getSelection()
    if (!sel || sel.rangeCount === 0) return
    const node = sel.anchorNode
    const el = node instanceof Element ? node : node?.parentElement ?? null
    if (el?.closest?.('.xterm')) sel.removeAllRanges()
  }

  private onPointerDown = (event: PointerEvent): void => {
    // 新手势开始：丢掉上一轮的 rect 缓存（键盘开合、滚动都会改变屏幕位置）。
    this.rectCache = null
    this.rectAt = 0
    // 上一轮若在 pointerup 之外被系统打断，守卫可能还挂着；新手势先复位。
    ;(window as any).__zlSelectGesture = false
    // 键栏覆盖在终端屏幕底部（.xterm-screen 的矩形包含键栏坐标），
    // 若不在这里短路：capturePointer 会把 pointerdown 吞掉、并记下
    // downInScreen=true，抬手时 focusTerminal() 弹软键盘盖住整条键栏。
    // 键栏自己的 touchstart/pointerdown 负责发键，这里只让路。
    if (this.isKeybarEvent(event)) {
      this.stopDrag()
      this.ownedDown = false
      this.downInScreen = false
      this.justSelected = false
      this.clearTimers()
      return
    }
    const target = event.target as HTMLElement | null
    // 只有落在终端本体（.xterm）或选区层（手柄/复制粘贴按钮）上的手势才归本控制器。
    // `.xterm` 可能是 fixed 全屏：若只按坐标命中就 stopPropagation +
    // preventDefault，宿主放在终端上方的按钮会被整条吞掉，委托监听根本
    // 收不到 click（表现就是「点不动」）。
    const inTermUi = target?.closest?.('.xterm, .zl-sel-layer') != null
    // 宿主的交互控件永远放行：即使它们被 fixed 的 .xterm 盖住、坐标命中终端，
    // 也不能被选区控制器接管（preventDefault 会取消 click 合成）。
    const hostUi = target?.closest?.(
      'button, a, input, textarea, select, [role="button"], [role="link"],' +
        '.permission-card, .pending-form-card, .sheet-root, .center-dialog-root'
    ) != null
    // 点终端以外的地方：顺手收起选区。盾层已改成 pointer-events:none，
    // 这里不再有全屏透明层挡住 #root。
    if (this.capturePointer && this.range != null && !inTermUi && !hostUi) this.clear()
    // 终端区域内的触摸一律视为「可能开始选区」：
    //  - preventDefault 阻止 pointerdown 的默认焦点迁移。否则 WebView 会把焦点
    //    从 textarea.xterm-helper-textarea 挪走：键盘开着时直接收起，键盘关着时
    //    xterm/WebView 又会自己 focus 并弹出输入法 —— 两种都会改变长按前的 IME 状态。
    //  - 同时置 __zlSelectGesture 给原生 focusin 钩子：手势期间任何主动 focus
    //    （xterm 的点按处理）都不许调 showIme。普通点按的 focus 由 pointerup
    //    末尾的 focusTerminal() 主动完成，不依赖默认行为。
    // 鼠标不拦：桌面/外接鼠标的文本选择、xterm 鼠标上报仍按原样走。
    if (inTermUi && !hostUi && this.inScreen(event.clientX, event.clientY)) {
      if (event.pointerType !== 'mouse') event.preventDefault()
      ;(window as any).__zlSelectGesture = true
      if (this.capturePointer) event.stopPropagation()
    }

    // 复制/粘贴按钮自己处理 click：pointerdown 阶段别把按钮和选区一起拆掉。
    const action = target?.closest?.('.zl-sel-copy, .zl-sel-paste') as HTMLElement | null
    if (action) return

    // 先记下落点是否在屏幕内，抬手时据此决定要不要把焦点交回终端。
    // 这里只记录、不 preventDefault：pointerdown 的默认行为一旦被取消，
    // 浏览器就不把它算作用户手势，随后 focus() 唤不起输入法。
    this.downInScreen = this.inScreen(event.clientX, event.clientY)
    this.justSelected = false
    this.moved = false

    // 圆点被 .term-host 底部裁掉一部分时，elementFromPoint 会落到 shield 甚至
    // 返回 null（命中不到 .zl-sel-handle）。所以先做「距离命中」：按下点离圆点
    // 中心 ≤ 26px 就直接开拖，不依赖 target.dataset。
    if (this.handles) {
      const near = this.dotNear(event.clientX, event.clientY)
      if (near) {
        event.preventDefault()
        event.stopPropagation()
        // 两圆点重合时把「抓哪一端」推迟到第一次位移（见 moveDrag）。
        this.beginDrag(near, event, this.dotsCoincide())
        return
      }

      const which = target?.dataset?.['which'] as 'start' | 'end' | undefined
      if (which) {
        event.preventDefault()
        event.stopPropagation()
        this.beginDrag(which, event, this.dotsCoincide())
        return
      }
    }

    // 任意非手柄/非按钮位置按下：已有选区就先收起，且**不** preventDefault、
    // 不 stopPropagation、不 return —— 这一击要同时把焦点/光标交给终端，
    // 不能像以前那样在 suppressUntil 里被吞掉。
    // 本次手势已接管（ownedDown）时例外：长按选定后遮挡层浮到最上层，
    // 同一手势若再落到这里，收起就等于把刚画好的选区抹掉。
    if (this.range != null && !this.ownedDown) this.clear()

    // document 捕获会收到所有会话的手势；只处理自己这一屏。
    if (this.host.owns != null) {
      if (!this.host.owns(target)) return
    } else {
      const owner = this.host.layer.closest('.term-host') as HTMLElement | null
      if (!owner || (target && !owner.contains(target))) return
      if (owner.offsetParent === null) return
    }

    // 从这里开始本次手势归本控制器：之后冒泡到 shield 或重复派发到这里的
    // pointerdown 看到标记就直接让路，不得再 clear()/hide()（抬手时复位）。
    this.ownedDown = true

    if (Date.now() < this.suppressUntil) {
      // 选区刚建好后的这一击先收起选区，但不能直接 return：直接返回会让
      // 紧接着的第二次长按也拿不到 460ms 计时器，表现就是“只有第一次能长按”。
      // 这里只挡住冒泡，随后继续走正常的落点判断与长按计时。
      event.stopPropagation()
      this.suppressUntil = 0
    }

    this.startX = event.clientX
    this.startY = event.clientY
    this.clearTimers()
    this.timer = window.setTimeout(() => {
      this.timer = null
      // 长按本身就是「选择」手势：即使落点没有词（空白/分隔符），抬手时也不能
      // 把焦点交回 textarea —— 否则 focusTerminal()->showIme() 会把键盘顶出来，
      // 改变长按前的输入法状态。
      ;(window as any).__zlLongPress = true
      this.justSelected = true
      this.selectWordAt(this.startX, this.startY)
    }, LONG_PRESS_MS)
  }

  private onPointerMove = (event: PointerEvent): void => {
    // 没有拖拽、也没在等长按：与本控制器无关。提前 return 省掉 inScreen()
    // 的 layout 读取——这条监听在 document 捕获阶段，聊天页滚动时也会被调。
    if (this.drag == null && this.timer == null) return
    if (this.capturePointer && this.inScreen(event.clientX, event.clientY)) event.stopPropagation()
    if (this.drag) {
      // preventDefault 由拖拽期间临时挂上的非 passive 监听负责（本监听是 passive）。
      event.stopPropagation()
      this.moveDrag(event)
      return
    }
    if (this.timer != null) {
      const dx = event.clientX - this.startX
      const dy = event.clientY - this.startY
      // 3px 就认为用户在滚动：比长按取消阈值更灵敏，避免「滑了一下又弹键盘」。
      if (!this.moved && Math.hypot(dx, dy) > 3) this.moved = true
      if (Math.hypot(dx, dy) > MOVE_TOLERANCE) {
        this.clearTimers()
      }
    }
  }

  private onPointerUp = (event: PointerEvent): void => {
    // 键栏上的抬手：既不是「点终端空白」，也不是选区手势。若这里放行，
    // downInScreen（pointerdown 时已置 true）会让 focusTerminal() 弹键盘。
    if (this.isKeybarEvent(event)) {
      this.stopDrag()
      this.ownedDown = false
      this.downInScreen = false
      this.justSelected = false
      this.clearTimers()
      ;(window as any).__zlSelectGesture = false
      if (this.range) this.render(this.range)
      return
    }
    if (this.capturePointer && this.inScreen(event.clientX, event.clientY)) event.stopPropagation()
    // 只要这次手势留下/操作了选区（长按选词、拖手柄、点盾层），就绝不碰焦点：
    // focusTerminal() 会 focus + showSoftInput，正是「扩选一下键盘弹出来」的来源。
    // 只有没选中任何东西的普通点按才把焦点交回终端。
    const focusable =
      this.downInScreen && !this.justSelected && this.range == null && !this.moved
    const selected = this.justSelected || this.range != null
    this.stopDrag()
    this.ownedDown = false
    this.downInScreen = false
    this.justSelected = false
    this.moved = false
    ;(window as any).__zlLongPress = false
    // 抬手后按「选区仍在」的常态重画一次：宿主可能在中途把 drag 摘掉，
    // 只要 range 还在就必须保证灰色选区可见，不能依赖 drag 是否有值。
    if (this.range) this.render(this.range)
    this.scheduleRestore()
    this.clearTimers()
    // 选区手势的守卫要继续挂一小会儿：WebView 可能在本轮事件结束后才补发
    // focusin（例如 xterm 的 touch 处理），原生钩子看到守卫就不会 showIme。
    // 普通点按则立即放行，让 focusTerminal 的 showIme 正常生效。
    if (selected) {
      window.setTimeout(() => {
        ;(window as any).__zlSelectGesture = false
      }, 350)
    } else {
      ;(window as any).__zlSelectGesture = false
    }
    // 焦点必须在真实用户手势里交回：pointerup 没被 preventDefault 时 WebView
    // 才允许 focus() 唤起输入法。只有「普通点按」走这一步；长按选词、拖手柄、
    // 点盾层都不碰焦点，保证 IME 的开/关状态与原样一致。
    if (focusable) this.focusTerminal()
  }

  private onPointerCancel = (): void => {
    this.stopDrag()
    this.ownedDown = false
    this.downInScreen = false
    this.justSelected = false
    this.moved = false
    ;(window as any).__zlLongPress = false
    ;(window as any).__zlSelectGesture = false
    // pointercancel 同理：range 还在就重画，绝不因手势被宿主打断而留下空层。
    // 没有 range 时改为整层复位，否则盾层会带着 pointer-events:auto 留在页面上。
    if (this.range) this.render(this.range)
    else this.clear()
    this.clearTimers()
  }

  /** 落点是否在终端屏幕矩形内。rect 做短 TTL 缓存，避免 move 热路径强制 layout。 */
  private inScreen(clientX: number, clientY: number): boolean {
    const now = Date.now()
    if (this.rectCache == null || now - this.rectAt > 120) {
      this.rectCache = this.host.screen.getBoundingClientRect()
      this.rectAt = now
    }
    const rect = this.rectCache
    return (
      rect.width > 0 &&
      rect.height > 0 &&
      clientX >= rect.left &&
      clientX <= rect.right &&
      clientY >= rect.top &&
      clientY <= rect.bottom
    )
  }

  /**
   * 事件是否落在底部快捷按键栏上。
   *
   * 键栏浮在终端屏幕底部，坐标同时落在 `.xterm-screen` 矩形内，
   * 单看 inScreen() 会把按键当成「点终端空白」，抬手后 focusTerminal()
   * 弹起软键盘。先按 target 判，再用落点元素与可见键栏矩形兜底
   * （选区遮挡层可能盖住键栏，此时 target 是 shield）。
   */
  private isKeybarEvent(event: Event): boolean {
    const target = event.target
    if (target instanceof Element) {
      // 圆点是顶置图层里的独立命中盒：即使它现在浮在键栏上方，也优先判圆点。
      // 否则坐标兜底会把「抓圆点」误判成「按按键」，圆点就拖不动了。
      if (target.closest('.zl-sel-handle') != null) return false
      if (target.closest('.zl-keybar') != null) return true
    }
    const pe = event as PointerEvent
    if (!this.isKeybarPoint(pe.clientX, pe.clientY)) return false
    // 坐标兜底同理：只有落在圆点中心附近之外才算按键，键栏按钮才不会被抢走。
    return this.dotNear(pe.clientX, pe.clientY, HANDLE_RADIUS + 8) == null
  }

  /** 坐标级兜底：命中的元素或任一可见键栏矩形覆盖该点。 */
  private isKeybarPoint(clientX: number, clientY: number): boolean {
    if (typeof clientX !== 'number' || typeof clientY !== 'number') return false
    const hit = document.elementFromPoint(clientX, clientY)
    if (hit != null && hit.closest?.('.zl-keybar') != null) return true
    const bars = document.querySelectorAll<HTMLElement>('.zl-keybar')
    for (const bar of bars) {
      const r = bar.getBoundingClientRect()
      if (r.width <= 0 || r.height <= 0) continue
      if (
        clientX >= r.left &&
        clientX <= r.right &&
        clientY >= r.top &&
        clientY <= r.bottom
      ) {
        return true
      }
    }
    return false
  }

  /**
   * 把焦点交回 xterm 的隐藏 textarea。
   *
   * xterm 的键盘输入与 IME 组字全部挂在 `textarea.xterm-helper-textarea` 上，
   * 只有它在**真实用户手势**里拿到焦点，WebView 才会弹软键盘。不要改动
   * textarea 自身的属性（readonly / tabindex 等由 xterm 管理）。
   * 只在本层 screen 所在的 `.xterm` 内查找，多终端共存时不会聚焦错实例。
   */
  private focusTerminal(): void {
    const root = this.host.screen.closest('.xterm') ?? this.host.screen
    const area = root.querySelector<HTMLTextAreaElement>('textarea.xterm-helper-textarea')
    if (area == null) return
    ensureTextInputAttrs(area)
    try {
      area.focus({ preventScroll: true })
    } catch {
      area.focus()
    }
    showNativeIme()
  }

  /**
   * pointerdown 被 capture 阶段 `stopPropagation()` 后，宿主的聚焦逻辑收不到
   * 事件；下一帧把焦点补回 textarea。只 focus，不调用 showIme：
   * 长按选词期间不该把软键盘顶起来（抬手的 [focusTerminal] 负责弹键盘）。
   */
  private deferFocus(target: Element | null): void {
    if (target?.closest?.('.zl-sel-copy, .zl-sel-paste')) return
    if (target?.closest?.('.zl-keybar')) return
    const root = this.host.screen.closest('.xterm') ?? this.host.screen
    const area = root.querySelector<HTMLTextAreaElement>('textarea.xterm-helper-textarea')
    if (area == null) return
    ensureTextInputAttrs(area)
    requestAnimationFrame(() => {
      if (document.activeElement === area) return
      try {
        area.focus({ preventScroll: true })
      } catch {
        area.focus()
      }
    })
  }

  private clearTimers(): void {
    if (this.timer != null) {
      window.clearTimeout(this.timer)
      this.timer = null
    }
    if (this.restoreTimer != null) {
      window.clearTimeout(this.restoreTimer)
      this.restoreTimer = null
    }
    if (this.repositionTimer != null) {
      window.clearTimeout(this.repositionTimer)
      this.repositionTimer = null
    }
  }

  // ---------------------------------------------------------------- geometry

  /**
   * 单元格尺寸与屏幕在 layer 内的偏移。
   *
   * 两个坑：
   * 1. 不能用「screen 宽度 / cols」估 cell 宽，xterm 自带字符测量元素
   *    `.xterm-char-measure-element`（内容是一串 W），用它算才准；
   * 2. `.xterm-screen` 相对 layer 有 padding 偏移（实测左上各 8px），
   *    handle/caret 都定位在 layer 里，必须把偏移加回去。
   */
  private metrics(): { w: number; h: number; left: number; top: number } {
    const layerRect = this.host.layer.getBoundingClientRect()
    const rect = this.host.screen.getBoundingClientRect()
    const root = this.host.screen.closest('.xterm') ?? this.host.screen
    const measure = root.querySelector('.xterm-char-measure-element') as HTMLElement | null
    let w = 0
    if (measure) {
      const mr = measure.getBoundingClientRect()
      const len = (measure.textContent ?? '').length
      if (mr.width > 0 && len > 0) w = mr.width / len
    }
    if (!(w > 0)) w = rect.width / Math.max(1, this.term.cols)
    const h = rect.height / Math.max(1, this.term.rows)
    return { w, h, left: rect.left - layerRect.left, top: rect.top - layerRect.top }
  }

  /**
   * 终端可视区在 layer 坐标系里的边界。
   *
   * 软键盘弹出时宿主会把 WebView 压矮，但 visualViewport 还可能单独滚动/缩小；
   * 两者取交集，菜单与圆点都按这个范围钳制，才不会被输入法或底边盖住。
   */
  private viewBounds(): { left: number; top: number; right: number; bottom: number } {
    const lr = this.host.layer.getBoundingClientRect()
    const vv = window.visualViewport
    const vw = vv?.width ?? window.innerWidth
    const vh = vv?.height ?? window.innerHeight
    const vLeft = vv?.offsetLeft ?? 0
    const vTop = vv?.offsetTop ?? 0
    const left = Math.max(0, vLeft - lr.left)
    const top = Math.max(0, vTop - lr.top)
    const right = Math.min(lr.width, vLeft + vw - lr.left)
    const bottom = Math.min(lr.height, vTop + vh - lr.top)
    return {
      left,
      top,
      right: Math.max(left, right),
      bottom: Math.max(top, bottom),
    }
  }

  private cellAt(clientX: number, clientY: number): { row: number; col: number } | null {
    const rect = this.host.screen.getBoundingClientRect()
    if (rect.width <= 0 || rect.height <= 0) return null
    const { w, h } = this.metrics()
    const col = Math.floor((clientX - rect.left) / w)
    const row = Math.floor((clientY - rect.top) / h)
    if (row < 0 || row >= this.term.rows) return null
    return {
      row,
      col: Math.max(0, Math.min(this.term.cols - 1, col))
    }
  }

  /**
   * cellAt 的「夹紧」版本：圆点手柄贴在行边界 / 图层边缘时，y 会落到行网格
   * 之外（row < 0 或 row >= rows），cellAt 返回 null 会让拖动事件被整段丢弃。
   * 这里把 row 夹进 [0, rows-1]、col 夹进 [0, cols]（cols 表示行尾位置）。
   */
  private cellAtClamped(clientX: number, clientY: number): { row: number; col: number } {
    const rect = this.host.screen.getBoundingClientRect()
    const { w, h } = this.metrics()
    if (!(w > 0) || !(h > 0)) return { row: 0, col: 0 }
    const rawCol = Math.floor((clientX - rect.left) / w)
    const rawRow = Math.floor((clientY - rect.top) / h)
    return {
      row: Math.max(0, Math.min(this.term.rows - 1, rawRow)),
      col: Math.max(0, Math.min(this.term.cols, rawCol))
    }
  }

  private lineText(viewportRow: number): string {
    const buffer = this.term.buffer.active
    const abs = buffer.viewportY + viewportRow
    const line = buffer.getLine(abs)
    return line ? line.translateToString(true) : ''
  }

  // ------------------------------------------------------------------ select

  /** 把某一行回溯到逻辑行首/行尾，拼成一行文本并算出目标列在其中的下标。 */
  private logicalLineAt(row: number, col: number): {
    text: string
    firstRow: number
    lastRow: number
    index: number
  } | null {
    if (row < 0 || row >= this.term.rows) return null
    const buffer = this.term.buffer.active
    let firstRow = row
    while (firstRow > 0) {
      const line = buffer.getLine(buffer.viewportY + firstRow)
      if (!line || !line.isWrapped) break
      firstRow--
    }
    let lastRow = row
    while (lastRow < this.term.rows - 1) {
      const next = buffer.getLine(buffer.viewportY + lastRow + 1)
      if (!next || !next.isWrapped) break
      lastRow++
    }

    let text = ''
    let offset = 0
    for (let r = firstRow; r <= lastRow; r++) {
      const line = this.lineText(r)
      if (r < row) offset += line.length
      text += line
    }
    return { text, firstRow, lastRow, index: offset + col }
  }

  /** 命中的 cell 落在哪个词上（跨 wrapped 行），返回该词的首尾 cell 与词文本。 */
  private wordAtCell(row: number, col: number): { start: Cell; end: Cell; text: string } | null {
    const logical = this.logicalLineAt(row, col)
    if (!logical) return null
    const [from, to] = expandSelection(logical.text, logical.index)
    const start = this.toCell(logical.text, logical.firstRow, logical.lastRow, from)
    const end = this.toCell(logical.text, logical.firstRow, logical.lastRow, to)
    if (!start || !end) return null
    return { start, end, text: logical.text.slice(from, to) }
  }

  private selectWordAt(clientX: number, clientY: number): void {
    const hit = this.cellAt(clientX, clientY)
    if (!hit) return
    const word = this.wordAtCell(hit.row, hit.col)
    if (!word) return

    this.applyRange({
      startRow: word.start.row,
      startCol: word.start.col,
      endRow: word.end.row,
      endCol: word.end.col,
    })
    // 这一手是「长按选词」而不是「点一下想输入」：抬手时不要再抢焦点，
    // 否则软键盘会直接盖在刚选出来的内容上。
    this.justSelected = true
    this.vibrate(20)

    // 长按不松手继续拖 = 直接进入扩选，不需要抬手再抓圆点。
    // 空白/分隔符上不武装拖动，避免拖出一段看不见的空选区。
    if (word.text.trim().length === 0) return
    this.drag = {
      which: 'end',
      anchor: { row: word.start.row, col: word.start.col },
      mode: 'press',
      originX: clientX,
      originY: clientY,
      armed: false,
      baseEnd: { row: word.end.row, col: word.end.col },
    }
  }

  private toCell(
    logical: string,
    firstRow: number,
    lastRow: number,
    flatIndex: number
  ): { row: number; col: number } | null {
    let acc = 0
    for (let row = firstRow; row <= lastRow; row++) {
      const text = this.lineText(row)
      if (flatIndex <= acc + text.length) {
        return { row, col: Math.max(0, flatIndex - acc) }
      }
      acc += text.length
    }
    const tail = this.lineText(lastRow)
    return { row: lastRow, col: tail.length }
  }

  /** 按下点是否落在某个圆点的命中半径内（client 坐标 vs layer 坐标）。 */
  private dotNear(
    clientX: number,
    clientY: number,
    radius: number = DOT_HIT_RADIUS
  ): 'start' | 'end' | null {
    if (!this.handles) return null
    const dots = this.dotPos
    if (!dots || !this.range) return null
    const rect = this.host.layer.getBoundingClientRect()
    const dist = (p: { x: number; y: number }): number =>
      Math.hypot(clientX - (rect.left + p.x), clientY - (rect.top + p.y))
    const ds = dist(dots.start)
    const de = dist(dots.end)
    if (Math.min(ds, de) > radius) return null
    return ds <= de ? 'start' : 'end'
  }

  /**
   * 两个圆点是否已经重叠到分不出彼此（零宽选区或同一格）。
   *
   * 26px 的圆点在同一位置时完全盖住对方，仅靠距离取近的会永远选中
   * start，end 就再也拖不出来 —— 这种时候改由拖动方向决定抓哪一端。
   */
  private dotsCoincide(): boolean {
    const dots = this.dotPos
    if (!dots) return false
    return Math.hypot(dots.start.x - dots.end.x, dots.start.y - dots.end.y) <= HANDLE_RADIUS
  }

  private beginDrag(which: 'start' | 'end', event: PointerEvent, pair = false): void {
    if (!this.range) return
    // 抓圆点也是本控制器的手势：同样置标记，避免中途被 shield 的 handler 清掉。
    this.ownedDown = true
    const anchor = which === 'start'
      ? { row: this.range.endRow, col: this.range.endCol }
      : { row: this.range.startRow, col: this.range.startCol }
    const moving = which === 'start'
      ? { row: this.range.startRow, col: this.range.startCol }
      : { row: this.range.endRow, col: this.range.endCol }
    this.drag = {
      which,
      anchor,
      mode: 'handle',
      pair,
      originX: event.clientX,
      originY: event.clientY,
      armed: true,
      baseRow: moving.row,
      baseCol: moving.col,
    }
    ;(event.target as HTMLElement | null)?.setPointerCapture?.(event.pointerId)
    // 拖拽期间才挂非 passive：阻止页面跟随手指滚动（平时 move 是 passive）。
    if (this.dragBlock == null) {
      this.dragBlock = (ev: PointerEvent): void => {
        if (this.drag != null) ev.preventDefault()
      }
      document.addEventListener('pointermove', this.dragBlock, { capture: true, passive: false })
    }
    // 抓到手柄立刻重画一次：拖动中要把选区边界与圆点位置同步。
    this.scheduleRender()
  }

  /** 结束拖拽：清状态并摘掉临时挂上的非 passive 监听。 */
  private stopDrag(): void {
    this.drag = null
    if (this.dragBlock != null) {
      document.removeEventListener('pointermove', this.dragBlock, { capture: true })
      this.dragBlock = null
    }
  }

  /** 选区重画合并到一帧：拖动手势一次 move 只画一次。 */
  private scheduleRender(): void {
    if (this.renderRaf != null) return
    this.renderRaf = window.requestAnimationFrame(() => {
      this.renderRaf = null
      if (this.range != null) this.render(this.range)
    })
  }

  private moveDrag(event: PointerEvent): void {
    const drag = this.drag
    if (!drag) return

    if (drag.mode === 'press') {
      // 长按拖动仍按原语义吸附词边界，命中不到网格就丢弃事件。
      const hit = this.cellAt(event.clientX, event.clientY)
      if (!hit) return
      // 长按已选中：先吃掉 8px 抖动，之后按词边界吸附扩选。
      if (!drag.armed) {
        const moved = Math.hypot(event.clientX - drag.originX, event.clientY - drag.originY)
        if (moved < PRESS_DRAG_THRESHOLD) return
        drag.armed = true
      }
      const a = drag.anchor
      const baseEnd = drag.baseEnd ?? a
      const before = hit.row < a.row || (hit.row === a.row && hit.col < a.col)
      const word = this.wordAtCell(hit.row, hit.col)
      const solid = word != null && word.text.trim().length > 0
      if (before) {
        const start = solid && word ? word.start : hit
        this.applyRange({
          startRow: start.row,
          startCol: start.col,
          endRow: baseEnd.row,
          endCol: baseEnd.col,
        })
      } else {
        const end = solid && word ? word.end : hit
        this.applyRange({
          startRow: a.row,
          startCol: a.col,
          endRow: end.row,
          endCol: end.col,
        })
      }
      return
    }

    // 抓圆点：圆点贴在行边界或图层边缘时 y 可能落在行网格之外，
    // 用 clamp 后的命中，保证拖动全程不丢事件。
    // 越过最后一行底边（圆点本身被钳在底边上方 15px，纯向下拖十几像素仍停在
    // 最后一行同一列）时视为「选到行尾」，否则底行向下拖完全没有反馈。
    const { w, h } = this.metrics()
    if (!(w > 0) || !(h > 0)) return
    // 圆点重叠时按下分不出端点：等第一次有效位移再定。
    // 往下 / 往右拖扩终点，往上 / 往左拖扩起点，两个方向都拖得出来。
    if (drag.pair) {
      const dx = event.clientX - drag.originX
      const dy = event.clientY - drag.originY
      if (Math.hypot(dx, dy) < 6) return
      const range = this.range
      if (!range) return
      const useEnd = dy > 0 || (dy === 0 && dx >= 0)
      drag.pair = false
      drag.which = useEnd ? 'end' : 'start'
      drag.anchor = useEnd
        ? { row: range.startRow, col: range.startCol }
        : { row: range.endRow, col: range.endCol }
      drag.baseRow = useEnd ? range.endRow : range.startRow
      drag.baseCol = useEnd ? range.endCol : range.startCol
    }
    const baseRow = drag.baseRow ?? drag.anchor.row
    const baseCol = drag.baseCol ?? drag.anchor.col
    // 四舍五入：h≈17px 时向下拖 12px 也能推进一行（用户实测的有效位移）。
    const rawRow = baseRow + Math.round((event.clientY - drag.originY) / h)
    const rawCol = baseCol + Math.round((event.clientX - drag.originX) / w)
    const hit = rawRow > this.term.rows - 1
      ? // 越过最后一行底边：视为「选到行尾」，否则底行向下拖完全没有反馈。
        { row: this.term.rows - 1, col: this.term.cols }
      : {
          row: Math.max(0, Math.min(this.term.rows - 1, rawRow)),
          col: Math.max(0, Math.min(this.term.cols, rawCol)),
        }
    // 抓圆点：保持按 cell 逐格扩选，方便精细调整。
    // 必须按「抓的是哪一端」决定移动哪一个端点：起点圆点永远只动 start、
    // 终点圆点永远只动 end，越过另一端时钳在另一端（不交换身份）。
    // 旧实现按文档位置重新排序，一越过另一端两个圆点就互换，手感上就是
    // 「拖起点动的却是终点」。
    const a = drag.anchor
    if (drag.which === 'start') {
      const before = hit.row < a.row || (hit.row === a.row && hit.col <= a.col)
      const s = before ? hit : a
      this.applyRange({ startRow: s.row, startCol: s.col, endRow: a.row, endCol: a.col })
    } else {
      const after = hit.row > a.row || (hit.row === a.row && hit.col >= a.col)
      const e = after ? hit : a
      this.applyRange({ startRow: a.row, startCol: a.col, endRow: e.row, endCol: e.col })
    }
  }

  private applyRange(range: SelectionRange): void {
    this.menuHidden = false
    this.range = range
    this.lastCols = this.term.cols
    this.lastRows = this.term.rows
    // 选区一变就快照锚定文本（必须在 render 之前，render 只画不记）。
    this.snapshotAnchor(range)
    // 拖动时一次 move 只画一帧：直接 render 会在 120Hz 下反复重建选区 DOM。
    this.scheduleRender()
    this.suppressUntil = Date.now() + SUPPRESS_MS
    void this.term
  }

  /**
   * xterm 有时会在 touchend 后清掉自己的选中态，抬手后补一次重贴。
   * 只挂在抬手/落定，不再每次 move 都排一个 40ms 定时器。
   */
  private scheduleRestore(): void {
    if (this.restoreTimer != null) window.clearTimeout(this.restoreTimer)
    this.restoreTimer = window.setTimeout(() => {
      this.restoreTimer = null
      if (this.range) this.render(this.range)
    }, RESTORE_DELAY_MS)
  }

  // ---------------------------------------------------------------- reanchor

  /**
   * 记录「选中了哪段文字」以及它起始行的绝对行号。
   *
   * 复制之后、输入法弹出之前调用一次；只要这段文字还在 buffer 里，
   * reanchor() 就能在 reflow 后把它重新定位出来。
   */
  private snapshotAnchor(range: SelectionRange): void {
    const text = this.selectedText()
    if (!text) {
      this.anchorText = null
      this.anchorStartRow = 0
      return
    }
    this.anchorText = text
    this.anchorStartRow = this.absoluteRow(range.startRow)
  }

  /**
   * 按锚定文本在新 buffer 里重新定位选区。
   *
   * 步骤：从 `anchorStartRow - 3` 起逐行 `translateToString(true)` 拼成一个
   * 长串（同时记下每行在长串里的起始码位偏移），在其中 `indexOf(anchorText)`
   * —— 选中文本里的换行在拼接时不存在，所以先去掉 `\n` 再找。命中后把
   * 起止码位偏移映射回 (行, 列)：行 = 绝对行号 - viewportY，列按显示宽度累加
   * （中文/emoji 占两列）。找不到就隐藏选区，绝不留下错位的高亮。
   *
   * 全程只读 buffer + render，不改尺寸、不派发 resize —— 不会触发循环。
   */
  private reanchor(): void {
    const anchor = this.anchorText
    const current = this.range
    if (anchor == null || current == null) return
    const buffer = this.term.buffer.active
    const needle = anchor.replace(/\n/g, '')
    if (needle.length === 0) {
      this.clear()
      return
    }

    const from = Math.max(0, this.anchorStartRow - 3)
    const offsets: number[] = []
    const lines: string[] = []
    let joined = ''
    let abs = from
    // 最多扫 1000 行：足够覆盖任何一段可复制的选区，也挡住超长 buffer 退化。
    for (let guard = 0; guard < 1000; guard++) {
      const line = buffer.getLine(abs)
      if (!line) break
      const text = line.translateToString(true)
      offsets.push(joined.length)
      lines.push(text)
      joined += text
      abs++
      // 已经攒够比锚定文本还长的一大截就不再往下读（后面不可能更早命中）。
      if (joined.length >= needle.length + 240 && abs > this.anchorStartRow) break
    }

    const hit = joined.indexOf(needle)
    if (hit < 0) {
      // 重排后已经找不到同一段文字（内容被覆盖/滚出缓冲区）：隐藏，杜绝错位。
      this.clear()
      return
    }

    /** 码位偏移 → { 绝对行号, 列 }；取最后一个 start <= offset 的行。 */
    const cellAtOffset = (offset: number): { row: number; col: number } | null => {
      for (let k = offsets.length - 1; k >= 0; k--) {
        if (offset >= offsets[k]) {
          const lineText = lines[k] ?? ''
          return { row: from + k, col: colAtUnit(lineText, offset - offsets[k]) }
        }
      }
      return null
    }

    const start = cellAtOffset(hit)
    const end = cellAtOffset(hit + needle.length)
    if (!start || !end) {
      this.clear()
      return
    }

    const viewportY = buffer.viewportY
    const startRow = start.row - viewportY
    const endRow = end.row - viewportY
    if (
      startRow < 0 ||
      startRow >= this.term.rows ||
      endRow < 0 ||
      endRow >= this.term.rows ||
      endRow < startRow ||
      (endRow === startRow && end.col <= start.col)
    ) {
      // 选区整体滚出了可视区：没有可画的位置，直接隐藏。
      this.clear()
      return
    }

    const next: SelectionRange = {
      startRow,
      startCol: Math.min(start.col, this.term.cols),
      endRow,
      endCol: Math.min(end.col, this.term.cols),
    }
    this.range = next
    // 更新起始绝对行号，连续多次 reflow（键盘反复弹收）也能逐次收敛。
    this.anchorStartRow = start.row
    this.render(next)
  }

  // ------------------------------------------------------------------ render

  private render(range: SelectionRange): void {
    const { w, h, left: offsetX, top: offsetY } = this.metrics()
    const orb = this.orb
    if (!orb) return
    orb.style.display = 'block'
    orb.textContent = ''

    const rows = range.endRow - range.startRow + 1
    for (let i = 0; i < rows; i++) {
      const row = range.startRow + i
      const from = i === 0 ? range.startCol : 0
      const to = row === range.endRow ? range.endCol : this.term.cols
      if (to <= from) continue
      const rect = document.createElement('div')
      rect.className = 'zl-sel-rect'
      rect.style.left = `${offsetX + from * w}px`
      rect.style.top = `${offsetY + row * h}px`
      rect.style.width = `${Math.max(1, (to - from) * w)}px`
      rect.style.height = `${h}px`
      orb.appendChild(rect)
    }

    // 关掉手柄时不写 dot 样式、也不维护 dotPos：
    // 复制/粘贴按钮只用 range 与 metrics 定位，不依赖圆点坐标。
    if (this.handles) {
      this.position(range.startRow, range.startCol, this.dotStart, true)
      this.position(range.endRow, range.endCol, this.dotEnd, false)
    }

    if (this.shield) {
      // 盾层只作为视觉层：pointer-events 永远 none。
      // 之前这里按 range 打开 pointer-events:auto，只要有一次选区没被 clear，
      // 整块 fixed inset:0 的 9997 层就会盖住 #root，提问卡/权限卡按钮全部点不动。
      // 「点外面收起选区」改由 document 捕获阶段的 onPointerDown 负责。
      this.shield.style.display = this.range ? 'block' : 'none'
      this.shield.style.pointerEvents = 'none'
    }
    if (this.copyBtn) {
      // 菜单始终落在终端可视区内：软键盘弹出时 WebView 会被宿主压矮，
      // visualViewport 也会缩小；取交集后上下都钳制，按钮不会被输入法盖住。
      const view = this.viewBounds()
      const pad = 8
      const btnH = this.copyBtn.offsetHeight || 32
      const endY = offsetY + range.endRow * h + h
      const startY = offsetY + range.startRow * h
      // 让开圆点外沿（圆心偏移 + 视觉半径）再留 10px，菜单不会压住手柄。
      const clearance = HANDLE_GAP + HANDLE_RADIUS + 10
      const below = endY + clearance
      const above = startY - btnH - clearance
      let top = below + btnH <= view.bottom - pad ? below : above
      top = Math.max(view.top + pad, Math.min(view.bottom - btnH - pad, top))
      const btnW = this.copyBtn.offsetWidth || 56
      const gap = 6
      const groupW = this.pasteBtn ? btnW * 2 + gap : btnW
      const left = Math.max(
        view.left + pad,
        Math.min(view.right - groupW - pad, offsetX + range.endCol * w - 24)
      )
      this.copyBtn.style.display = this.menuHidden ? 'none' : 'block'
      this.copyBtn.style.top = `${top}px`
      this.copyBtn.style.left = `${left}px`
      if (this.pasteBtn) {
        this.pasteBtn.style.display = this.menuHidden ? 'none' : 'block'
        this.pasteBtn.style.top = `${top}px`
        this.pasteBtn.style.left = `${left + btnW + gap}px`
      }
    }
  }

  private position(
    row: number,
    col: number,
    dot: HTMLElement | null,
    isStart: boolean
  ): void {
    const { w, h, left: offsetX, top: offsetY } = this.metrics()
    const x = offsetX + col * w
    const lineTop = offsetY + row * h

    // 圆点允许越界：不再夹在文字块（screen/可视区）里，首行可以画到文字上方、
    // 末行可以画到文字下方，只要整颗圆点还落在终端页（layer）内即可。
    // layer 就是 WebView 区域，键盘弹出时宿主会把 WebView 压矮，因此这里
    // 天然不会跑到输入法下面；上下各留 HANDLE_RADIUS + 2 保证整颗圆点可见。
    const layerH = this.host.layer.clientHeight
    const screenBottom = offsetY + this.term.rows * h
    const bodyH = layerH > 0 ? layerH : screenBottom
    const maxY = bodyH - HANDLE_RADIUS - 2
    const minY = HANDLE_RADIUS + 2

    // start 圆点优先放行下方，放不下改上方；end 圆点严格落在字符下方，
    // 只做钳制，绝不回退到行上方。两者最后都夹进 [minY, maxY]。
    let dotY: number
    if (isStart) {
      const below = lineTop + h + HANDLE_GAP
      const above = lineTop - HANDLE_GAP
      dotY = below > maxY ? above : below
    } else {
      dotY = Math.min(lineTop + h + HANDLE_GAP, maxY)
    }
    dotY = Math.max(minY, Math.min(maxY, dotY))

    if (!this.dotPos) {
      this.dotPos = { start: { x, y: dotY }, end: { x, y: dotY } }
    }
    if (isStart) this.dotPos.start = { x, y: dotY }
    else this.dotPos.end = { x, y: dotY }

    if (dot) {
      dot.style.display = 'block'
      dot.style.left = `${x}px`
      dot.style.top = `${dotY}px`
    }
  }

  private hide(): void {
    for (const el of [this.orb, this.dotStart, this.dotEnd, this.copyBtn, this.pasteBtn]) {
      if (el) el.style.display = 'none'
    }
    if (this.shield) {
      this.shield.style.display = 'none'
      this.shield.style.pointerEvents = 'none'
    }
  }

  clear(): void {
    const hadSelection = this.range != null || this.drag != null
    this.range = null
    this.stopDrag()
    this.anchorText = null
    this.anchorStartRow = 0
    this.cancelReanchor()
    // 清除后立即解除「吞掉下一击」的保护：点选区外那一击必须能落到终端上。
    this.suppressUntil = 0
    this.clearTimers()
    this.hide()
    this.term.clearSelection()
    if (hadSelection) this.notifySelectionEnd()
  }

  /** 选区存在或正在拖手柄/长按扩选 —— main.ts 用它让出滚动与横滑手势。 */
  isSelecting(): boolean {
    return this.drag != null || this.range != null || this.timer != null
  }

  /** 通知宿主：选区已结束，可以补做被冻结的 fit。 */
  private notifySelectionEnd(): void {
    try {
      window.dispatchEvent(new CustomEvent('zl:selection-end'))
    } catch {
      /* 事件派发失败不影响选区清理 */
    }
  }

  /** 当前选区快照（调试/测试钩子用）。 */
  currentRange(): SelectionRange | null {
    return this.range ? { ...this.range } : null
  }

  selectedText(): string {
    const range = this.range
    if (!range) return ''
    const parts: string[] = []
    for (let row = range.startRow; row <= range.endRow; row++) {
      const text = this.lineText(row)
      const from = row === range.startRow ? range.startCol : 0
      const to = row === range.endRow ? range.endCol : text.length
      parts.push(text.slice(from, Math.max(from, to)).replace(/\s+$/, ''))
    }
    return parts.join('\n').replace(/\n+$/, '')
  }
}

/**
 * 唤起原生软键盘。
 *
 * 原生终端注入的桥名是 `zlPty`（PtyWebBridge）；历史/兼容名一并尝试。
 * 存在哪个调哪个，任何异常静默，绝不影响焦点交接本身。
 */
const IME_BRIDGES = ['zlPty', 'PtyWebBridge'] as const

/**
 * 软键盘输入法需要的基础属性。
 *
 * xterm 自己只设 `autocapitalize` / `spellcheck`，`inputmode` 与 `autocomplete`
 * 留空时 WebView 会对英文输入做首字母大写与联想，搜狗等输入法也可能因此走
 * 非组词通道。这里只补这四个属性，`readonly` / `tabindex` 仍由 xterm 管理，
 * 绝不改动（把 textarea 设成 readonly 会彻底屏蔽软键盘输入）。
 */
export function ensureTextInputAttrs(area: HTMLTextAreaElement): void {
  if (area.getAttribute('inputmode') !== 'text') area.setAttribute('inputmode', 'text')
  if (area.getAttribute('autocomplete') !== 'off') area.setAttribute('autocomplete', 'off')
  if (area.getAttribute('autocapitalize') !== 'off') area.setAttribute('autocapitalize', 'off')
  if (area.getAttribute('spellcheck') !== 'false') area.setAttribute('spellcheck', 'false')
}

function showNativeIme(): void {
  const win = window as unknown as Record<string, { showIme?: () => void } | undefined>
  for (const name of IME_BRIDGES) {
    try {
      win[name]?.showIme?.()
    } catch {
      /* WebView 没注入或版本不支持：忽略 */
    }
  }
}
