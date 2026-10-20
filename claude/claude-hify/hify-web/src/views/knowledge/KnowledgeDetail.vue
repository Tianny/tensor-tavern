<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'

import { useConfirm } from '@/composables/useConfirm'
import { notifyError, notifySuccess } from '@/utils/notify'
import {
  deleteDocument,
  listChunks,
  listDocuments,
  listKnowledgeBases,
  retryDocument,
  searchKnowledgeBase,
  uploadDocument,
  type Chunk,
  type KnowledgeBase,
  type KnowledgeDocument,
  type RetrievalHit,
} from '@/api/knowledge'

const route = useRoute()
const router = useRouter()

const kbId = Number(route.params.id)
const kb = ref<KnowledgeBase | null>(null)

// ---------------- 文档列表（pending/processing 时轮询直到稳定） ----------------

const documents = ref<KnowledgeDocument[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

const hasProcessing = computed(() =>
  documents.value.some((d) => d.status === 'pending' || d.status === 'processing'),
)

async function loadDocuments() {
  documents.value = await listDocuments(kbId)
  schedulePoll()
}

/** 有进行中的文档时 2s 轮询（不用 SSE：状态机简单、秒级不需要实时推送） */
function schedulePoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  if (hasProcessing.value) {
    pollTimer = setTimeout(loadDocuments, 2000)
  }
}

onBeforeUnmount(() => {
  if (pollTimer) {
    clearTimeout(pollTimer)
  }
})

async function handleUpload(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''   // 同名文件可重复上传
  if (!file) {
    return
  }
  const ext = file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase()
  if (ext !== 'txt' && ext !== 'md') {
    notifyError('仅支持 txt / md 纯文本文件')
    return
  }
  uploading.value = true
  try {
    await uploadDocument(kbId, file)
    notifySuccess('已上传，正在向量化')
    await loadDocuments()
  } finally {
    uploading.value = false
  }
}

async function handleDeleteDoc(row: KnowledgeDocument) {
  await useConfirm(`确认删除文档「${row.fileName}」？已向量化的分块将一并清理。`, () =>
    deleteDocument(row.id),
  )
  loadDocuments()
}

async function handleRetry(row: KnowledgeDocument) {
  await retryDocument(row.id)
  notifySuccess('已重新开始向量化')
  loadDocuments()
}

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatDateTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

function statusTag(status: string): { type: 'success' | 'warning' | 'danger' | 'info'; label: string } {
  switch (status) {
    case 'completed':
      return { type: 'success', label: '可用' }
    case 'failed':
      return { type: 'danger', label: '失败' }
    case 'processing':
      return { type: 'warning', label: '向量化中' }
    default:
      return { type: 'info', label: '排队中' }
  }
}

// ---------------- 分块预览 ----------------

const chunkDrawer = ref(false)
const chunkDoc = ref<KnowledgeDocument | null>(null)
const chunks = ref<Chunk[]>([])
const chunksLoading = ref(false)

async function openChunks(row: KnowledgeDocument) {
  chunkDoc.value = row
  chunkDrawer.value = true
  chunksLoading.value = true
  try {
    chunks.value = await listChunks(row.id, { offset: 0, limit: 50 })
  } finally {
    chunksLoading.value = false
  }
}

// ---------------- 检索测试 ----------------

const retrievalQuery = ref('')
const retrievalTopK = ref(4)
const retrievalHits = ref<RetrievalHit[] | null>(null)
const retrievalLoading = ref(false)

async function handleSearch() {
  if (!retrievalQuery.value.trim()) {
    notifyError('请输入测试问题')
    return
  }
  retrievalLoading.value = true
  try {
    retrievalHits.value = await searchKnowledgeBase(kbId, {
      query: retrievalQuery.value.trim(),
      topK: retrievalTopK.value,
    })
  } finally {
    retrievalLoading.value = false
  }
}

// ---------------- 初始化 ----------------

