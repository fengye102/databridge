<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElButton, ElMessageBox } from 'element-plus'
import { taskApi } from './api/client.js'
import { createVisiblePoller } from './api/visible-poller.js'
import TaskForm from './components/TaskForm.vue'
import TaskProgress from './components/TaskProgress.vue'
import TaskStatus from './components/TaskStatus.vue'
import VerificationReport from './components/VerificationReport.vue'

const catalog = ref(null)
const tasks = ref([])
const selectedId = ref(null)
const progress = ref(null)
const reports = ref([])
const showCreate = ref(false)
const loading = ref(true)
const busyAction = ref(null)
const error = ref('')
const selectedTask = computed(() => tasks.value.find(task => task.taskId === selectedId.value) || null)
const currentTable = computed(() => selectedTask.value?.table)
let poller

async function loadCatalog() {
  try {
    catalog.value = await taskApi.catalog()
  } catch (cause) {
    error.value = cause.message
    catalog.value = { source: { reachable: false, tables: [] },
      target: { reachable: false, tables: [] } }
  }
}

async function refreshDetails() {
  if (!selectedId.value) {
    progress.value = null
    reports.value = []
    return
  }
  const id = selectedId.value
  const [nextProgress, nextReports] = await Promise.all([
    taskApi.progress(id), taskApi.reports(id),
  ])
  if (selectedId.value === id) {
    progress.value = nextProgress
    reports.value = nextReports
  }
}

