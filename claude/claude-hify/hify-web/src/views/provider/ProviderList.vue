<script setup lang="ts">
import { ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'

import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifySuccess, notifyWarning } from '@/utils/notify'
import ModelManageDialog from './ModelManageDialog.vue'
import {
  createProvider,
  deleteProvider,
  listProviders,
  testProviderConnection,
  updateProvider,
  type Provider,
  type ProviderSaveReq,
  type ProviderTypeCode,
} from '@/api/provider'
import type { HifyFormDialogDone, HifyTableColumn } from '@/types/components'

// 类型选项与标签配色（openai_compatible 与 openai 同色系、plain 区分官方/第三方）
const PROVIDER_TYPES: { code: ProviderTypeCode; label: string }[] = [
  { code: 'openai', label: 'OpenAI' },
  { code: 'openai_compatible', label: 'OpenAI 兼容' },
  { code: 'claude', label: 'Claude' },
  { code: 'gemini', label: 'Gemini' },
  { code: 'ollama', label: 'Ollama' },
]
const TYPE_LABEL: Record<ProviderTypeCode, string> = Object.fromEntries(
  PROVIDER_TYPES.map((t) => [t.code, t.label]),
)
const TYPE_TAG: Record<ProviderTypeCode, { type: 'primary' | 'success' | 'info' | 'warning'; effect: 'light' | 'plain' }> = {
  openai: { type: 'primary', effect: 'light' },
  openai_compatible: { type: 'primary', effect: 'plain' },
  claude: { type: 'warning', effect: 'light' },
  gemini: { type: 'success', effect: 'light' },
  ollama: { type: 'info', effect: 'light' },
}
/** 表单里按类型提示 Base URL（留空=官方默认；兼容类型必填） */
const BASE_URL_PLACEHOLDER: Record<ProviderTypeCode, string> = {
  openai: '留空用官方 https://api.openai.com/v1',
  openai_compatible: '必填，如 https://api.deepseek.com/v1',
  claude: '留空用官方 https://api.anthropic.com',
  gemini: '留空用官方 https://generativelanguage.googleapis.com',
  ollama: '留空用本机 http://localhost:11434',
}

// ---------------- 列表 ----------------

const columns: HifyTableColumn[] = [
  { label: '名称', prop: 'name', minWidth: 140, showOverflowTooltip: true },
  { label: '类型', slot: 'type', width: 120 },
  { label: 'Base URL', prop: 'baseUrl', minWidth: 220, showOverflowTooltip: true },
  { label: '状态', slot: 'enabled', width: 90 },
  { label: '连通性', slot: 'health', width: 100 },
  { label: '创建时间', slot: 'createdAt', width: 160 },
  { label: '操作', slot: 'actions', width: 220, fixed: 'right' },
]

const tableRef = ref()
/** 正在测连通性的行 id（行级 loading） */
const testingId = ref<number | null>(null)
const modelDialogRef = ref()

function openModels(row: Provider) {
  modelDialogRef.value?.open(row)
}

function formatDateTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

async function handleTest(row: Provider) {
  testingId.value = row.id
  try {
    const resp = await testProviderConnection(row.id)
    if (resp.ok) {
      notifySuccess(resp.message)
    } else {
      notifyWarning(resp.message)
    }
    tableRef.value?.refresh()
  } finally {
    testingId.value = null
  }
}

// ---------------- 新增 / 编辑弹窗 ----------------

const dialogVisible = ref(false)
const dialogRef = ref()
const editingId = ref<number | null>(null)   // null=新增

const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 64, message: '名称不能超过 64 个字符', trigger: 'blur' },
  ],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  // baseUrl 对 openai_compatible 的必填校验由后端完成（错误信息明确）
}

function openCreate() {
  editingId.value = null
  dialogRef.value?.open({ enabled: 1 })
}

function openEdit(row: Provider) {
  editingId.value = row.id
  // apiKey 置空：编辑不回传明文（只有掩码），留空=保持原值
  dialogRef.value?.open({ ...row, apiKey: '' })
}

