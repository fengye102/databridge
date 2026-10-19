import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskForm from '../src/components/TaskForm.vue'
import TaskProgress from '../src/components/TaskProgress.vue'

const catalog = {
  source: { reachable: true, tables: [{ schema: 'databridge_source', table: 'orders', columns: [
    { name: 'id', type: 'BIGINT' }, { name: 'name', type: 'VARCHAR' },
  ] }] },
  target: { reachable: true, tables: [{ schema: 'business', table: 'orders', columns: [
    { name: 'id', type: 'int8' }, { name: 'name', type: 'varchar' },
  ] }] },
}

describe('task workspace', () => {
  it('creates a task using only preconfigured catalog tables', async () => {
    const wrapper = mount(TaskForm, { props: { catalog } })
    await wrapper.find('form').trigger('submit.prevent')
    expect(wrapper.emitted('create')?.[0]?.[0]).toEqual({
      sourceSchema: 'databridge_source', sourceTable: 'orders',
      targetSchema: 'business', targetTable: 'orders',
    })
    expect(wrapper.text()).not.toContain('密码')
  })

  it('shows durable copy ids rather than a false percent for sparse keys', () => {
    const wrapper = mount(TaskProgress, { props: { status: 'COPYING', progress: {
      highWatermark: 100, lastCopiedId: 50, copyComplete: false,
      capturedEvents: 17, appliedEvents: 0, pendingEvents: 17,
    } } })
    expect(wrapper.text()).toContain('已处理到 ID 50')
    expect(wrapper.text()).toContain('扫描上界 ID 100')
    expect(wrapper.findAll('.metric-strip > div')[3].text()).toContain('待应用')
    expect(wrapper.findAll('.metric-strip > div')[3].find('strong').text()).toBe('17')
    expect(wrapper.text()).not.toContain('50%')
  })
})
