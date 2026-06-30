<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { taskApi } from '@/api/task'
import { fileApi } from '@/api/file'
import { statsApi } from '@/api/audit'
import { todayIso } from '@/utils/format'
import type { AbnormalDownloadStatsDTO, FileDownloadStatsDTO, FileVO, TaskVO } from '@/types/models'

const router = useRouter()
const loading = ref(true)

const kpis = ref({
  taskTotal: 0,
  taskFailed: 0,
  fileTotal: 0,
  filePublished: 0,
  filePending: 0,
  dlSuccess: 0,
  dlFailed: 0,
  abnormal: 0,
})

async function safe<T>(p: Promise<T>, fallback: T): Promise<T> {
  try {
    return await p
  } catch {
    return fallback
  }
}

async function load() {
  loading.value = true
  const day = todayIso()

  const [tasks, files, fileStats, abnormal] = await Promise.all([
    safe(taskApi.list({ settleDate: day }), [] as TaskVO[]),
    safe(fileApi.list({ settleDate: day }), [] as FileVO[]),
    safe(statsApi.fileDownload({ settleDate: day }), [] as FileDownloadStatsDTO[]),
    safe(statsApi.abnormal({}), { tokenInvalidCount: 0, tokenExpiredCount: 0, deniedCount: 0, limitedCount: 0, fileNotPublishedCount: 0, fileRevokedCount: 0, fileReissuedCount: 0, topIps: [], topMembers: [] } as AbnormalDownloadStatsDTO),
  ])

  kpis.value.taskTotal = tasks.length
  kpis.value.taskFailed = tasks.filter((t) => ['FAILED', 'SEND_FAILED'].includes(t.status)).length

  kpis.value.fileTotal = files.length
  kpis.value.filePublished = files.filter((f) => f.status === 'PUBLISHED').length
  kpis.value.filePending = files.filter((f) => f.status === 'GENERATED').length

  kpis.value.dlSuccess = fileStats.reduce((s, f) => s + (f.successCount || 0), 0)
  kpis.value.dlFailed = fileStats.reduce((s, f) => s + (f.failedCount || 0), 0)

  const a = abnormal as AbnormalDownloadStatsDTO
  kpis.value.abnormal =
    a.tokenInvalidCount + a.tokenExpiredCount + a.deniedCount + a.limitedCount +
    a.fileNotPublishedCount + a.fileRevokedCount + a.fileReissuedCount

  loading.value = false
}

onMounted(load)

const cards = [
  { key: 'taskTotal', label: '今日任务', sub: 'TODAY TASKS', to: 'tasks', tone: 'var(--cream)' },
  { key: 'taskFailed', label: '任务失败', sub: 'FAILED', to: 'tasks', tone: 'var(--sienna)' },
  { key: 'filePending', label: '待发布文件', sub: 'PENDING PUBLISH', to: 'files', tone: '#c89b3a' },
  { key: 'filePublished', label: '已发布文件', sub: 'PUBLISHED', to: 'files', tone: 'var(--sage)' },
  { key: 'dlSuccess', label: '下载成功', sub: 'DL SUCCESS', to: 'stats', tone: 'var(--sage)' },
  { key: 'dlFailed', label: '下载失败', sub: 'DL FAILED', to: 'stats', tone: 'var(--sienna)' },
  { key: 'abnormal', label: '异常下载', sub: 'ABNORMAL', to: 'audits', tone: 'var(--sienna)' },
  { key: 'fileTotal', label: '今日文件', sub: 'TODAY FILES', to: 'files', tone: 'var(--cream)' },
] as const

const shortcuts = [
  { label: '创建任务', to: 'tasks', icon: 'Tickets' },
  { label: '发布文件', to: 'publish', icon: 'Promotion' },
  { label: '下载中心', to: 'download', icon: 'Download' },
  { label: '异常统计', to: 'stats', icon: 'TrendCharts' },
]
</script>

<template>
  <section class="page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="overline font-mono">01 · DASHBOARD</span>
        <h1>清算台概览</h1>
        <p class="lede">今日任务、文件、下载与异常的一屏总览。点击卡片可跳转对应模块。</p>
      </div>
      <el-button text @click="load">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </header>

    <div class="grid">
      <article
        v-for="c in cards"
        :key="c.key"
        class="card"
        @click="router.push({ name: c.to })"
      >
        <span class="card-sub font-mono">{{ c.sub }}</span>
        <span class="card-val font-mono" :style="{ color: c.tone }">{{ (kpis as Record<string, number>)[c.key] }}</span>
        <span class="card-label">{{ c.label }}</span>
        <el-icon class="card-arrow"><ArrowRight /></el-icon>
      </article>
    </div>

    <div class="shortcuts">
      <span class="sc-title font-mono">QUICK ACCESS</span>
      <div class="sc-grid">
        <button
          v-for="s in shortcuts"
          :key="s.label"
          class="sc-btn"
          @click="router.push({ name: s.to })"
        >
          <el-icon><component :is="s.icon" /></el-icon>
          <span>{{ s.label }}</span>
          <el-icon class="sc-arrow"><ArrowRight /></el-icon>
        </button>
      </div>
    </div>
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
  font-size: 32px;
  color: var(--cream);
  margin: 8px 0 6px;
  letter-spacing: -0.01em;
}
.lede {
  color: var(--fg-muted);
  max-width: 560px;
  margin: 0;
}
.grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 28px;
}
.card {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  cursor: pointer;
  transition: border-color 0.15s, transform 0.15s, background 0.15s;
  overflow: hidden;
}
.card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--line-strong);
  transition: background 0.15s;
}
.card:hover {
  border-color: var(--amber-line);
  background: var(--surface-2);
  transform: translateY(-2px);
}
.card:hover::before {
  background: var(--amber);
}
.card-sub {
  color: var(--fg-dim);
  font-size: 10px;
  letter-spacing: 0.14em;
}
.card-val {
  font-size: 38px;
  font-weight: 600;
  line-height: 1.1;
}
.card-label {
  color: var(--fg-muted);
  font-size: 12.5px;
}
.card-arrow {
  position: absolute;
  right: 16px;
  top: 18px;
  color: var(--fg-dim);
  opacity: 0;
  transition: opacity 0.15s, color 0.15s;
}
.card:hover .card-arrow {
  opacity: 1;
  color: var(--amber);
}
.shortcuts {
  margin-top: 4px;
}
.sc-title {
  color: var(--fg-dim);
  font-size: 11px;
  letter-spacing: 0.14em;
  margin-bottom: 14px;
  display: block;
}
.sc-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.sc-btn {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 18px 18px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--fg);
  font-family: var(--font-ui);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.sc-btn:hover {
  border-color: var(--amber-line);
  color: var(--amber);
  background: var(--surface-2);
}
.sc-btn .el-icon {
  font-size: 18px;
}
.sc-arrow {
  margin-left: auto;
  opacity: 0.4;
}
.sc-btn:hover .sc-arrow {
  opacity: 1;
}
</style>
