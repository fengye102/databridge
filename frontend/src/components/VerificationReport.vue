<script setup>
import { computed } from 'vue'

const props = defineProps({ report: { type: Object, required: true } })
const labels = {
  CONSISTENT: '数据一致',
  DIFFERENT: '存在差异',
  INCONCLUSIVE: '结论未定',
}
const reasons = {
  SOURCE_CHANGED: '扫描期间源端发生写入，请停写后重新校验。',
  BARRIER_TIMEOUT: 'CDC 探针等待超时，请确认任务仍在同步。',
  TASK_NOT_STREAMING: '任务已离开持续同步状态。',
  INTERRUPTED: '校验被中断。',
  SCAN_OR_BARRIER_FAILED: '扫描或探针失败，请检查本机后端日志。',
}
const status = computed(() => labels[props.report.status] || '未知结果')
const reason = computed(() => reasons[props.report.reasonCode] || '本次结果不可用于一致性结论。')
const differenceLabels = {
  MISSING_TARGET: '目标缺行',
  EXTRA_TARGET: '目标多行',
  VALUE_MISMATCH: '字段不同',
}
</script>

<template>
  <article class="report" :class="`report-${report.status?.toLowerCase()}`">
    <header class="report-header">
      <div>
        <h3>{{ status }}</h3>
        <p v-if="report.status === 'INCONCLUSIVE'">{{ reason }}</p>
        <p v-else>源端 {{ report.sourceRows }} 行 · 目标端 {{ report.targetRows }} 行 · 差异 {{ report.differenceRows }} 行</p>
      </div>
      <time v-if="report.completedAt" :datetime="report.completedAt">{{ new Date(report.completedAt).toLocaleString('zh-CN') }}</time>
    </header>
    <div v-if="report.differences?.length" class="report-differences">
      <table>
        <thead><tr><th scope="col">主键 ID</th><th scope="col">差异</th><th scope="col">列名</th></tr></thead>
        <tbody>
          <tr v-for="item in report.differences" :key="item.rowId">
            <td>{{ item.rowId }}</td>
            <td>{{ differenceLabels[item.kind] || item.kind }}</td>
            <td>{{ item.columns?.join('、') || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <p class="table-note">仅显示最多 100 条差异样本，不保存业务字段原值。</p>
    </div>
  </article>
</template>
