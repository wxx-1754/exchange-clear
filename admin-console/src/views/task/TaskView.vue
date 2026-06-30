<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import { TASK_STATUS_OPTIONS, taskStatus, FILE_TYPES } from '@/utils/enums'
import { formatDateTime, todayIso, truncate } from '@/utils/format'
import StatusTag from '@/components/StatusTag.vue'
import type { TaskVO } from '@/types/models'

// ───── list + filters ─────
const loading = ref(false)
const tasks = ref<TaskVO[]>([])

const filters = reactive({
  settleDate: '' as string,
  status: '' as string,
})

async function load() {
  loading.value = true
  try {
    const query: Record<string, string> = {}
    if (filters.settleDate) query.settleDate = filters.settleDate
    if (filters.status) query.status = filters.status
    tasks.value = (await taskApi.list(query)) ?? []
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.settleDate = ''
  filters.status = ''
  load()
}

onMounted(() => {
  filters.settleDate = todayIso()
  load()
})

// ───── KPI strip (derived from current list) ─────
const kpis = computed(() => {
  const acc = { total: tasks.value.length, pending: 0, running: 0, done: 0, failed: 0 }
  for (const t of tasks.value) {
    const m = taskStatus(t.status).tone
    if (t.status === 'INIT') acc.pending++
    else if (t.status === 'SENT' || t.status === 'GENERATING') acc.running++
    else if (t.status === 'GENERATED') acc.done++
    else if (m === 'danger' || m === 'warn') acc.failed++
  }
  return acc
})

// ───── create drawer ─────
const createVisible = ref(false)
const createLoading = ref(false)
const createForm = reactive({
  settleDate: todayIso(),
  fileType: 'TRADE',
  version: 1,
})

function openCreate() {
  createForm.settleDate = filters.settleDate || todayIso()
  createForm.fileType = 'TRADE'
  createForm.version = 1
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.settleDate) {
    ElMessage.warning('请选择结算日期')
    return
  }
  createLoading.value = true
  try {
    await taskApi.create({
      settleDate: createForm.settleDate,
      fileType: createForm.fileType,
      version: createForm.version,
    })
    ElMessage.success('任务已创建')
    createVisible.value = false
    await load()
  } finally {
    createLoading.value = false
  }
}

// ───── single actions ─────
async function generate(row: TaskVO) {
  await ElMessageBox.confirm(
    `确认触发任务生成？\n${row.taskNo} · ${row.fileType} v${row.version}`,
    '生成任务',
    { type: 'warning', confirmButtonText: '生成', cancelButtonText: '取消' },
  )
  await taskApi.generate(row.taskNo)
  ElMessage.success('已触发生成')
  await load()
}

async function resend(row: TaskVO) {
  await ElMessageBox.confirm(
    `确认重发任务？\n${row.taskNo} · ${row.fileType} v${row.version}`,
    '重发任务',
    { type: 'warning', confirmButtonText: '重发', cancelButtonText: '取消' },
  )
  await taskApi.resend(row.taskNo)
  ElMessage.success('已触发重发')
  await load()
}

// ───── batch dialog ─────
type BatchMode = 'generate' | 'send'
const batchVisible = ref(false)
const batchMode = ref<BatchMode>('generate')
const batchLoading = ref(false)
const batchForm = reactive({
  settleDate: todayIso(),
  fileType: 'TRADE',
  status: 'INIT',
})

function openBatch(mode: BatchMode) {
  batchMode.value = mode
  batchForm.settleDate = filters.settleDate || todayIso()
  batchForm.fileType = filters.status ? 'TRADE' : 'TRADE'
  batchForm.status = 'INIT'
  batchVisible.value = true
}

const batchTitle = computed(() =>
  batchMode.value === 'generate' ? '批量生成' : '批量投递',
)

async function submitBatch() {
  if (!batchForm.settleDate) {
    ElMessage.warning('请选择结算日期')
    return
  }
  batchLoading.value = true
  try {
    if (batchMode.value === 'generate') {
      const r = await taskApi.generateBatch({
        settleDate: batchForm.settleDate,
        fileType: batchForm.fileType,
      })
      reportBatch(r, '批量生成完成')
    } else {
      const r = await taskApi.sendBatch({
        settleDate: batchForm.settleDate,
        fileType: batchForm.fileType,
        status: batchForm.status,
      })
      reportBatch(r, '批量投递完成')
    }
    batchVisible.value = false
    await load()
  } finally {
    batchLoading.value = false
  }
}

