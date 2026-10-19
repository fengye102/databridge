<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, required: true },
  progress: { type: Object, default: null },
})
const stages = [
  ['CAPTURING', '建立捕获'],
  ['COPYING', '全量复制'],
  ['REPLAYING', '事件回放'],
  ['STREAMING', '持续同步'],
]
const stageIndex = computed(() => stages.findIndex(([key]) => key === props.status))
const copyText = computed(() => {
  if (!props.progress || props.progress.copyComplete === null) return '尚未开始'
  if (props.progress.copyComplete) return '全量已完成'
  return props.progress.lastCopiedId === null ? '准备扫描' : `已处理到 ID ${props.progress.lastCopiedId}`
})
</script>

<template>
  <section class="progress-panel" aria-label="任务进度">
    <div class="section-heading">
      <div><h2>处理进度</h2><p>以下数字来自持久化检查点与事件游标</p></div>
    </div>
    <ol class="stage-track">
      <li v-for="([key, label], index) in stages" :key="key"
        :class="{ 'stage-current': stageIndex === index, 'stage-done': stageIndex > index }">
        <span class="stage-marker" aria-hidden="true"></span><span>{{ label }}</span>
      </li>
    </ol>
    <div class="metric-strip">
      <div><span class="metric-label">全量检查点</span><strong>{{ copyText }}</strong>
        <small v-if="progress?.highWatermark !== null && progress?.highWatermark !== undefined">扫描上界 ID {{ progress.highWatermark }}</small></div>
      <div><span class="metric-label">已捕获事件</span><strong>{{ progress?.capturedEvents ?? '—' }}</strong><small>含同步探针</small></div>
      <div><span class="metric-label">已应用事件</span><strong>{{ progress?.appliedEvents ?? '—' }}</strong><small>游标以内</small></div>
      <div><span class="metric-label">待应用</span><strong>{{ progress?.pendingEvents ?? '—' }}</strong><small>尚未追平的事件</small></div>
    </div>
  </section>
</template>
