<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import { useRouter } from 'vue-router'

import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifySuccess } from '@/utils/notify'
import {
  createWorkflow,
  deleteWorkflow,
  listWorkflows,
  updateWorkflow,
  type Workflow,
  type WorkflowSaveReq,
} from '@/api/workflow'
import type { HifyFormDialogDone, HifyTableColumn } from '@/types/components'

const router = useRouter()

// ---------------- 列表 ----------------

const columns: HifyTableColumn[] = [
  { label: '名称', prop: 'name', minWidth: 160, showOverflowTooltip: true },
  { label: '描述', prop: 'description', minWidth: 200, showOverflowTooltip: true },
  { label: '节点', slot: 'nodes', width: 80 },
  { label: '状态', slot: 'enabled', width: 80 },
  { label: '更新时间', slot: 'updatedAt', width: 160 },
  { label: '操作', slot: 'actions', width: 200, fixed: 'right' },
]

const tableRef = ref()

/** 后端工作流不分页（数量级小），包一层适配 HifyTable 的分页协议 */
async function fetchWorkflows() {
  const list = await listWorkflows()
  return { list, total: list.length, page: 1, pageSize: list.length }
}

function formatDateTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

// ---------------- 基础信息编辑（编排在详情页改，这里只改名称/描述/启停） ----------------

const dialogVisible = ref(false)
const dialogRef = ref()
const editingRow = ref<Workflow | null>(null)   // null=新增

const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 64, message: '名称不能超过 64 个字符', trigger: 'blur' },
  ],
}

function openEdit(row: Workflow) {
  editingRow.value = row
  dialogRef.value?.open({ name: row.name, description: row.description, enabled: row.enabled })
}

async function handleSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  try {
    const row = editingRow.value!
    // 保存整体图：基础信息从表单取，节点/连线保持原样（编排改动在详情页保存）
    const req: WorkflowSaveReq = {
      name: data.name as string,
      description: (data.description ?? '') as string,
      enabled: (data.enabled ?? 1) as number,
      nodes: row.nodes.map(({ id: _id, ...node }) => node),
      edges: row.edges.map(({ id: _id, ...edge }) => edge),
    }
    await updateWorkflow(row.id, req)
    notifySuccess('保存成功')
    done()
    tableRef.value?.refresh()
  } catch {
    done(false)   // 失败保留弹窗（错误提示已由拦截器弹出）
  }
}

// ---------------- 删除 / 进入编排 ----------------

async function handleDelete(row: Workflow) {
  await useConfirm(
    `确认删除工作流「${row.name}」？节点、连线和运行历史将一并清理。`,
    () => deleteWorkflow(row.id),
  )
  tableRef.value?.refresh()
}

function openDetail(row: Workflow) {
  router.push(`/workflow/${row.id}`)
}

/** 新增：跳到空编排页（工作流必须带节点+连线才能保存，列表弹窗里建不了） */
function openCreate() {
  router.push('/workflow/new')
}

onMounted(() => {
  tableRef.value?.refresh()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">工作流</h1>
        <p class="page-desc">顺序执行 + 条件分支的节点编排（start → llm / condition → end），独立运行验证</p>
      </div>
      <el-button type="primary" class="page-create-btn" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新增工作流
      </el-button>
    </div>

    <div class="page-card">
      <HifyTable
        ref="tableRef"
        :columns="columns"
        :api="fetchWorkflows"
        :show-pagination="false"
        empty-text="暂无工作流，点击右上角「新增工作流」开始编排"
      >
        <template #nodes="{ row }">
          <span class="page-time">{{ row.nodes.length }}</span>
        </template>
        <template #enabled="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" :effect="row.enabled === 1 ? 'light' : 'plain'" disable-transitions>
            {{ row.enabled === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
        <template #updatedAt="{ row }">
          <span class="page-time">{{ formatDateTime(row.updatedAt) }}</span>
        </template>
        <template #actions="{ row }">
          <el-button link type="primary" @click="openDetail(row)">编排</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </HifyTable>
    </div>

    <HifyFormDialog
      ref="dialogRef"
      v-model="dialogVisible"
      title="编辑工作流"
      width="520px"
      :rules="rules"
      @submit="handleSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="名称" prop="name">
          <el-input v-model="model.name" placeholder="如 客服工单分类" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="model.description" placeholder="一句话说明用途，可选" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="model.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <div class="graph-tip">节点与连线在「编排」页维护，此处仅调整基础信息</div>
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

.graph-tip {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}
</style>
