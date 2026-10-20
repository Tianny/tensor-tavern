<script setup lang="ts">
import { ref, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'

import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifySuccess, notifyWarning } from '@/utils/notify'
import {
  createModelConfig,
  deleteModelConfig,
  fetchRemoteModels,
  listModelConfigs,
  updateModelConfig,
  type ModelConfig,
  type ModelConfigCreateReq,
  type ModelConfigUpdateReq,
} from '@/api/modelConfig'
import type { Provider } from '@/api/provider'
import type { HifyFormDialogDone, HifyTableColumn } from '@/types/components'

/**
 * 模型管理弹窗（从 ProviderList 的「模型」按钮打开）：
 * 该 provider 下的 model_config 列表 + 新增/编辑/删除 + 从远端拉取勾选导入。
 * 远端只提供模型名，上下文长度等元数据入库后人工补充。
 */
const visible = ref(false)
const provider = ref<Provider | null>(null)
const tableRef = ref()

const columns: HifyTableColumn[] = [
  { label: '模型名', prop: 'modelName', minWidth: 200, showOverflowTooltip: true },
  { label: '展示名', prop: 'displayName', minWidth: 140, showOverflowTooltip: true },
  { label: '类型', slot: 'modelType', width: 110 },
  { label: '上下文', slot: 'context', width: 100 },
  { label: '状态', slot: 'enabled', width: 80 },
  { label: '操作', slot: 'actions', width: 130, fixed: 'right' },
]

function open(target: Provider) {
  provider.value = target
  visible.value = true
}

// script-setup 组件默认对外封闭：不 expose，父组件的 dialogRef.open() 会静默失败
defineExpose({ open })

// 首次打开时表格组件随 dialog 内容懒挂载并自动请求；
// 之后每次打开手动刷新（tableRef 已存在才刷，避免首开双请求）
watch(visible, (open) => {
  if (open && tableRef.value) {
    tableRef.value?.refresh(true)
  }
})

function listApi(params: Record<string, unknown>) {
  return listModelConfigs({ ...params, providerId: provider.value?.id, pageSize: 100 })
}

function formatTokens(tokens: number): string {
  if (!tokens) {
    return '—'
  }
  return tokens >= 1000 ? `${Math.round(tokens / 1000)}K` : String(tokens)
}

// ---------------- 新增 / 编辑模型 ----------------

const formVisible = ref(false)
const formRef = ref()
const editingId = ref<number | null>(null)

const rules: FormRules = {
  modelName: [
    { required: true, message: '请输入模型名', trigger: 'blur' },
    { max: 64, message: '模型名不能超过 64 个字符', trigger: 'blur' },
  ],
  modelType: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
}

function openCreate() {
  editingId.value = null
  formRef.value?.open({ modelType: 'chat', maxContextTokens: 0, enabled: 1 })
}

function openEdit(row: ModelConfig) {
  editingId.value = row.id
  formRef.value?.open(row)
}

async function handleSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  if (!provider.value) {
    return
  }
  try {
    if (editingId.value) {
      await updateModelConfig(editingId.value, data as unknown as ModelConfigUpdateReq)
      notifySuccess('保存成功')
    } else {
      await createModelConfig({ ...data, providerId: provider.value.id } as unknown as ModelConfigCreateReq)
      notifySuccess('创建成功')
    }
    done()
    tableRef.value?.refresh(!editingId.value)
  } catch {
    done(false)
  }
}

// ---------------- 删除 ----------------

async function handleDelete(row: ModelConfig) {
  await useConfirm(`确认删除模型「${row.modelName}」？Agent 绑定该模型的配置将失效。`, () =>
    deleteModelConfig(row.id),
  )
  tableRef.value?.refresh()
}

// ---------------- 从远端拉取 ----------------

const fetching = ref(false)
const remoteVisible = ref(false)
const remoteNames = ref<string[]>([])
const checkedNames = ref<string[]>([])
const importing = ref(false)

function isExisting(name: string): boolean {
  const list: ModelConfig[] = tableRef.value?.list ?? []
  return list.some((m) => m.modelName === name)
}

async function handleFetchRemote() {
  if (!provider.value) {
    return
  }
  fetching.value = true
  try {
    const names = await fetchRemoteModels(provider.value.id)
    if (!names.length) {
      notifyWarning('远端未返回模型列表')
      return
    }
    remoteNames.value = names
    // 默认全选（已存在的自动禁用，导入时也会跳过）
    checkedNames.value = [...names]
    remoteVisible.value = true
  } finally {
    fetching.value = false
  }
}

