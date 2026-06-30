<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { downloadApi, downloadUrl } from '@/api/download'
import { fileApi } from '@/api/file'
import { formatBytes } from '@/utils/format'
import StatusTag from '@/components/StatusTag.vue'
import { fileStatus } from '@/utils/enums'
import type { FileVO } from '@/types/models'

const route = useRoute()

const form = reactive({
  memberId: '',
  fileNo: '',
})
const tokenLoading = ref(false)
const token = ref('')
const expireSeconds = ref(0)
const file = ref<FileVO | null>(null)
const fileLoading = ref(false)

onMounted(() => {
  const q = route.query
  if (q.fileNo) form.fileNo = String(q.fileNo)
  if (q.memberId) form.memberId = String(q.memberId)
})

async function lookupFile() {
  if (!form.fileNo) return
  fileLoading.value = true
  try {
    file.value = await fileApi.detail(form.fileNo)
  } catch {
    file.value = null
  } finally {
    fileLoading.value = false
  }
}

async function mintToken() {
  if (!form.memberId.trim()) {
    ElMessage.warning('请填写会员编号')
    return
  }
  if (!form.fileNo.trim()) {
    ElMessage.warning('请填写文件编号')
    return
  }
  tokenLoading.value = true
  token.value = ''
  try {
    const r = await downloadApi.createToken(form.fileNo.trim(), form.memberId.trim())
    token.value = r.token
    expireSeconds.value = r.expireSeconds
    ElMessage.success('Token 已签发')
    if (!file.value) await lookupFile()
  } finally {
    tokenLoading.value = false
  }
}

function triggerDownload() {
  if (!token.value || !form.fileNo) return
  // stream endpoint — use a hidden anchor so the browser handles the binary response natively
  const a = document.createElement('a')
  a.href = downloadUrl(form.fileNo, token.value)
  a.download = file.value?.fileName || ''
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function openRaw() {
  if (!token.value || !form.fileNo) return
  window.open(downloadUrl(form.fileNo, token.value), '_blank')
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(token.value)
    ElMessage.success('Token 已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选取')
  }
}
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <span class="overline font-mono">05 · DOWNLOAD</span>
        <h1>下载中心</h1>
        <p class="lede">签发下载 Token 并触发文件下载，用于联调与排查下载链路及审计落库。</p>
      </div>
    </header>

    <div class="cols">
      <!-- token mint -->
      <div class="panel">
        <div class="card-head">
          <span class="card-title">签发 Token</span>
          <span class="card-sub font-mono">POST /api/download/token</span>
        </div>
        <div class="body">
          <el-form label-position="top" class="form">
            <el-form-item label="会员编号（X-Member-Id）" required>
              <el-input v-model="form.memberId" placeholder="如 0001" />
            </el-form-item>
            <el-form-item label="文件编号" required>
              <el-input v-model="form.fileNo" placeholder="如 FILE202606260001" @change="lookupFile" />
            </el-form-item>
          </el-form>
          <el-button type="primary" :loading="tokenLoading" class="full-btn" @click="mintToken">
            <el-icon style="margin-right: 6px"><Key /></el-icon>签发 Token
          </el-button>
        </div>
      </div>

      <!-- result + file info -->
      <div class="panel">
        <div class="card-head">
          <span class="card-title">下载</span>
          <span class="card-sub font-mono">GET /api/download/files/{fileNo}?token=</span>
        </div>
        <div class="body">
          <div v-if="file" v-loading="fileLoading" class="file-info">
            <div class="fi-row">
              <StatusTag :meta="fileStatus(file.status)" />
              <span class="font-mono dim">v{{ file.version }} · {{ file.fileType }}</span>
            </div>
            <div class="fi-name">{{ file.fileName }}</div>
            <div class="fi-meta font-mono">
              <span>{{ file.memberId }}</span>
              <span class="dim">·</span>
              <span>{{ formatBytes(file.fileSize) }}</span>
              <span class="dim">·</span>
              <span>{{ file.settleDate }}</span>
            </div>
            <div v-if="file.status !== 'PUBLISHED'" class="fi-warn">
              文件当前状态为 {{ file.status }}，可能无法正常下载。
            </div>
          </div>

          <div v-if="token" class="token-box">
            <div class="token-label font-mono">DOWNLOAD TOKEN</div>
            <div class="token-value font-mono">{{ token }}</div>
            <div class="token-foot">
              <span class="dim font-mono">有效期 {{ expireSeconds }}s</span>
              <el-button text @click="copyToken">复制</el-button>
            </div>
          </div>

          <div class="actions">
            <el-button
              type="primary"
              :disabled="!token"
              @click="triggerDownload"
            >
              <el-icon style="margin-right: 6px"><Download /></el-icon>下载文件
            </el-button>
            <el-button :disabled="!token" @click="openRaw">新标签打开（查看错误）</el-button>
          </div>

          <div v-if="!token" class="empty-hint">
            签发 Token 后即可触发下载。若下载失败，可用「新标签打开」查看返回的错误 JSON。
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page {
  max-width: 1180px;
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
  grid-template-columns: 1fr 1fr;
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
.body {
  padding: 18px 20px 20px;
}
.form :deep(.el-form-item) {
  margin-bottom: 18px;
}
.full-btn {
  width: 100%;
}
.file-info {
  padding: 14px 16px;
  background: var(--ink);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  margin-bottom: 16px;
}
.fi-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 11px;
}
.fi-name {
  color: var(--cream);
  font-size: 14px;
  margin-bottom: 6px;
  word-break: break-all;
}
.fi-meta {
  font-size: 12px;
  color: var(--fg-muted);
  display: flex;
  gap: 8px;
}
.fi-warn {
  margin-top: 10px;
  color: var(--sienna);
  font-size: 12px;
}
.token-box {
  padding: 14px 16px;
  background: var(--ink);
  border: 1px solid var(--amber-line);
  border-radius: var(--radius);
  margin-bottom: 16px;
}
.token-label {
  color: var(--amber);
  font-size: 10px;
  letter-spacing: 0.14em;
  margin-bottom: 8px;
}
.token-value {
  color: var(--cream);
  font-size: 12px;
  word-break: break-all;
  line-height: 1.6;
}
.token-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  font-size: 11px;
}
.actions {
  display: flex;
  gap: 10px;
}
.empty-hint {
  margin-top: 18px;
  color: var(--fg-dim);
  font-size: 12px;
  line-height: 1.7;
}
.dim {
  color: var(--fg-dim);
}
</style>
