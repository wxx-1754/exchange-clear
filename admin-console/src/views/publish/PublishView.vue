<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { publishApi } from '@/api/publish'
import { FILE_TYPES } from '@/utils/enums'
import { formatDateTime, todayIso } from '@/utils/format'
import StatusTag from '@/components/StatusTag.vue'
import { taskStatus } from '@/utils/enums'
import type { FilePublishBatch } from '@/types/models'

// publish batch status reuses task-status tones loosely
function batchStatus(code: string | null) {
  if (!code) return { code: '-', desc: '-', tone: 'idle' as const }
  const map: Record<string, 'ok' | 'busy' | 'danger' | 'idle'> = {
    SUCCESS: 'ok',
    RUNNING: 'busy',
    PROCESSING: 'busy',
    FAILED: 'danger',
  }
  return { code, desc: code, tone: map[code] ?? 'idle' }
}

// ───── publish form ─────
const publishForm = reactive({
  settleDate: todayIso(),
  fileType: '',
  version: 1,
  operator: 'admin',
})
const publishLoading = ref(false)
const lastBatch = ref<{ batchNo: string; publishCount: number; status: string } | null>(null)

async function submitPublish() {
  if (!publishForm.settleDate) {
    ElMessage.warning('请选择结算日期')
    return
  }
  publishLoading.value = true
  try {
    const r = await publishApi.publish({
      settleDate: publishForm.settleDate,
      fileType: publishForm.fileType || undefined,
      version: publishForm.version || undefined,
      operator: publishForm.operator || undefined,
    })
    lastBatch.value = { batchNo: r.batchNo, publishCount: r.publishCount, status: r.status }
    ElMessage.success(`已发起发布，批次 ${r.batchNo}，发布 ${r.publishCount} 个文件`)
    await loadBatches()
  } finally {
    publishLoading.value = false
  }
}

// ───── batch list ─────
const batchFilterDate = ref('')
const batches = ref<FilePublishBatch[]>([])
const batchLoading = ref(false)

async function loadBatches() {
  batchLoading.value = true
  try {
    batches.value = (await publishApi.batches(batchFilterDate.value || undefined)) ?? []
  } finally {
    batchLoading.value = false
  }
}

onMounted(() => {
  batchFilterDate.value = todayIso()
  loadBatches()
})

const successRate = computed(() => {
  const t = batches.value.reduce((s, b) => s + (b.totalCount ?? 0), 0)
  const ok = batches.value.reduce((s, b) => s + (b.successCount ?? 0), 0)
  return { total: t, ok }
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <span class="overline font-mono">04 · PUBLISH</span>
        <h1>发布管理</h1>
        <p class="lede">将已生成（GENERATED）的结算文件批量发布为可下载状态，并查看发布批次与结果。</p>
      </div>
    </header>

    <div class="cols">
      <!-- publish form -->
      <div class="panel publish-card">
        <div class="card-head">
          <span class="card-title">发起发布</span>
          <span class="card-sub font-mono">POST /api/files/publish</span>
        </div>
        <el-form label-position="top" class="form">
          <el-form-item label="结算日期" required>
            <el-date-picker
              v-model="publishForm.settleDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="文件类型（可选）">
            <el-select v-model="publishForm.fileType" clearable placeholder="全部类型" style="width: 100%">
              <el-option v-for="ft in FILE_TYPES" :key="ft" :label="ft" :value="ft" />
            </el-select>
          </el-form-item>
          <el-form-item label="文件版本（可选）">
            <el-input-number v-model="publishForm.version" :min="1" :max="99" style="width: 100%" />
          </el-form-item>
          <el-form-item label="操作人">
            <el-input v-model="publishForm.operator" placeholder="admin" />
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="publishLoading" class="full-btn" @click="submitPublish">
          <el-icon style="margin-right: 6px"><Promotion /></el-icon>执行发布
        </el-button>

        <Transition name="fade">
          <div v-if="lastBatch" class="last-batch">
            <div class="lb-row">
              <span class="dim font-mono">BATCH</span>
              <span class="font-mono amber">{{ lastBatch.batchNo }}</span>
            </div>
            <div class="lb-row">
              <span class="dim font-mono">COUNT</span>
              <span class="font-mono">{{ lastBatch.publishCount }}</span>
            </div>
            <div class="lb-row">
              <span class="dim font-mono">STATUS</span>
              <StatusTag :meta="batchStatus(lastBatch.status)" />
            </div>
          </div>
        </Transition>
      </div>

      <!-- batch list -->
      <div class="panel batch-card">
        <div class="card-head">
          <span class="card-title">发布批次</span>
          <div class="batch-filter">
            <el-date-picker
              v-model="batchFilterDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="结算日期"
              clearable
              style="width: 150px"
              @change="loadBatches"
            />
            <el-button @click="loadBatches">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </div>
        </div>

        <div class="batch-kpis">
          <span class="font-mono"><span class="dim">BATCHES</span> {{ batches.length }}</span>
          <span class="font-mono"><span class="dim">FILES</span> {{ successRate.total }}</span>
          <span class="font-mono"><span class="dim">PUBLISHED</span>
            <span style="color: var(--sage)">{{ successRate.ok }}</span>
          </span>
        </div>

        <el-table
          v-loading="batchLoading"
          :data="batches"
          size="small"
          empty-text="暂无发布批次"
          style="width: 100%"
        >
          <el-table-column label="批次号" min-width="170">
            <template #default="{ row }">
              <span class="font-mono amber">{{ row.batchNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="settleDate" label="结算日期" width="110" />
          <el-table-column label="类型/版本" width="110">
            <template #default="{ row }">
              <span class="font-mono dim">{{ row.fileType || '—' }} / v{{ row.version ?? '?' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusTag :meta="batchStatus(row.status)" />
            </template>
          </el-table-column>
          <el-table-column label="成功/总数" width="100">
            <template #default="{ row }">
              <span class="font-mono">
                <span style="color: var(--sage)">{{ row.successCount ?? 0 }}</span>
                <span class="dim">/{{ row.totalCount ?? 0 }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作人" width="90">
            <template #default="{ row }">
              <span class="dim">{{ row.operator || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }">
              <span class="font-mono dim">{{ formatDateTime(row.startTime || row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="错误" min-width="140">
            <template #default="{ row }">
              <span v-if="row.errorMessage" class="err-on" :title="row.errorMessage">
                {{ row.errorMessage.slice(0, 30) }}
              </span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page {
  max-width: 1320px;
}
.page-head {
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
.cols {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 16px;
  align-items: start;
}
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
}
.card-title {
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 18px;
  color: var(--cream);
}
.card-sub {
  color: var(--fg-dim);
  font-size: 11px;
}
.batch-filter {
  display: flex;
  gap: 8px;
}
.form {
  padding: 18px 20px 4px;
}
.form :deep(.el-form-item) {
  margin-bottom: 18px;
}
.full-btn {
  width: calc(100% - 40px);
  margin: 0 20px 20px;
}
.last-batch {
  margin: 0 20px 20px;
  padding: 14px 16px;
  background: var(--ink);
  border: 1px solid var(--amber-line);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.lb-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}
.amber {
  color: var(--amber);
}
.batch-kpis {
  display: flex;
  gap: 22px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.err-on {
  color: var(--sienna);
  font-size: 12px;
}
.dim {
  color: var(--fg-dim);
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
