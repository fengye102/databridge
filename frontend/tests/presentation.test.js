import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskStatus from '../src/components/TaskStatus.vue'
import VerificationReport from '../src/components/VerificationReport.vue'

describe('operational status', () => {
  it('shows streaming and failed states with separate labels', () => {
    expect(mount(TaskStatus, { props: { status: 'STREAMING' } }).text()).toContain('持续同步')
    expect(mount(TaskStatus, { props: { status: 'FAILED' } }).text()).toContain('失败')
  })

  it('never treats inconclusive verification as consistency', () => {
    const wrapper = mount(VerificationReport, { props: { report: {
      runId: 'run-1', status: 'INCONCLUSIVE', reasonCode: 'SOURCE_CHANGED',
      sourceRows: null, targetRows: null, differenceRows: null, differences: [],
    } } })
    expect(wrapper.text()).toContain('结论未定')
    expect(wrapper.text()).toContain('扫描期间源端发生写入')
    expect(wrapper.text()).not.toContain('数据一致')
  })
})
