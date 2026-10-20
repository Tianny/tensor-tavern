<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import { useRouter } from 'vue-router'

import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifySuccess } from '@/utils/notify'
import { listModelConfigs } from '@/api/modelConfig'
import { listProviders } from '@/api/provider'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  listKnowledgeBases,
  updateKnowledgeBase,
  type KnowledgeBase,
} from '@/api/knowledge'
import type { HifyFormDialogDone, HifyTableColumn } from '@/types/components'

const router = useRouter()

// ---------------- 列表 ----------------

const columns: HifyTableColumn[] = [
  { label: '名称', prop: 'name', minWidth: 160, showOverflowTooltip: true },
  { label: '描述', prop: 'description', minWidth: 200, showOverflowTooltip: true },
  { label: '向量化模型', slot: 'model', minWidth: 200 },
  { label: '创建时间', slot: 'createdAt', width: 160 },
  { label: '操作', slot: 'actions', width: 200, fixed: 'right' },
]

const tableRef = ref()

/** 后端知识库不分页（数量级小），包一层适配 HifyTable 的分页协议 */
async function fetchKbs() {
  const list = await listKnowledgeBases()
  return { list, total: list.length, page: 1, pageSize: list.length }
}

function formatDateTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

// ---------------- embedding 模型下拉（启用 provider 下启用 embedding 模型，按 provider 分组） ----------------

interface ModelOption {
  modelConfigId: number
  label: string
}

interface ModelGroup {
  providerId: number
  label: string
  options: ModelOption[]
}

const modelGroups = ref<ModelGroup[]>([])
const modelsLoading = ref(false)

async function loadModelOptions() {
  modelsLoading.value = true
  try {
    const [provRes, modelRes] = await Promise.all([
      listProviders({ page: 1, pageSize: 100 }),
      listModelConfigs({ page: 1, pageSize: 100, modelType: 'embedding' }),
    ])
    const enabledModels = modelRes.list.filter((m) => m.enabled === 1)
    modelGroups.value = provRes.list
      .filter((p) => p.enabled === 1)
      .map((p) => ({
        providerId: p.id,
        label: p.name,
        options: enabledModels
          .filter((m) => m.providerId === p.id)
          .map((m) => ({ modelConfigId: m.id, label: m.displayName || m.modelName })),
      }))
      .filter((g) => g.options.length > 0)
  } finally {
    modelsLoading.value = false
  }
}

onMounted(loadModelOptions)

// ---------------- 新增 / 编辑弹窗 ----------------

const dialogVisible = ref(false)
const dialogRef = ref()
const editingRow = ref<KnowledgeBase | null>(null)   // null=新增

const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 64, message: '名称不能超过 64 个字符', trigger: 'blur' },
  ],
  embeddingModelId: [{ required: true, message: '请选择向量化模型', trigger: 'change' }],
}

function openCreate() {
  editingRow.value = null
  dialogRef.value?.open({ name: '', description: '', embeddingModelId: undefined })
}

function openEdit(row: KnowledgeBase) {
  editingRow.value = row
  dialogRef.value?.open({ ...row })
}

async function handleSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  try {
    if (editingRow.value) {
      // embedding 模型建库后不可换（向量维度与模型绑定）
      await updateKnowledgeBase(editingRow.value.id, {
        name: data.name as string,
        description: (data.description ?? '') as string,
      })
      notifySuccess('保存成功')
    } else {
      await createKnowledgeBase(data as never)
      notifySuccess('创建成功')
    }
    done()
    tableRef.value?.refresh(true)
  } catch {
    done(false)   // 失败保留弹窗（错误提示已由拦截器弹出）
  }
}

// ---------------- 删除 / 进入详情 ----------------

async function handleDelete(row: KnowledgeBase) {
  await useConfirm(
    `确认删除知识库「${row.name}」？已向量化的文档、分块和 Agent 绑定将一并清理。`,
    () => deleteKnowledgeBase(row.id),
  )
  tableRef.value?.refresh()
}

function openDetail(row: KnowledgeBase) {
  router.push(`/knowledge/${row.id}`)
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">知识库</h1>
        <p class="page-desc">上传文档自动分块向量化（txt / md），Agent 绑定后对话时检索引用</p>
      </div>
      <el-button type="primary" class="page-create-btn" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新增知识库
      </el-button>
    </div>

    <div class="page-card">
      <HifyTable
        ref="tableRef"
        :columns="columns"
        :api="fetchKbs"
        :show-pagination="false"
        empty-text="暂无知识库，点击右上角「新增知识库」"
      >
        <template #model="{ row }">
          <template v-if="!row.embeddingModelName">
            <el-tag type="danger" effect="light" disable-transitions>模型缺失</el-tag>
          </template>
          <div v-else class="model-cell">
            <span class="model-name">{{ row.embeddingModelName }}</span>
            <span class="model-provider">{{ row.embeddingProviderName }}</span>
          </div>
        </template>
        <template #createdAt="{ row }">
          <span class="page-time">{{ formatDateTime(row.createdAt) }}</span>
        </template>
        <template #actions="{ row }">
          <el-button link type="primary" @click="openDetail(row)">文档管理</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </HifyTable>
    </div>

    <HifyFormDialog
      ref="dialogRef"
      v-model="dialogVisible"
      :title="editingRow ? '编辑知识库' : '新增知识库'"
      width="560px"
      :rules="rules"
      @submit="handleSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="名称" prop="name">
          <el-input v-model="model.name" placeholder="如 产品使用手册" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="model.description" placeholder="一句话说明用途，可选" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="向量化模型" prop="embeddingModelId">
          <!-- 建库后不可换模型：编辑时禁用 -->
          <el-select
            v-model="model.embeddingModelId"
            placeholder="选择启用的 embedding 模型"
            :loading="modelsLoading"
            :disabled="!!editingRow"
            style="width: 100%"
          >
            <el-option-group v-for="group in modelGroups" :key="group.providerId" :label="group.label">
              <el-option
                v-for="opt in group.options"
                :key="opt.modelConfigId"
                :label="opt.label"
                :value="opt.modelConfigId"
              />
            </el-option-group>
            <template #footer>
              <div class="model-empty">
                {{ modelGroups.length ? '' : '暂无可用模型：先在「模型管理」里启用 embedding 模型（如 text-embedding-v3）' }}
              </div>
            </template>
          </el-select>
          <div v-if="editingRow" class="model-lock-tip">模型在创建后不可更换（向量维度与模型绑定），如需更换请新建知识库</div>
        </el-form-item>
      </template>
    </HifyFormDialog>
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
  margin-bottom: var(--hify-space-5);
}

.page-title {
  margin: 0;
  font-size: var(--hify-text-xl);
  font-weight: var(--hify-weight-semibold);
  color: var(--hify-text-1);
}

.page-desc {
  margin: var(--hify-space-1) 0 0;
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
}

:deep(.page-create-btn.el-button--primary:hover),
.page-create-btn.el-button--primary:hover {
  box-shadow: var(--hify-shadow-primary);
}

.page-card {
  background: var(--hify-bg-surface);
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-lg);
  padding: var(--hify-space-5);
  box-shadow: var(--hify-shadow-xs);
}

.page-time {
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
}

.model-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.model-name {
  font-size: var(--hify-text-sm);
  color: var(--hify-text-1);
}

.model-provider {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.model-empty {
  padding: var(--hify-space-2) var(--hify-space-3);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.model-lock-tip {
  width: 100%;
  margin-top: var(--hify-space-1);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}
</style>
