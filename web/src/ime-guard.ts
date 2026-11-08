/**
 * Android IME guard —— 已停用（2026-11-02）。
 *
 * 曾经的做法：在 document 捕获阶段于 compositionstart 里清空 textarea.value
 * 并 setSelectionRange(0,0)，试图绕开 xterm 6 的既有偏移问题。
 *
 * 实测结论：安卓 WebView 上，组词开始时改写 textarea.value 会直接取消输入法的
 * 组词会话，表现为"中文完全打不出来"（英文不受影响）。因此整套 guard 关闭，
 * 输入交给 xterm 原生处理；重复上屏由 bridge.ts 的写入去重兜底。
 *
 * 保留同名导出，接线处（main.ts）无需改动。
 */
export function installImeGuard(
  _getTextarea: () => HTMLTextAreaElement | null
): () => void {
  return () => {}
}