onMounted(async () => {
  const kbs = await listKnowledgeBases()
  kb.value = kbs.find((k) => k.id === kbId) ?? null
  if (!kb.value) {
    notifyError('知识库不存在或已删除')
    router.replace('/knowledge')
    return
  }
  await loadDocuments()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="page-header-left">
        <el-button link class="back-btn" @click="router.push('/knowledge')">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <div>
          <h1 class="page-title">{{ kb?.name ?? '…' }}</h1>
          <p class="page-desc">
            {{ kb?.description || '暂无描述' }}
            <span v-if="kb" class="page-desc-model">· 向量化模型 {{ kb.embeddingModelName || '缺失' }}</span>
          </p>
        </div>
      </div>
      <el-button
        type="primary"
        class="page-create-btn"
        :loading="uploading"
        :disabled="hasProcessing"
        :title="hasProcessing ? '有文档正在向量化，稍候再上传' : ''"
        @click="fileInput?.click()"
      >
        <el-icon><Plus /></el-icon>
        上传文档
      </el-button>
      <input
        ref="fileInput"
        type="file"
        accept=".txt,.md"
        class="hidden-file-input"
        @change="handleUpload"
      />
    </div>

    <div class="detail-grid">
      <div class="page-card">
        <h2 class="card-title">文档（{{ documents.length }}）</h2>
        <el-table :data="documents" v-loading="documents.length === 0 && !hasProcessing" empty-text="暂无文档，点击右上角「上传文档」">
          <el-table-column label="文件名" prop="fileName" min-width="200" show-overflow-tooltip />
          <el-table-column label="大小" width="100">
            <template #default="{ row }">
              <span class="page-time">{{ formatSize(row.fileSize) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="分块" width="80">
            <template #default="{ row }">
              <el-link v-if="row.status === 'completed'" type="primary" :underline="false" @click="openChunks(row)">
                {{ row.chunkCount }}
              </el-link>
              <span v-else class="page-time">{{ row.chunkCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tooltip
                v-if="row.status === 'failed' && row.errorMsg"
                :content="row.errorMsg"
                placement="top"
              >
                <el-tag :type="statusTag(row.status).type" effect="light" disable-transitions>
                  {{ statusTag(row.status).label }}
                </el-tag>
              </el-tooltip>
              <el-tag v-else :type="statusTag(row.status).type" effect="light" disable-transitions>
                {{ statusTag(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="上传时间" width="160">
            <template #default="{ row }">
              <span class="page-time">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'failed'"
                link
                type="primary"
                @click="handleRetry(row)"
              >重试</el-button>
              <el-button
                v-if="row.status === 'completed'"
                link
                type="primary"
                @click="openChunks(row)"
              >分块</el-button>
              <el-button link type="danger" @click="handleDeleteDoc(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="page-card">
        <h2 class="card-title">检索测试</h2>
        <p class="card-desc">输入问题验证召回效果（与 Agent 对话时的注入逻辑一致：余弦相似度 top-k + 阈值过滤）</p>
        <div class="search-row">
          <el-input
            v-model="retrievalQuery"
            placeholder="如：部署需要注意什么？"
            maxlength="2000"
            clearable
            @keyup.enter="handleSearch"
          />
          <el-input-number v-model="retrievalTopK" :min="1" :max="10" controls-position="right" />
          <el-button type="primary" :loading="retrievalLoading" @click="handleSearch">检索</el-button>
        </div>
        <div v-if="retrievalHits" class="hit-list">
          <div v-if="retrievalHits.length === 0" class="hit-empty">无命中（相似度低于阈值）</div>
          <div v-for="hit in retrievalHits" :key="hit.rank" class="hit-item">
            <div class="hit-meta">
              <el-tag size="small" effect="plain" disable-transitions>#{{ hit.rank }}</el-tag>
              <span class="hit-doc">{{ hit.documentName }}</span>
              <span class="hit-score">{{ (hit.score * 100).toFixed(1) }}%</span>
            </div>
            <div class="hit-content">{{ hit.content }}</div>
          </div>
        </div>
      </div>
    </div>

    <el-drawer v-model="chunkDrawer" :title="`分块预览 · ${chunkDoc?.fileName ?? ''}`" size="520px">
      <div v-loading="chunksLoading" class="chunk-list">
        <div v-if="!chunksLoading && chunks.length === 0" class="hit-empty">该文档暂无分块</div>
        <div v-for="c in chunks" :key="c.chunkIndex" class="chunk-item">
          <div class="chunk-meta">
            <span class="chunk-index">块 #{{ c.chunkIndex }}</span>
            <span class="chunk-tokens">≈ {{ c.tokenCount }} tokens</span>
          </div>
          <div class="chunk-content">{{ c.content }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  padding: var(--hify-space-6);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--hify-space-4);
  margin-bottom: var(--hify-space-5);
}

.page-header-left {
  display: flex;
  align-items: center;
  gap: var(--hify-space-3);
  min-width: 0;
}

.back-btn {
  font-size: var(--hify-text-sm);
}

.page-title {
  margin: 0;
  font-size: var(--hify-text-xl);
  font-weight: var(--hify-weight-semibold);
  color: var(--hify-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-desc {
  margin: var(--hify-space-1) 0 0;
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
}

.page-desc-model {
  color: var(--hify-text-3);
}

:deep(.page-create-btn.el-button--primary:hover),
.page-create-btn.el-button--primary:hover {
  box-shadow: var(--hify-shadow-primary);
}

.hidden-file-input {
  display: none;
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(300px, 2fr);
  gap: var(--hify-space-5);
  align-items: start;
}

.page-card {
  background: var(--hify-bg-surface);
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-lg);
  padding: var(--hify-space-5);
  box-shadow: var(--hify-shadow-xs);
}

.card-title {
  margin: 0 0 var(--hify-space-4);
  font-size: var(--hify-text-base);
  font-weight: var(--hify-weight-semibold);
  color: var(--hify-text-1);
}

.card-desc {
  margin: 0 0 var(--hify-space-4);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  line-height: 1.6;
}

.page-time {
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
}

/* 检索测试 */
.search-row {
  display: flex;
  gap: var(--hify-space-2);
  margin-bottom: var(--hify-space-4);
}

.search-row .el-input-number {
  width: 90px;
}

.hit-list {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-3);
}

.hit-empty,
.chunk-list .hit-empty {
  padding: var(--hify-space-4);
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
  text-align: center;
}

.hit-item,
.chunk-item {
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-md);
  padding: var(--hify-space-3);
  background: var(--hify-bg-subtle);
}

.hit-meta,
.chunk-meta {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
  margin-bottom: var(--hify-space-2);
}

.hit-doc {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hit-score {
  margin-left: auto;
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-xs);
  color: var(--hify-color-primary);
}

.hit-content,
.chunk-content {
  font-size: var(--hify-text-sm);
  color: var(--hify-text-2);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow-y: auto;
}

.chunk-index {
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-1);
}

.chunk-tokens {
  margin-left: auto;
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.chunk-list {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-3);
}
</style>