async function handleImport() {
  if (!provider.value) {
    return
  }
  const toCreate = checkedNames.value.filter((name) => !isExisting(name))
  if (!toCreate.length) {
    remoteVisible.value = false
    return
  }
  importing.value = true
  let ok = 0
  let fail = 0
  try {
    for (const name of toCreate) {
      try {
        await createModelConfig({
          providerId: provider.value.id,
          modelName: name,
          modelType: 'chat',
        })
        ok++
      } catch {
        fail++   // 单条失败不中断（错误提示已由拦截器弹出）
      }
    }
    notifySuccess(`导入完成：成功 ${ok} 个${fail ? `，失败 ${fail} 个` : ''}`)
    remoteVisible.value = false
    tableRef.value?.refresh(true)
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`模型管理 · ${provider?.name ?? ''}`"
    width="900px"
    :close-on-click-modal="false"
  >
    <div class="model-toolbar">
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新增模型
      </el-button>
      <el-button :loading="fetching" @click="handleFetchRemote">从远端拉取</el-button>
      <span class="model-hint">远端拉取只导入模型名，上下文长度等元数据请编辑补充</span>
    </div>

    <HifyTable
      ref="tableRef"
      :columns="columns"
      :api="listApi"
      :show-pagination="false"
      empty-text="暂无模型，可新增或从远端拉取"
    >
      <template #modelType="{ row }">
        <el-tag :type="row.modelType === 'chat' ? 'primary' : 'success'" effect="plain" disable-transitions>
          {{ row.modelType === 'chat' ? '对话' : '向量化' }}
        </el-tag>
      </template>
      <template #context="{ row }">
        <span class="model-num">{{ formatTokens(row.maxContextTokens) }}</span>
      </template>
      <template #enabled="{ row }">
        <el-tag :type="row.enabled === 1 ? 'success' : 'info'" :effect="row.enabled === 1 ? 'light' : 'plain'" disable-transitions>
          {{ row.enabled === 1 ? '启用' : '停用' }}
        </el-tag>
      </template>
      <template #actions="{ row }">
        <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
      </template>
    </HifyTable>

    <!-- 拉取远端：勾选导入 -->
    <el-dialog v-model="remoteVisible" title="从远端拉取模型" width="480px" append-to-body>
      <el-checkbox-group v-model="checkedNames" class="remote-list">
        <div v-for="name in remoteNames" :key="name" class="remote-item">
          <el-checkbox :value="name" :disabled="isExisting(name)">
            {{ name }}
            <span v-if="isExisting(name)" class="remote-exists">（已存在）</span>
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="remoteVisible = false">取 消</el-button>
        <el-button type="primary" :loading="importing" @click="handleImport">导入选中</el-button>
      </template>
    </el-dialog>

    <!-- 模型新增/编辑（嵌套弹窗挂到 body） -->
    <HifyFormDialog
      ref="formRef"
      v-model="formVisible"
      append-to-body
      :title="editingId ? '编辑模型' : '新增模型'"
      :rules="rules"
      @submit="handleSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="模型名" prop="modelName">
          <el-input v-model="model.modelName" placeholder="如 gpt-4o / deepseek-chat" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="展示名" prop="displayName">
          <el-input v-model="model.displayName" placeholder="留空取模型名" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="模型类型" prop="modelType">
          <el-select v-model="model.modelType" style="width: 100%">
            <el-option label="对话（chat）" value="chat" />
            <el-option label="向量化（embedding）" value="embedding" />
          </el-select>
        </el-form-item>
        <el-form-item label="上下文上限" prop="maxContextTokens">
          <el-input-number
            v-model="model.maxContextTokens"
            :min="0"
            :max="2000000"
            :step="1000"
            placeholder="0=未知"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="model.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </template>
    </HifyFormDialog>
  </el-dialog>
</template>

<style scoped>
.model-toolbar {
  display: flex;
  align-items: center;
  gap: var(--hify-space-3);
  margin-bottom: var(--hify-space-4);
}

.model-hint {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.model-num {
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
}

.remote-list {
  max-height: 360px;
  overflow-y: auto;
}

.remote-item {
  padding: var(--hify-space-1) 0;
}

.remote-exists {
  color: var(--hify-text-3);
  font-size: var(--hify-text-xs);
}
</style>
