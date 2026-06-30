<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { statsApi } from '@/api/audit'
import { FILE_TYPES } from '@/utils/enums'
import { formatDateTime, todayIso } from '@/utils/format'
import type {
  AbnormalDownloadStatsDTO,
  FileDownloadStatsDTO,
  MemberDownloadStatsDTO,
} from '@/types/models'

const activeTab = ref<'file' | 'member' | 'abnormal'>('file')

// ───── file stats ─────
const fileQuery = reactive({ settleDate: todayIso(), fileType: '', fileNo: '' })
const fileStats = ref<FileDownloadStatsDTO[]>([])
const fileLoading = ref(false)
async function loadFileStats() {
  fileLoading.value = true
  try {
    const q: Record<string, string> = {}
    if (fileQuery.settleDate) q.settleDate = fileQuery.settleDate
    if (fileQuery.fileType) q.fileType = fileQuery.fileType
    if (fileQuery.fileNo) q.fileNo = fileQuery.fileNo
    fileStats.value = (await statsApi.fileDownload(q)) ?? []
  } finally {
    fileLoading.value = false
  }
}

// ───── member stats ─────
const memberQuery = reactive({ memberId: '', range: null as [string, string] | null })
const memberStats = ref<MemberDownloadStatsDTO[]>([])
const memberLoading = ref(false)
async function loadMemberStats() {
  memberLoading.value = true
  try {
    const q: Record<string, string> = {}
    if (memberQuery.memberId) q.memberId = memberQuery.memberId
    if (memberQuery.range && memberQuery.range[0]) {
      q.startTime = memberQuery.range[0] + ' 00:00:00'
      q.endTime = memberQuery.range[1] + ' 23:59:59'
    }
    memberStats.value = (await statsApi.memberDownload(q)) ?? []
  } finally {
    memberLoading.value = false
  }
}

// ───── abnormal stats ─────
const abnormalRange = ref<[string, string] | null>(null)
const abnormal = ref<AbnormalDownloadStatsDTO | null>(null)
const abnormalLoading = ref(false)
async function loadAbnormal() {
  abnormalLoading.value = true
  try {
    const q: Record<string, string> = {}
    if (abnormalRange.value && abnormalRange.value[0]) {
      q.startTime = abnormalRange.value[0] + ' 00:00:00'
      q.endTime = abnormalRange.value[1] + ' 23:59:59'
    }
    abnormal.value = await statsApi.abnormal(q)
  } finally {
    abnormalLoading.value = false
  }
}

const abnormalMetrics = ref<{ label: string; value: number; tone: string }[]>([])
function refreshAbnormalCards() {
  const a = abnormal.value
  if (!a) return
  abnormalMetrics.value = [
    { label: 'Token 无效', value: a.tokenInvalidCount, tone: 'var(--sienna)' },
    { label: 'Token 过期', value: a.tokenExpiredCount, tone: 'var(--sienna)' },
    { label: '权限拒绝', value: a.deniedCount, tone: 'var(--sienna)' },
    { label: '限流', value: a.limitedCount, tone: '#c89b3a' },
    { label: '文件未发布', value: a.fileNotPublishedCount, tone: '#c89b3a' },
    { label: '文件已撤销', value: a.fileRevokedCount, tone: 'var(--sienna)' },
    { label: '文件已重发', value: a.fileReissuedCount, tone: '#c89b3a' },
  ]
}

async function onTab(tab: 'file' | 'member' | 'abnormal') {
  activeTab.value = tab
  if (tab === 'file' && !fileStats.value.length) await loadFileStats()
  if (tab === 'member' && !memberStats.value.length) await loadMemberStats()
  if (tab === 'abnormal' && !abnormal.value) {
    await loadAbnormal()
    refreshAbnormalCards()
  }
}

onMounted(loadFileStats)

