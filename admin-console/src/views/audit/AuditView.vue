<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { auditApi } from '@/api/audit'
import { DOWNLOAD_STATUS_OPTIONS, downloadStatus } from '@/utils/enums'
import { FILE_TYPES } from '@/utils/enums'
import { formatBytes, formatDateTime, truncate } from '@/utils/format'
import StatusTag from '@/components/StatusTag.vue'
import type { DownloadAuditDTO, DownloadAuditQuery } from '@/types/models'

const route = useRoute()

type ViewMode = 'all' | 'file' | 'member'
const mode = ref<ViewMode>('all')

const filters = reactive<DownloadAuditQuery>({
  memberId: '',
  fileNo: '',
  settleDate: '',
  fileType: '',
  downloadStatus: '',
  clientIp: '',
  startTime: '',
  endTime: '',
  pageNo: 1,
  pageSize: 20,
})

const dateRange = ref<[string, string] | null>(null)

const loading = ref(false)
const records = ref<DownloadAuditDTO[]>([])
const total = ref(0)

const pageTitle = computed(() => {
  if (mode.value === 'file') return '按文件下载审计'
  if (mode.value === 'member') return '按会员下载审计'
  return '下载审计查询'
})

function buildQuery(): DownloadAuditQuery {
  const q: DownloadAuditQuery = {
    pageNo: filters.pageNo,
    pageSize: filters.pageSize,
  }
  if (mode.value === 'file' && pathKey.value) q.fileNo = pathKey.value
  else if (filters.fileNo) q.fileNo = filters.fileNo

  if (mode.value === 'member' && pathKey.value) q.memberId = pathKey.value
  else if (filters.memberId) q.memberId = filters.memberId

  if (filters.settleDate) q.settleDate = filters.settleDate
  if (filters.fileType) q.fileType = filters.fileType
  if (filters.downloadStatus) q.downloadStatus = filters.downloadStatus
  if (filters.clientIp) q.clientIp = filters.clientIp
  if (dateRange.value && dateRange.value[0]) {
    q.startTime = dateRange.value[0] + ' 00:00:00'
    q.endTime = dateRange.value[1] + ' 23:59:59'
  }
  return q
}

// path key for file/member modes (entered via a separate input)
const pathKey = ref('')

async function load() {
  loading.value = true
  try {
    const q = buildQuery()
    let r
    if (mode.value === 'file' && pathKey.value) {
      r = await auditApi.listByFile(pathKey.value, q)
    } else if (mode.value === 'member' && pathKey.value) {
      r = await auditApi.listByMember(pathKey.value, q)
    } else {
      r = await auditApi.list(q)
    }
    records.value = r?.records ?? []
    total.value = r?.total ?? 0
  } finally {
    loading.value = false
  }
}

function search() {
  filters.pageNo = 1
  load()
}

function resetFilters() {
  filters.memberId = ''
  filters.fileNo = ''
  filters.settleDate = ''
  filters.fileType = ''
  filters.downloadStatus = ''
  filters.clientIp = ''
  dateRange.value = null
  pathKey.value = ''
  filters.pageNo = 1
  load()
}

function onPageChange(p: number) {
  filters.pageNo = p
  load()
}
function onSizeChange(s: number) {
  filters.pageSize = s
  filters.pageNo = 1
  load()
}

// ───── detail ─────
const detailVisible = ref(false)
const detail = ref<DownloadAuditDTO | null>(null)
function openDetail(row: DownloadAuditDTO) {
  detail.value = row
  detailVisible.value = true
}

