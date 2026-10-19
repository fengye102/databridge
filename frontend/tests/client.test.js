import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request, taskApi } from '../src/api/client.js'

afterEach(() => vi.unstubAllGlobals())

describe('local API client', () => {
  it('uses relative localhost proxy paths and returns JSON', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: async () => [{ taskId: 'id-1', status: 'STREAMING' }] })
    vi.stubGlobal('fetch', fetch)
    expect(await taskApi.list()).toEqual([{ taskId: 'id-1', status: 'STREAMING' }])
    expect(fetch).toHaveBeenCalledWith('/api/tasks', expect.objectContaining({
      headers: expect.objectContaining({ Accept: 'application/json' }),
    }))
  })

  it('does not echo server exception text or credentials', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409,
      json: async () => ({ code: 'INVALID_STATE', message: 'password=leaked' }) }))
    await expect(request('/api/tasks/id/start', { method: 'POST' })).rejects.toMatchObject({
      code: 'INVALID_STATE', status: 409, message: '当前任务状态不允许此操作。',
    })
  })

  it('turns a network failure into a fixed safe message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('jdbc:mysql://secret:3306')))
    await expect(taskApi.list()).rejects.toEqual(
      new ApiError('NETWORK_ERROR', 0, '本机后端未连接，请检查服务是否启动。'))
  })
})
