export function createVisiblePoller(refresh, doc = document, intervalMs = 3000) {
  let timer = null
  let started = false
  let busy = false

  const run = () => {
    if (busy) return
    busy = true
    try {
      const result = refresh()
      if (result && typeof result.then === 'function') {
        Promise.resolve(result).catch(() => {}).finally(() => { busy = false })
      } else {
        busy = false
      }
    } catch {
      busy = false
    }
  }
  const pause = () => {
    if (timer !== null) clearInterval(timer)
    timer = null
  }
  const onVisibility = () => {
    pause()
    if (doc.visibilityState === 'visible') {
      run()
      timer = setInterval(run, intervalMs)
    }
  }

  return {
    start() {
      if (started) return
      started = true
      doc.addEventListener('visibilitychange', onVisibility)
      onVisibility()
    },
    stop() {
      if (!started) return
      started = false
      pause()
      doc.removeEventListener('visibilitychange', onVisibility)
    },
  }
}