function reportBatch(r: { successCount?: number; failedCount?: number; totalCount?: number }, ok: string) {
  const s = r.successCount ?? 0
  const f = r.failedCount ?? 0
  if (f > 0) ElMessage.warning(`${ok}：成功 ${s}，失败 ${f}`)
  else ElMessage.success(`${ok}：成功 ${s}`)
}

// ───── detail drawer ─────
const detailVisible = ref(false)
const detail = ref<TaskVO | null>(null)
function openDetail(row: TaskVO) {
  detail.value = row
  detailVisible.value = true
}

function retryText(row: TaskVO) {
  return `${row.retryCount ?? 0}/${row.maxRetryCount ?? 0}`
}
</script>

<template>
  <section class="page">
    <!-- header -->
    <header class="page-head">
      <div>
        <span class="overline font-mono">02 · TASKS</span>
        <h1>任务管理</h1>
        <p class="lede">结算任务的创建、生成、投递与重发。任务经 RocketMQ 异步投递至 worker 生成结算文件。</p>
      </div>
      <div class="head-actions">
        <el-button @click="openBatch('send')">批量投递</el-button>
        <el-button @click="openBatch('generate')">批量生成</el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 4px"><Plus /></el-icon>新建任务
        </el-button>
      </div>
    </header>

    <!-- KPI strip -->
    <div class="kpis">
      <div class="kpi">
        <span class="kpi-label font-mono">TOTAL</span>
        <span class="kpi-val font-mono">{{ kpis.total }}</span>
      </div>
      <div class="kpi">
        <span class="kpi-label font-mono">PENDING</span>
        <span class="kpi-val font-mono">{{ kpis.pending }}</span>
      </div>
      <div class="kpi">
        <span class="kpi-label font-mono">RUNNING</span>
        <span class="kpi-val font-mono">{{ kpis.running }}</span>
      </div>
      <div class="kpi">
        <span class="kpi-label font-mono">GENERATED</span>
        <span class="kpi-val font-mono" style="color: var(--sage)">{{ kpis.done }}</span>
      </div>
      <div class="kpi">
        <span class="kpi-label font-mono">FAILED</span>
        <span class="kpi-val font-mono" style="color: var(--sienna)">{{ kpis.failed }}</span>
      </div>
    </div>

    <!-- filter -->
    <div class="panel filter">
      <el-date-picker
        v-model="filters.settleDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="结算日期"
        :clearable="true"
        style="width: 180px"
      />
      <el-select
        v-model="filters.status"
        placeholder="任务状态"
        clearable
        style="width: 160px"
      >
        <el-option
          v-for="opt in TASK_STATUS_OPTIONS"
          :key="opt.code"
          :label="`${opt.desc} · ${opt.code}`"
          :value="opt.code"
        />
      </el-select>
      <div class="filter-spacer" />
      <el-button @click="resetFilters">重置</el-button>
      <el-button type="primary" @click="load">
        <el-icon style="margin-right: 4px"><Search /></el-icon>查询
      </el-button>
    </div>

    <!-- table -->
    <div class="panel table-panel">
      <el-table
        v-loading="loading"
        :data="tasks"
        style="width: 100%"
        :header-cell-style="{ background: 'transparent' }"
        empty-text="该条件下暂无任务"
      >
        <el-table-column label="任务编号" min-width="200">
          <template #default="{ row }">
            <span class="font-mono id-cell" :title="row.taskNo">{{ row.taskNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="settleDate" label="结算日期" width="120" />
        <el-table-column label="会员" width="90">
          <template #default="{ row }">
            <span class="font-mono">{{ row.memberId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="90" />
        <el-table-column label="版本" width="70">
          <template #default="{ row }">
            <span class="font-mono">v{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <StatusTag :meta="taskStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="重试" width="80">
          <template #default="{ row }">
            <span class="font-mono dim">{{ retryText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最后投递" width="170">
          <template #default="{ row }">
            <span class="font-mono dim">{{ formatDateTime(row.lastSendTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最后消费" width="170">
          <template #default="{ row }">
            <span class="font-mono dim">{{ formatDateTime(row.lastConsumeTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="180">
          <template #default="{ row }">
            <span class="err-cell" :class="{ 'err-on': row.errorMessage }" :title="row.errorMessage || ''">
              {{ truncate(row.errorMessage, 36) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="generate(row)">生成</el-button>
            <el-button link type="primary" @click="resend(row)">重发</el-button>
            <el-button link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- create drawer -->
    <el-drawer v-model="createVisible" title="新建结算任务" size="420px" direction="rtl">
      <div class="drawer-body">
        <p class="drawer-hint">为指定结算日期、文件类型与版本创建结算任务，创建后可触发生成。</p>
        <el-form label-position="top" class="form">
          <el-form-item label="结算日期" required>
            <el-date-picker
              v-model="createForm.settleDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择结算日期"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="文件类型" required>
            <el-select v-model="createForm.fileType" style="width: 100%">
              <el-option v-for="ft in FILE_TYPES" :key="ft" :label="ft" :value="ft" />
            </el-select>
          </el-form-item>
          <el-form-item label="文件版本" required>
            <el-input-number v-model="createForm.version" :min="1" :max="99" style="width: 100%" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <div class="drawer-foot">
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="createLoading" @click="submitCreate">创建任务</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- batch dialog -->
    <el-dialog v-model="batchVisible" :title="batchTitle" width="440px" align-center>
      <p class="drawer-hint">
        {{ batchMode === 'generate'
          ? '按结算日期与文件类型批量触发生成。'
          : '按结算日期与文件类型批量投递到 RocketMQ，可按任务状态筛选。' }}
      </p>
      <el-form label-position="top" class="form">
        <el-form-item label="结算日期" required>
          <el-date-picker
            v-model="batchForm.settleDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择结算日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="文件类型" required>
          <el-select v-model="batchForm.fileType" style="width: 100%">
            <el-option v-for="ft in FILE_TYPES" :key="ft" :label="ft" :value="ft" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="batchMode === 'send'" label="任务状态">
          <el-select v-model="batchForm.status" style="width: 100%">
            <el-option
              v-for="opt in TASK_STATUS_OPTIONS"
              :key="opt.code"
              :label="`${opt.desc} · ${opt.code}`"
              :value="opt.code"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchLoading" @click="submitBatch">
          {{ batchMode === 'generate' ? '执行批量生成' : '执行批量投递' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- detail drawer -->
    <el-drawer v-model="detailVisible" title="任务详情" size="520px" direction="rtl">
      <div v-if="detail" class="drawer-body">
        <div class="detail-head">
          <span class="font-mono detail-id">{{ detail.taskNo }}</span>
          <StatusTag :meta="taskStatus(detail.status)" show-code />
        </div>
        <el-descriptions :column="2" border size="default" class="desc">
          <el-descriptions-item label="结算日期">{{ detail.settleDate }}</el-descriptions-item>
          <el-descriptions-item label="文件类型">{{ detail.fileType }}</el-descriptions-item>
          <el-descriptions-item label="会员编号">{{ detail.memberId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detail.version }}</el-descriptions-item>
          <el-descriptions-item label="重试次数">{{ retryText(detail) }}</el-descriptions-item>
          <el-descriptions-item label="最后消息ID">
            <span class="font-mono dim">{{ detail.lastMessageId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">
            <span class="font-mono dim">{{ formatDateTime(detail.startTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            <span class="font-mono dim">{{ formatDateTime(detail.endTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="最后投递">
            <span class="font-mono dim">{{ formatDateTime(detail.lastSendTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="最后消费">
            <span class="font-mono dim">{{ formatDateTime(detail.lastConsumeTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="错误信息" :span="2">
            <span v-if="detail.errorMessage" class="err-on">{{ detail.errorMessage }}</span>
            <span v-else class="dim">无</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.page {
  max-width: 1320px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}
.overline {
  color: var(--amber);
  font-size: 12px;
  letter-spacing: 0.18em;
}
.page-head h1 {
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 30px;
  color: var(--cream);
  margin: 8px 0 6px;
  letter-spacing: -0.01em;
}
.lede {
  color: var(--fg-muted);
  max-width: 560px;
  margin: 0;
}
.head-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

/* KPI strip */
.kpis {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.kpi {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  position: relative;
  overflow: hidden;
}
.kpi::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--line-strong);
}
.kpi-label {
  color: var(--fg-dim);
  font-size: 10px;
  letter-spacing: 0.14em;
}
.kpi-val {
  font-size: 28px;
  font-weight: 600;
  color: var(--cream);
}

/* panels */
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.filter {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
}
.filter-spacer {
  flex: 1;
}
.table-panel {
  padding: 4px 0;
  overflow: hidden;
}

.id-cell {
  color: var(--cream);
  font-size: 12.5px;
}
.err-cell {
  color: var(--fg-dim);
  font-size: 12px;
}
.err-on {
  color: var(--sienna);
}

/* drawers / dialogs */
.drawer-body {
  padding: 22px 24px;
}
.drawer-hint {
  color: var(--fg-muted);
  font-size: 13px;
  margin: 0 0 20px;
}
.form :deep(.el-form-item) {
  margin-bottom: 20px;
}
.drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 22px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.detail-id {
  color: var(--amber);
  font-size: 13px;
  word-break: break-all;
}
.desc {
  margin-top: 4px;
}
</style>