async function refreshSnapshot() {
  try {
    const nextTasks = await taskApi.list()
    tasks.value = nextTasks
    if (!nextTasks.some(task => task.taskId === selectedId.value)) {
      selectedId.value = nextTasks[0]?.taskId || null
    }
    await refreshDetails()
    error.value = ''
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function selectTask(taskId) {
  selectedId.value = taskId
  showCreate.value = false
  progress.value = null
  reports.value = []
  try { await refreshDetails() } catch (cause) { error.value = cause.message }
}

async function createTask(table) {
  busyAction.value = 'create'
  error.value = ''
  try {
    const created = await taskApi.create(table)
    selectedId.value = created.taskId
    showCreate.value = false
    await refreshSnapshot()
  } catch (cause) {
    error.value = cause.message
  } finally {
    busyAction.value = null
  }
}

async function runAction(action) {
  if (!selectedId.value || busyAction.value) return
  if (action === 'stop') {
    try {
      await ElMessageBox.confirm('停止后将保留检查点和 CDC 位点。再次启动会继续使用已有任务。',
        '确认停止任务', { confirmButtonText: '停止任务', cancelButtonText: '取消', type: 'warning' })
    } catch { return }
  }
  busyAction.value = action
  error.value = ''
  try {
    if (action === 'verify') await taskApi.verify(selectedId.value)
    else await taskApi.action(selectedId.value, action)
    await refreshSnapshot()
  } catch (cause) {
    error.value = cause.message
  } finally {
    busyAction.value = null
  }
}

const canStart = computed(() => ['CREATED', 'STOPPED', 'FAILED'].includes(selectedTask.value?.status))
const canPause = computed(() => ['CAPTURING', 'COPYING', 'REPLAYING', 'STREAMING'].includes(selectedTask.value?.status))
const canStop = computed(() => canPause.value || selectedTask.value?.status === 'PAUSED')

onMounted(async () => {
  await Promise.all([loadCatalog(), refreshSnapshot()])
  poller = createVisiblePoller(refreshSnapshot)
  poller.start()
})
onUnmounted(() => poller?.stop())
</script>

<template>
  <div class="console-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
        <div><strong>DataBridge</strong><small>迁移与同步控制台</small></div></div>
      <div class="sidebar-divider"></div>
      <section class="connection-panel" aria-label="数据库连接">
        <h2>本机连接</h2>
        <div class="connection-row"><span class="connection-node" :class="catalog?.source?.reachable ? 'online' : 'offline'"></span>
          <span>MySQL 源库</span><strong>{{ catalog?.source?.reachable ? '可用' : '离线' }}</strong></div>
        <div class="connection-row"><span class="connection-node" :class="catalog?.target?.reachable ? 'online' : 'offline'"></span>
          <span>PostgreSQL 目标库</span><strong>{{ catalog?.target?.reachable ? '可用' : '离线' }}</strong></div>
        <button class="sidebar-link" type="button" @click="loadCatalog">刷新连接与表结构</button>
      </section>
      <div class="sidebar-divider"></div>
      <div class="task-list-heading"><h2>迁移任务 <span>{{ tasks.length }}</span></h2>
        <button type="button" class="new-task" @click="showCreate = true">新建</button></div>
      <nav class="task-list" aria-label="迁移任务">
        <button v-for="task in tasks" :key="task.taskId" type="button" class="task-list-item"
          :class="{ active: task.taskId === selectedId && !showCreate }"
          @click="selectTask(task.taskId)">
          <span class="task-list-name">{{ task.table.sourceTable }} <span aria-hidden="true">→</span> {{ task.table.targetTable }}</span>
          <TaskStatus :status="task.status" />
        </button>
        <p v-if="!tasks.length" class="sidebar-empty">还没有迁移任务。</p>
      </nav>
      <p class="sidebar-footnote">仅限 127.0.0.1 本机访问<br>首版单进程、单任务运行</p>
    </aside>

    <main class="main-content">
      <header class="topbar"><span>工作台</span><span class="topbar-right">MySQL <b aria-hidden="true">→</b> PostgreSQL <em>本机运行</em></span></header>
      <div v-if="error" class="error-banner" role="alert"><strong>操作未完成</strong><span>{{ error }}</span>
        <button type="button" aria-label="关闭提示" @click="error = ''">×</button></div>
      <div v-if="loading" class="loading-state" role="status">正在读取任务与检查点…</div>

      <div v-else-if="showCreate || !selectedTask" class="content-wrap creation-view">
        <div class="page-heading"><span class="eyebrow">任务配置</span><h1>把一张表安全地迁到另一端</h1>
          <p>DataBridge 先捕获变更，再分批复制，最后按顺序回放并持续同步。</p></div>
        <TaskForm :catalog="catalog" :busy="busyAction === 'create'" @create="createTask" />
        <p class="creation-note">目标表必须已创建且为空；任务创建后由后端再次校验主键、列类型和探针权限。</p>
      </div>

      <div v-else class="content-wrap">
        <div class="page-heading task-heading"><div><span class="eyebrow">迁移任务 / {{ selectedTask.taskId.slice(0, 8) }}</span>
          <h1>{{ currentTable.sourceTable }} <span class="heading-arrow" aria-hidden="true">→</span> {{ currentTable.targetTable }}</h1>
          <p>单表同步路径 · 状态与数据来自目标库持久记录</p></div>
          <TaskStatus :status="selectedTask.status" /></div>

        <section class="route-panel" aria-label="迁移路径">
          <div class="route-endpoint"><span class="endpoint-type">SOURCE / MYSQL</span>
            <strong>{{ currentTable.sourceSchema }}.{{ currentTable.sourceTable }}</strong>
            <small>Binlog 捕获 + JDBC 分批读取</small></div>
          <div class="route-line" aria-hidden="true"><span></span><b>CDC 事件先持久化</b><span></span></div>
          <div class="route-endpoint target"><span class="endpoint-type">TARGET / POSTGRESQL</span>
            <strong>{{ currentTable.targetSchema }}.{{ currentTable.targetTable }}</strong>
            <small>检查点事务 + 顺序应用</small></div>
        </section>

        <div class="action-bar"><div><h2>任务操作</h2><p>一次只运行一个任务。校验前请先暂停源端写入。</p></div>
          <div class="action-buttons">
            <ElButton v-if="canStart" type="primary" :loading="busyAction === 'start'" :disabled="!!busyAction" @click="runAction('start')">{{ selectedTask.status === 'CREATED' ? '启动任务' : '重新启动' }}</ElButton>
            <ElButton v-if="canPause" :loading="busyAction === 'pause'" :disabled="!!busyAction" @click="runAction('pause')">暂停</ElButton>
            <ElButton v-if="selectedTask.status === 'PAUSED'" type="primary" :loading="busyAction === 'resume'" :disabled="!!busyAction" @click="runAction('resume')">继续</ElButton>
            <ElButton v-if="canStop" type="danger" plain :loading="busyAction === 'stop'" :disabled="!!busyAction" @click="runAction('stop')">停止</ElButton>
            <ElButton v-if="selectedTask.status === 'STREAMING'" type="primary" plain :loading="busyAction === 'verify'" :disabled="!!busyAction" @click="runAction('verify')">{{ busyAction === 'verify' ? '正在校验…' : '运行一致性校验' }}</ElButton>
          </div></div>
        <p v-if="selectedTask.status === 'FAILED'" class="failure-reason">失败原因：{{ selectedTask.errorMessage || '请查看本机后端日志' }}</p>

        <TaskProgress :status="selectedTask.status" :progress="progress" />

        <section class="reports-panel"><div class="section-heading"><div><h2>校验报告</h2>
          <p>前后 CDC 探针围住扫描窗口。源端写入时只给出“结论未定”。</p></div>
          <span class="report-count">最近 {{ reports.length }} 份</span></div>
          <div v-if="reports.length" class="reports-list">
            <VerificationReport v-for="report in reports" :key="report.runId" :report="report" />
          </div>
          <div v-else class="report-empty"><strong>还没有校验记录</strong>
            <p>任务进入持续同步且源端停写后，运行一致性校验。</p></div>
        </section>
      </div>
    </main>
  </div>
</template>