onMounted(() => {
  const q = route.query
  if (q.fileNo) {
    mode.value = 'file'
    pathKey.value = String(q.fileNo)
  } else if (q.memberId) {
    mode.value = 'member'
    pathKey.value = String(q.memberId)
  }
  if (q.downloadStatus) filters.downloadStatus = String(q.downloadStatus)
  load()
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <span class="overline font-mono">06 · AUDIT</span>
        <h1>{{ pageTitle }}</h1>
        <p class="lede">会员下载行为的完整审计记录，支持多条件组合查询与分页。</p>
      </div>
    </header>

    <!-- mode tabs -->
    <div class="mode-tabs">
      <button
        class="mode-tab"
        :class="{ active: mode === 'all' }"
        @click="mode = 'all'; resetFilters()"
      >全部审计</button>
      <button
        class="mode-tab"
        :class="{ active: mode === 'file' }"
        @click="mode = 'file'; resetFilters()"
      >按文件</button>
      <button
        class="mode-tab"
        :class="{ active: mode === 'member' }"
        @click="mode = 'member'; resetFilters()"
      >按会员</button>
    </div>

    <!-- filter -->
    <div class="panel filter">
      <template v-if="mode === 'file'">
        <el-input v-model="pathKey" placeholder="文件编号" clearable style="width: 240px" />
      </template>
      <template v-else-if="mode === 'member'">
        <el-input v-model="pathKey" placeholder="会员编号" clearable style="width: 180px" />
      </template>
      <template v-else>
        <el-input v-model="filters.memberId" placeholder="会员编号" clearable style="width: 140px" />
        <el-input v-model="filters.fileNo" placeholder="文件编号" clearable style="width: 200px" />
      </template>

      <el-date-picker
        v-model="filters.settleDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="结算日期"
        clearable
        style="width: 150px"
      />
      <el-select v-model="filters.fileType" placeholder="文件类型" clearable style="width: 130px">
        <el-option v-for="ft in FILE_TYPES" :key="ft" :label="ft" :value="ft" />
      </el-select>
      <el-select
        v-model="filters.downloadStatus"
        placeholder="下载状态"
        clearable
        style="width: 160px"
      >
        <el-option
          v-for="opt in DOWNLOAD_STATUS_OPTIONS"
          :key="opt.code"
          :label="`${opt.desc} · ${opt.code}`"
          :value="opt.code"
        />
      </el-select>
      <el-input v-model="filters.clientIp" placeholder="客户端 IP" clearable style="width: 150px" />
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        range-separator="—"
        start-placeholder="开始"
        end-placeholder="结束"
        value-format="YYYY-MM-DD"
        style="width: 260px"
      />
      <div class="filter-spacer" />
      <el-button @click="resetFilters">重置</el-button>
      <el-button type="primary" @click="search">
        <el-icon style="margin-right: 4px"><Search /></el-icon>查询
      </el-button>
    </div>

    <!-- table -->
    <div class="panel table-panel">
      <el-table
        v-loading="loading"
        :data="records"
        style="width: 100%"
        empty-text="暂无审计记录"
      >
        <el-table-column label="审计编号" min-width="180">
          <template #default="{ row }">
            <span class="font-mono id-cell" :title="row.auditNo">{{ row.auditNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="请求ID" width="150">
          <template #default="{ row }">
            <span class="font-mono dim">{{ truncate(row.requestId, 16) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="会员" width="80">
          <template #default="{ row }">
            <span class="font-mono">{{ row.memberId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="文件编号" min-width="170">
          <template #default="{ row }">
            <span class="font-mono dim">{{ truncate(row.fileNo, 18) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="80" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <StatusTag :meta="downloadStatus(row.downloadStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="IP" width="130">
          <template #default="{ row }">
            <span class="font-mono dim">{{ row.clientIp || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">
            <span class="font-mono">{{ row.costMs != null ? row.costMs + 'ms' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <span class="font-mono dim">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <span class="total font-mono dim">共 {{ total }} 条</span>
        <el-pagination
          :current-page="filters.pageNo"
          :page-size="filters.pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="sizes, prev, pager, next"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- detail drawer -->
    <el-drawer v-model="detailVisible" title="审计详情" size="560px" direction="rtl">
      <div v-if="detail" class="drawer-body">
        <div class="detail-head">
          <span class="font-mono detail-id">{{ detail.auditNo }}</span>
          <StatusTag :meta="downloadStatus(detail.downloadStatus)" show-code />
        </div>
        <el-descriptions :column="2" border class="desc">
          <el-descriptions-item label="请求ID">
            <span class="font-mono dim">{{ detail.requestId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="行为">{{ detail.action }}</el-descriptions-item>
          <el-descriptions-item label="会员编号">{{ detail.memberId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="文件编号">
            <span class="font-mono">{{ detail.fileNo || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="文件名" :span="2">{{ detail.fileName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="文件类型">{{ detail.fileType || '—' }}</el-descriptions-item>
          <el-descriptions-item label="结算日期">{{ detail.settleDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detail.version ?? '?' }}</el-descriptions-item>
          <el-descriptions-item label="客户端 IP">
            <span class="font-mono">{{ detail.clientIp || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="文件大小">
            <span class="font-mono">{{ formatBytes(detail.fileSize) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="下载字节">
            <span class="font-mono">{{ formatBytes(detail.downloadBytes) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">
            <span class="font-mono dim">{{ formatDateTime(detail.startTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            <span class="font-mono dim">{{ formatDateTime(detail.endTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">
            <span class="font-mono">{{ detail.costMs ?? '—' }} ms</span>
          </el-descriptions-item>
          <el-descriptions-item label="Token 摘要">
            <span class="font-mono md5">{{ detail.tokenDigest || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="User-Agent" :span="2">
            <span class="dim">{{ detail.userAgent || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="失败原因" :span="2">
            <span v-if="detail.failReason" class="err-on">{{ detail.failReason }}</span>
            <span v-else class="dim">无</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.page {
  max-width: 1380px;
}
.page-head {
  margin-bottom: 20px;
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
.mode-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.mode-tab {
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--fg-muted);
  padding: 8px 18px;
  border-radius: var(--radius);
  font-family: var(--font-ui);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.mode-tab:hover {
  color: var(--cream);
  border-color: var(--line-strong);
}
.mode-tab.active {
  color: var(--amber);
  border-color: var(--amber-line);
  background: var(--amber-soft);
}
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
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
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-top: 1px solid var(--line);
}
.total {
  font-size: 12px;
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
  margin-top: 4px;
}
.md5 {
  color: var(--fg-muted);
  font-size: 11px;
  word-break: break-all;
}
.err-on {
  color: var(--sienna);
}
.dim {
  color: var(--fg-dim);
}
</style>
