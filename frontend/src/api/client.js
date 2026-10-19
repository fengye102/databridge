const errorMessages = {
  INVALID_REQUEST: '输入内容不符合要求，请检查表名和主键。',
  NOT_FOUND: '任务或报告不存在，请刷新列表。',
  TASK_NOT_FOUND: '任务不存在，请刷新列表。',
  TARGET_ALREADY_OWNED: '目标表已经被其他任务使用。',
  INVALID_STATE: '当前任务状态不允许此操作。',
  INTERNAL_ERROR: '后端处理失败，请查看本机服务日志。',
}

export class ApiError extends Error {
  constructor(code, status, message) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

export async function request(path, options = {}) {
  let response
  try {
    response = await fetch(path, {
      ...options,
      headers: { Accept: 'application/json', ...options.headers },
    })
  } catch {
    throw new ApiError('NETWORK_ERROR', 0, '本机后端未连接，请检查服务是否启动。')
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    const code = error.code || 'INTERNAL_ERROR'
    throw new ApiError(code, response.status,
      errorMessages[code] || errorMessages.INTERNAL_ERROR)
  }
  return response.status === 204 ? null : response.json()
}

const taskRoot = '/api/tasks'
const taskPath = (id) => `${taskRoot}/${encodeURIComponent(id)}`

export const taskApi = {
  catalog: () => request('/api/catalog'),
  list: () => request(taskRoot),
  get: (id) => request(taskPath(id)),
  progress: (id) => request(`${taskPath(id)}/progress`),
  reports: (id) => request(`${taskPath(id)}/verifications`),
  create: (table) => request(taskRoot, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(table),
  }),
  action: (id, action) => request(`${taskPath(id)}/${action}`, { method: 'POST' }),
  verify: (id) => request(`${taskPath(id)}/verifications`, { method: 'POST' }),
}