function barWidth(count: number, max: number): string {
  if (!max) return '0%'
  return Math.max(6, Math.round((count / max) * 100)) + '%'
}
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <span class="overline font-mono">07 · STATS</span>
        <h1>下载统计</h1>
        <p class="lede">按文件、按会员、按异常维度的下载行为统计，为风控与告警提供数据基础。</p>
      </div>
    </header>

    <div class="tabs">
      <button class="tab" :class="{ active: activeTab === 'file' }" @click="onTab('file')">文件下载统计</button>
      <button class="tab" :class="{ active: activeTab === 'member' }" @click="onTab('member')">会员下载统计</button>
      <button class="tab" :class="{ active: activeTab === 'abnormal' }" @click="onTab('abnormal')">异常下载统计</button>
    </div>

    <!-- file stats -->
    <div v-show="activeTab === 'file'" class="panel">
      <div class="filter">
        <el-date-picker v-model="fileQuery.settleDate" type="date" value-format="YYYY-MM-DD" placeholder="结算日期" clearable style="width: 150px" />
        <el-select v-model="fileQuery.fileType" placeholder="文件类型" clearable style="width: 140px">
          <el-option v-for="ft in FILE_TYPES" :key="ft" :label="ft" :value="ft" />
        </el-select>
        <el-input v-model="fileQuery.fileNo" placeholder="文件编号" clearable style="width: 220px" />
        <el-button type="primary" @click="loadFileStats">查询</el-button>
      </div>
      <el-table v-loading="fileLoading" :data="fileStats" empty-text="暂无数据" style="width: 100%">
        <el-table-column label="文件编号" min-width="200">
          <template #default="{ row }"><span class="font-mono amber">{{ row.fileNo }}</span></template>
        </el-table-column>
        <el-table-column label="成功" width="100">
          <template #default="{ row }"><span class="font-mono" style="color: var(--sage)">{{ row.successCount }}</span></template>
        </el-table-column>
        <el-table-column label="失败" width="100">
          <template #default="{ row }"><span class="font-mono" style="color: var(--sienna)">{{ row.failedCount }}</span></template>
        </el-table-column>
        <el-table-column label="最后成功时间" width="180">
          <template #default="{ row }"><span class="font-mono dim">{{ formatDateTime(row.lastSuccessTime) }}</span></template>
        </el-table-column>
        <el-table-column label="最后下载 IP" width="150">
          <template #default="{ row }"><span class="font-mono dim">{{ row.lastClientIp || '—' }}</span></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- member stats -->
    <div v-show="activeTab === 'member'" class="panel">
      <div class="filter">
        <el-input v-model="memberQuery.memberId" placeholder="会员编号" clearable style="width: 180px" />
        <el-date-picker v-model="memberQuery.range" type="daterange" range-separator="—" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" style="width: 260px" />
        <el-button type="primary" @click="loadMemberStats">查询</el-button>
      </div>
      <el-table v-loading="memberLoading" :data="memberStats" empty-text="暂无数据" style="width: 100%">
        <el-table-column label="会员编号" min-width="160">
          <template #default="{ row }"><span class="font-mono amber">{{ row.memberId }}</span></template>
        </el-table-column>
        <el-table-column label="总次数" width="100">
          <template #default="{ row }"><span class="font-mono">{{ row.totalCount }}</span></template>
        </el-table-column>
        <el-table-column label="成功" width="100">
          <template #default="{ row }"><span class="font-mono" style="color: var(--sage)">{{ row.successCount }}</span></template>
        </el-table-column>
        <el-table-column label="失败" width="100">
          <template #default="{ row }"><span class="font-mono" style="color: var(--sienna)">{{ row.failedCount }}</span></template>
        </el-table-column>
        <el-table-column label="最后下载时间" width="180">
          <template #default="{ row }"><span class="font-mono dim">{{ formatDateTime(row.lastDownloadTime) }}</span></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- abnormal stats -->
    <div v-show="activeTab === 'abnormal'">
      <div class="panel filter">
        <span class="filter-label font-mono">异常时间范围</span>
        <el-date-picker v-model="abnormalRange" type="daterange" range-separator="—" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" style="width: 260px" />
        <el-button type="primary" @click="loadAbnormal().then(refreshAbnormalCards)">查询</el-button>
      </div>

      <div v-loading="abnormalLoading" class="metric-grid">
        <div v-for="m in abnormalMetrics" :key="m.label" class="metric">
          <span class="metric-label">{{ m.label }}</span>
          <span class="metric-val font-mono" :style="{ color: m.tone }">{{ m.value }}</span>
        </div>
      </div>

      <div class="rank-cols" v-if="abnormal">
        <div class="panel rank-card">
          <div class="rank-title font-mono">TOP IP · 异常</div>
          <div v-if="abnormal.topIps.length" class="rank-list">
            <div v-for="(item, i) in abnormal.topIps" :key="i" class="rank-row">
              <span class="rank-pos font-mono">{{ String(i + 1).padStart(2, '0') }}</span>
              <span class="font-mono rank-name">{{ item.name }}</span>
              <div class="rank-bar">
                <div class="rank-bar-fill" :style="{ width: barWidth(item.count, abnormal.topIps[0]?.count ?? 0) }" />
              </div>
              <span class="font-mono rank-count">{{ item.count }}</span>
            </div>
          </div>
          <div v-else class="rank-empty dim">暂无异常 IP</div>
        </div>
        <div class="panel rank-card">
          <div class="rank-title font-mono">TOP 会员 · 异常</div>
          <div v-if="abnormal.topMembers.length" class="rank-list">
            <div v-for="(item, i) in abnormal.topMembers" :key="i" class="rank-row">
              <span class="rank-pos font-mono">{{ String(i + 1).padStart(2, '0') }}</span>
              <span class="font-mono rank-name">{{ item.name }}</span>
              <div class="rank-bar">
                <div class="rank-bar-fill" :style="{ width: barWidth(item.count, abnormal.topMembers[0]?.count ?? 0) }" />
              </div>
              <span class="font-mono rank-count">{{ item.count }}</span>
            </div>
          </div>
          <div v-else class="rank-empty dim">暂无异常会员</div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page {
  max-width: 1320px;
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
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.tab {
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--fg-muted);
  padding: 8px 18px;
  border-radius: var(--radius);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.tab:hover {
  color: var(--cream);
}
.tab.active {
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
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.filter-label {
  color: var(--fg-dim);
  font-size: 11px;
  letter-spacing: 0.1em;
}
.amber {
  color: var(--amber);
}
.dim {
  color: var(--fg-dim);
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.metric {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  position: relative;
  overflow: hidden;
}
.metric::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--line-strong);
}
.metric-label {
  color: var(--fg-muted);
  font-size: 12px;
}
.metric-val {
  font-size: 30px;
  font-weight: 600;
}
.rank-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.rank-card {
  padding: 18px 20px;
}
.rank-title {
  color: var(--fg-dim);
  font-size: 11px;
  letter-spacing: 0.14em;
  margin-bottom: 16px;
}
.rank-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.rank-row {
  display: grid;
  grid-template-columns: 28px 130px 1fr 50px;
  align-items: center;
  gap: 12px;
}
.rank-pos {
  color: var(--amber);
  font-size: 12px;
}
.rank-name {
  color: var(--cream);
  font-size: 12.5px;
  word-break: break-all;
}
.rank-bar {
  height: 6px;
  background: var(--ink);
  border-radius: 3px;
  overflow: hidden;
}
.rank-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--amber), var(--amber-line));
  border-radius: 3px;
  transition: width 0.4s ease;
}
.rank-count {
  color: var(--fg);
  font-size: 13px;
  text-align: right;
}
.rank-empty {
  font-size: 12px;
  padding: 8px 0;
}
</style>