async function handleSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  const req = data as unknown as ProviderSaveReq
  try {
    if (editingId.value) {
      await updateProvider(editingId.value, req)
      notifySuccess('保存成功')
      done()
      tableRef.value?.refresh()          // 编辑停在当前页
    } else {
      await createProvider(req)
      notifySuccess('创建成功')
      done()
      tableRef.value?.refresh(true)      // 新增回到第 1 页
    }
  } catch {
    done(false)   // 失败保留弹窗（错误提示已由拦截器弹出）
  }
}

// ---------------- 删除 ----------------

async function handleDelete(row: Provider) {
  await useConfirm(`确认删除「${row.name}」？删除后模型配置与 Agent 绑定将失效。`, () =>
    deleteProvider(row.id),
  )
  tableRef.value?.refresh()
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">模型提供商管理</h1>
        <p class="page-desc">管理模型提供商的接入配置，Agent 绑定模型时从这里选择</p>
      </div>
      <el-button type="primary" class="page-create-btn" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新增提供商
      </el-button>
    </div>

    <div class="page-card">
      <HifyTable
        ref="tableRef"
        :columns="columns"
        :api="listProviders"
        empty-text="暂无提供商，点击右上角「新增提供商」"
      >
        <template #type="{ row }">
          <el-tag
            :type="TYPE_TAG[row.type as ProviderTypeCode]?.type ?? 'info'"
            :effect="TYPE_TAG[row.type as ProviderTypeCode]?.effect ?? 'light'"
            disable-transitions
          >
            {{ TYPE_LABEL[row.type as ProviderTypeCode] ?? row.type }}
          </el-tag>
        </template>
        <template #enabled="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" :effect="row.enabled === 1 ? 'light' : 'plain'" disable-transitions>
            {{ row.enabled === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
        <template #health="{ row }">
          <el-tooltip :content="row.lastTestMessage ?? '从未测试'" :disabled="!row.lastTestMessage" placement="top">
            <el-tag
              :type="row.lastTestOk === 1 ? 'success' : row.lastTestOk === 0 ? 'danger' : 'info'"
              :effect="row.lastTestOk === null ? 'plain' : 'light'"
              disable-transitions
            >
              {{ row.lastTestOk === 1 ? '正常' : row.lastTestOk === 0 ? '异常' : '未测试' }}
            </el-tag>
          </el-tooltip>
        </template>
        <template #createdAt="{ row }">
          <span class="page-time">{{ formatDateTime(row.createdAt) }}</span>
        </template>
        <template #actions="{ row }">
          <el-button link type="primary" :loading="testingId === row.id" @click="handleTest(row)">测试</el-button>
          <el-button link type="primary" @click="openModels(row)">模型</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </HifyTable>
    </div>

    <HifyFormDialog
      ref="dialogRef"
      v-model="dialogVisible"
      :title="editingId ? '编辑提供商' : '新增提供商'"
      :rules="rules"
      @submit="handleSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="名称" prop="name">
          <el-input v-model="model.name" placeholder="如 OpenAI 官方" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="model.type" placeholder="请选择类型" style="width: 100%">
            <el-option v-for="t in PROVIDER_TYPES" :key="t.code" :label="t.label" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input
            v-model="model.baseUrl"
            :placeholder="BASE_URL_PLACEHOLDER[model.type as ProviderTypeCode] ?? 'https://...'"
            clearable
          />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="model.apiKey"
            :placeholder="editingId ? '留空保持原 Key' : 'Ollama / 本地 vLLM 可不填'"
            show-password
            clearable
          />
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="model.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="model.remark" placeholder="可选" maxlength="255" clearable />
        </el-form-item>
      </template>
    </HifyFormDialog>

    <!-- 模型管理（该 provider 下的 model_config） -->
    <ModelManageDialog ref="modelDialogRef" />
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

/* 页面唯一的关键 CTA：hover 品牌光晕 */
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
</style>
