<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fileApi } from '@/api/file'
import { publishApi } from '@/api/publish'
import { FILE_TYPES, fileStatus, taskStatus } from '@/utils/enums'
import { formatBytes, formatDateTime, todayIso, truncate } from '@/utils/format'
import StatusTag from '@/components/StatusTag.vue'
import type { FileChecksumVO, FileStatusLogVO, FileVO } from '@/types/models'

const router = useRouter()

const loading = ref(false)
const files = ref<FileVO[]>([])

const filters = reactive({
  settleDate: todayIso(),
  memberId: '',
  fileType: '',
})

async function load() {
  loading.value = true
  try {
    const query: Record<string, string> = {}
    if (filters.settleDate) query.settleDate = filters.settleDate
    if (filters.memberId) query.memberId = filters.memberId
    if (filters.fileType) query.fileType = filters.fileType
    files.value = (await fileApi.list(query)) ?? []
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.settleDate = todayIso()
  filters.memberId = ''
  filters.fileType = ''
  load()
}

onMounted(load)

// ───── detail drawer ─────
const detailVisible = ref(false)
const detail = ref<FileVO | null>(null)
const checksum = ref<FileChecksumVO | null>(null)
const logs = ref<FileStatusLogVO[]>([])
const detailLoading = ref(false)

async function openDetail(row: FileVO) {
  detail.value = row
  checksum.value = null
  logs.value = []
  detailVisible.value = true
  detailLoading.value = true
  try {
    const [cs, lg] = await Promise.all([
      fileApi.checksum(row.fileNo).catch(() => null),
      fileApi.statusLogs(row.fileNo).catch(() => [] as FileStatusLogVO[]),
    ])
    checksum.value = cs
    logs.value = lg ?? []
  } finally {
    detailLoading.value = false
  }
}

function toDownload(row: FileVO) {
  router.push({ name: 'download', query: { fileNo: row.fileNo, memberId: row.memberId } })
}

// ───── lifecycle: revoke / reissue ─────
const lifecycleVisible = ref(false)
const lifecycleMode = ref<'revoke' | 'reissue'>('revoke')
const lifecycleFile = ref<FileVO | null>(null)
const lifecycleLoading = ref(false)
const lifecycleForm = reactive({ operator: '', reason: '' })

function openLifecycle(mode: 'revoke' | 'reissue', row: FileVO) {
  lifecycleMode.value = mode
  lifecycleFile.value = row
  lifecycleForm.operator = ''
  lifecycleForm.reason = ''
  lifecycleVisible.value = true
}

async function submitLifecycle() {
  if (!lifecycleForm.reason.trim()) {
    ElMessage.warning(lifecycleMode.value === 'revoke' ? '请填写撤销原因' : '请填写重发原因')
    return
  }
  const f = lifecycleFile.value
  if (!f) return
  await ElMessageBox.confirm(
    `${lifecycleMode.value === 'revoke' ? '撤销' : '重发'}文件 ${f.fileNo}？\n原因：${lifecycleForm.reason}`,
    lifecycleMode.value === 'revoke' ? '撤销文件' : '重发文件',
    { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
  )
  lifecycleLoading.value = true
  try {
    if (lifecycleMode.value === 'revoke') {
      await publishApi.revoke(f.fileNo, {
        operator: lifecycleForm.operator,
        reason: lifecycleForm.reason,
      })
      ElMessage.success('文件已撤销')
    } else {
      const r = await publishApi.reissue(f.fileNo, {
        operator: lifecycleForm.operator,
        reason: lifecycleForm.reason,
      })
      ElMessage.success(`文件已重发，新版本 v${r.newVersion ?? '?'}`)
    }
    lifecycleVisible.value = false
    await load()
  } finally {
    lifecycleLoading.value = false
  }
}

function canRevoke(row: FileVO) {
  return row.status === 'PUBLISHED'
}
function canReissue(row: FileVO) {
  return row.status === 'REVOKED'
}
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <span class="overline font-mono">03 · FILES</span>
        <h1>文件管理</h1>
        <p class="lede">结算文件的元数据查询、校验和、状态变更日志，以及撤销/重发生命周期操作。</p>
      </div>
    </header>

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
      <el-input v-model="filters.memberId" placeholder="会员编号" clearable style="width: 150px" />
      <el-select v-model="filters.fileType" placeholder="文件类型" clearable style="width: 150px">
        <el-option v-for="ft in FILE_TYPES" :key="ft" :label="ft" :value="ft" />
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
        :data="files"
        style="width: 100%"
        empty-text="该条件下暂无文件"
      >
        <el-table-column label="文件编号" min-width="210">
          <template #default="{ row }">
            <span class="font-mono id-cell" :title="row.fileNo">{{ row.fileNo }}</span>
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
        <el-table-column label="文件名" min-width="200">
          <template #default="{ row }">
            <span class="fname" :title="row.fileName">{{ truncate(row.fileName, 32) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">
            <span class="font-mono dim">{{ formatBytes(row.fileSize) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <StatusTag :meta="fileStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="下载次数" width="100">
          <template #default="{ row }">
            <span class="font-mono">{{ row.downloadCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link @click="openDetail(row)">详情</el-button>
            <el-button link @click="toDownload(row)">下载</el-button>
            <el-button
              v-if="canRevoke(row)"
              link
              type="danger"
              @click="openLifecycle('revoke', row)"
            >撤销</el-button>
            <el-button
              v-if="canReissue(row)"
              link
              type="warning"
              @click="openLifecycle('reissue', row)"
            >重发</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- detail drawer -->
    <el-drawer v-model="detailVisible" title="文件详情" size="560px" direction="rtl">
      <div v-if="detail" v-loading="detailLoading" class="drawer-body">
        <div class="detail-head">
          <span class="font-mono detail-id">{{ detail.fileNo }}</span>
          <StatusTag :meta="fileStatus(detail.status)" show-code />
        </div>

        <el-descriptions :column="2" border class="desc">
          <el-descriptions-item label="文件名" :span="2">{{ detail.fileName }}</el-descriptions-item>
          <el-descriptions-item label="结算日期">{{ detail.settleDate }}</el-descriptions-item>
          <el-descriptions-item label="文件类型">{{ detail.fileType }}</el-descriptions-item>
          <el-descriptions-item label="会员编号">{{ detail.memberId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detail.version }}</el-descriptions-item>
          <el-descriptions-item label="文件大小">
            <span class="font-mono">{{ formatBytes(detail.fileSize) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="下载次数">
            <span class="font-mono">{{ detail.downloadCount ?? 0 }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="MD5" :span="2">
            <span class="font-mono md5">{{ checksum?.fileMd5 || detail.fileMd5 || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="section-title font-mono">STATUS LOG</div>
        <el-table :data="logs" size="small" empty-text="无状态变更记录" style="width: 100%">
          <el-table-column label="变更" width="160">
            <template #default="{ row }">
              <span class="font-mono">{{ row.beforeStatus || '∅' }} → {{ row.afterStatus }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="operationType" label="操作" width="90" />
          <el-table-column label="批次/操作人" min-width="140">
            <template #default="{ row }">
              <span class="font-mono dim">{{ row.batchNo || row.operator || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">
              <span class="font-mono dim">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="logs.length && logs[0]?.reason" class="log-reason">
          <span class="dim">最近原因：</span>{{ logs[0]?.reason }}
        </div>
      </div>
    </el-drawer>

    <!-- lifecycle dialog -->
    <el-dialog
      v-model="lifecycleVisible"
      :title="lifecycleMode === 'revoke' ? '撤销文件' : '重发文件'"
      width="440px"
      align-center
    >
      <p class="drawer-hint">
        {{ lifecycleMode === 'revoke'
          ? '撤销后文件不可下载，状态变为 REVOKED。'
          : '重发将生成新版本文件，旧文件状态变为 REISSUED。' }}
        请填写原因（必填）。
      </p>
      <el-form label-position="top" class="form">
        <el-form-item label="文件编号">
          <span class="font-mono detail-id">{{ lifecycleFile?.fileNo }}</span>
        </el-form-item>
        <el-form-item label="操作人（可选）">
          <el-input v-model="lifecycleForm.operator" placeholder="如 admin" />
        </el-form-item>
        <el-form-item :label="lifecycleMode === 'revoke' ? '撤销原因' : '重发原因'" required>
          <el-input v-model="lifecycleForm.reason" type="textarea" :rows="3" placeholder="请填写原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lifecycleVisible = false">取消</el-button>
        <el-button
          :type="lifecycleMode === 'revoke' ? 'danger' : 'primary'"
          :loading="lifecycleLoading"
          @click="submitLifecycle"
        >{{ lifecycleMode === 'revoke' ? '确认撤销' : '确认重发' }}</el-button>
      </template>
    </el-dialog>
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
.fname {
  color: var(--fg);
  font-size: 12.5px;
}
.drawer-body {
  padding: 22px 24px;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.detail-id {
  color: var(--amber);
  font-size: 13px;
  word-break: break-all;
}
.desc {
  margin-bottom: 24px;
}
.md5 {
  color: var(--fg-muted);
  font-size: 11px;
  word-break: break-all;
}
.section-title {
  color: var(--fg-dim);
  font-size: 11px;
  letter-spacing: 0.14em;
  margin: 0 0 10px;
}
.log-reason {
  margin-top: 10px;
  font-size: 12px;
  color: var(--fg-muted);
}
.drawer-hint {
  color: var(--fg-muted);
  font-size: 13px;
  margin: 0 0 20px;
}
.form :deep(.el-form-item) {
  margin-bottom: 18px;
}
</style>
