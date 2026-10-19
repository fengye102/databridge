import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

vi.mock('../src/api/client.js', () => ({ taskApi: {
  catalog: vi.fn(), list: vi.fn(), get: vi.fn(), progress: vi.fn(), reports: vi.fn(),
  create: vi.fn(), action: vi.fn(), verify: vi.fn(),
} }))

import { taskApi } from '../src/api/client.js'
import App from '../src/App.vue'

beforeEach(() => {
  taskApi.catalog.mockResolvedValue({
    source: { reachable: true, tables: [] }, target: { reachable: true, tables: [] },
  })
  taskApi.list.mockResolvedValue([{ taskId: 'id-1', status: 'STREAMING', table: {
    sourceSchema: 'databridge_source', sourceTable: 'orders',
    targetSchema: 'business', targetTable: 'orders',
  } }])
  taskApi.progress.mockResolvedValue({ copyComplete: true, highWatermark: 10,
    lastCopiedId: 10, capturedEvents: 3, appliedEvents: 3, pendingEvents: 0 })
  taskApi.reports.mockResolvedValue([{ runId: 'run-1', status: 'INCONCLUSIVE',
    reasonCode: 'SOURCE_CHANGED', differences: [] }])
})

describe('console workspace', () => {
  it('loads durable task, progress, and latest report without values or credentials', async () => {
    const wrapper = mount(App, { global: { stubs: { ElButton: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('databridge_source.orders')
    expect(wrapper.text()).toContain('business.orders')
    expect(wrapper.text()).toContain('持续同步')
    expect(wrapper.text()).toContain('结论未定')
    expect(wrapper.text()).not.toContain('password')
    wrapper.unmount()
  })
})
