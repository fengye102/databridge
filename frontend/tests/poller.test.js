import { afterEach, describe, expect, it, vi } from 'vitest'
import { createVisiblePoller } from '../src/api/visible-poller.js'

afterEach(() => vi.useRealTimers())

describe('visible poller', () => {
  it('pauses while hidden, refreshes on return, and removes timer on stop', () => {
    vi.useFakeTimers()
    let visibility = 'visible'
    const doc = new EventTarget()
    Object.defineProperty(doc, 'visibilityState', { get: () => visibility })
    const refresh = vi.fn()
    const poller = createVisiblePoller(refresh, doc, 3000)
    poller.start()
    expect(refresh).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(3000)
    expect(refresh).toHaveBeenCalledTimes(2)
    visibility = 'hidden'
    doc.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(9000)
    expect(refresh).toHaveBeenCalledTimes(2)
    visibility = 'visible'
    doc.dispatchEvent(new Event('visibilitychange'))
    expect(refresh).toHaveBeenCalledTimes(3)
    poller.stop()
    vi.advanceTimersByTime(6000)
    expect(refresh).toHaveBeenCalledTimes(3)
  })
})
