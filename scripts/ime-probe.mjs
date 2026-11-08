// TEMP diagnostic: evaluate JS in the terminal WebView over CDP.
// usage: node scripts/ime-probe.mjs "<js expression>" [--await]
const port = process.env.CDP_PORT || '9222'
const expr = process.argv[2]
if (!expr) { console.error('expr required'); process.exit(2) }

const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json()
const page = targets.find(t => t.type === 'page') || targets[0]
if (!page) { console.error('no target'); process.exit(3) }

const ws = new WebSocket(page.webSocketDebuggerUrl)
let id = 0
const pending = new Map()
const send = (method, params = {}) => new Promise((res, rej) => {
  const mid = ++id
  pending.set(mid, { res, rej })
  ws.send(JSON.stringify({ id: mid, method, params }))
})
ws.onmessage = ev => {
  const msg = JSON.parse(ev.data)
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? p.rej(new Error(JSON.stringify(msg.error))) : p.res(msg.result)
  }
}
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = e => rej(new Error('ws error')) })
try {
  const r = await send('Runtime.evaluate', {
    expression: expr,
    returnByValue: true,
    awaitPromise: true,
    userGesture: true,
  })
  if (r.exceptionDetails) {
    console.error('EXCEPTION:', JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails))
    process.exitCode = 4
  } else {
    console.log(JSON.stringify(r.result.value, null, 2))
  }
} finally {
  ws.close()
}
