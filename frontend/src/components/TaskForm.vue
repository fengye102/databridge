<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  catalog: { type: Object, default: null },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['create'])
const sourceTables = computed(() => props.catalog?.source?.tables || [])
const targetTables = computed(() => props.catalog?.target?.tables || [])
const sourceKey = ref('')
const targetKey = ref('')
const key = (table) => `${table.schema}.${table.table}`

watch([sourceTables, targetTables], () => {
  if (!sourceTables.value.some(table => key(table) === sourceKey.value)) {
    sourceKey.value = sourceTables.value[0] ? key(sourceTables.value[0]) : ''
  }
  if (!targetTables.value.some(table => key(table) === targetKey.value)) {
    targetKey.value = targetTables.value[0] ? key(targetTables.value[0]) : ''
  }
}, { immediate: true })

const selectedSource = computed(() => sourceTables.value.find(table => key(table) === sourceKey.value))
const selectedTarget = computed(() => targetTables.value.find(table => key(table) === targetKey.value))
const canCreate = computed(() => !props.busy && props.catalog?.source?.reachable
  && props.catalog?.target?.reachable && selectedSource.value && selectedTarget.value)

function submit() {
  if (!canCreate.value) return
  emit('create', {
    sourceSchema: selectedSource.value.schema,
    sourceTable: selectedSource.value.table,
    targetSchema: selectedTarget.value.schema,
    targetTable: selectedTarget.value.table,
  })
}
</script>

<template>
  <form class="task-form" @submit.prevent="submit">
    <div class="form-heading">
      <h2>建立迁移路径</h2>
      <p>选择预配置的 MySQL 源表和已建好的空 PostgreSQL 目标表。</p>
    </div>
    <div class="form-grid">
      <label>
        <span>MySQL 源表</span>
        <select v-model="sourceKey" :disabled="!catalog?.source?.reachable || busy">
          <option v-for="table in sourceTables" :key="key(table)" :value="key(table)">{{ key(table) }}</option>
        </select>
        <small v-if="selectedSource">{{ selectedSource.columns.length }} 列 · 源端只读</small>
      </label>
      <label>
        <span>PostgreSQL 目标表</span>
        <select v-model="targetKey" :disabled="!catalog?.target?.reachable || busy">
          <option v-for="table in targetTables" :key="key(table)" :value="key(table)">{{ key(table) }}</option>
        </select>
        <small v-if="selectedTarget">{{ selectedTarget.columns.length }} 列 · 创建任务前须为空</small>
      </label>
    </div>
    <p class="form-hint">仅支持单列有符号 BIGINT 主键；两端列类型必须兼容。创建时后端会再次校验。</p>
    <button class="primary-button" type="submit" :disabled="!canCreate">
      {{ busy ? '正在创建…' : '创建任务' }}
    </button>
  </form>
</template>
