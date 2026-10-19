<script setup>
import { computed } from 'vue'

const props = defineProps({ status: { type: String, required: true } })
const states = {
  CREATED: ['待启动', 'neutral'],
  CAPTURING: ['建立捕获', 'active'],
  COPYING: ['全量复制', 'active'],
  REPLAYING: ['增量回放', 'active'],
  STREAMING: ['持续同步', 'success'],
  PAUSED: ['已暂停', 'warning'],
  STOPPED: ['已停止', 'neutral'],
  FAILED: ['失败', 'danger'],
}
const state = computed(() => states[props.status] || ['未知状态', 'warning'])
</script>

<template>
  <span class="status-badge" :class="`status-${state[1]}`">
    <span class="status-dot" aria-hidden="true"></span>{{ state[0] }}
  </span>
</template>
