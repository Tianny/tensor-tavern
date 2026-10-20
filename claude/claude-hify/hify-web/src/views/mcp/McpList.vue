<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'

import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifyError, notifySuccess } from '@/utils/notify'
import {
  createMcpServer,
  deleteMcpServer,
  listMcpServers,
  listMcpTools,
  testMcpConnection,
  updateMcpServer,
  type McpServer,
  type McpServerSaveReq,
  type McpTool,
} from '@/api/mcp'
import type { HifyFormDialogDone, HifyTableColumn } from '@/types/components'

// ---------------- 列表 ----------------

const columns: HifyTableColumn[] = [
  { label: '名称', prop: 'name', minWidth: 140, showOverflowTooltip: true },
  { label: '描述', prop: 'description', minWidth: 180, showOverflowTooltip: true },
  { label: '地址', slot: 'url', minWidth: 220, showOverflowTooltip: true },
  { label: '状态', slot: 'enabled', width: 80 },
  { label: '更新时间', slot: 'updatedAt', width: 160 },
  { label: '操作', slot: 'actions', width: 300, fixed: 'right' },
]

const tableRef = ref()

/** 后端 MCP 服务不分页（数量级小），包一层适配 HifyTable 的分页协议 */
async function fetchServers() {
  const list = await listMcpServers()
  return { list, total: list.length, page: 1, pageSize: list.length }
}

function formatDateTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

// ---------------- 新增 / 编辑 ----------------

const dialogVisible = ref(false)
const dialogRef = ref()
const editingRow = ref<McpServer | null>(null)   // null=新增

const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 64, message: '名称不能超过 64 个字符', trigger: 'blur' },
  ],
  url: [
    { required: true, message: '请输入 MCP 端点地址', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: '地址必须以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
}

function openCreate() {
  editingRow.value = null
  dialogRef.value?.open({ name: '', description: '', url: '', headersJson: '', enabled: 1 })
}

function openEdit(row: McpServer) {
  editingRow.value = row
  dialogRef.value?.open({
    name: row.name,
    description: row.description,
    url: row.url,
    // 掩码值序列化回显：保存时原样传回即保持库里的真实值
    headersJson: Object.keys(row.headers ?? {}).length
      ? JSON.stringify(Object.fromEntries(
          Object.entries(row.headers).map(([k, v]) => [k, v || '******'])), null, 0)
      : '',
    enabled: row.enabled,
  })
}

async function handleSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  let headers: Record<string, string> | null = null
  const raw = ((data.headersJson ?? '') as string).trim()
  if (raw) {
    try {
      headers = JSON.parse(raw) as Record<string, string>
      if (typeof headers !== 'object' || Array.isArray(headers)) {
        throw new Error('not object')
      }
    } catch {
      notifyError('请求头必须是合法的 JSON 对象，如 {"Authorization":"Bearer xxx"}')
      done(false)
      return
    }
  }
  const req: McpServerSaveReq = {
    name: data.name as string,
    description: (data.description ?? '') as string,
    transport: 'http',
    url: (data.url as string).trim(),
    headers,
    enabled: (data.enabled ?? 1) as number,
  }
  try {
    if (editingRow.value) {
      await updateMcpServer(editingRow.value.id, req)
      notifySuccess('保存成功')
    } else {
      await createMcpServer(req)
      notifySuccess('创建成功')
    }
    done()
    tableRef.value?.refresh(editingRow.value ? false : true)
  } catch {
    done(false)   // 失败保留弹窗（错误提示已由拦截器弹出）
  }
}

// ---------------- 测试连接 / 查看工具 / 删除 ----------------

const testingId = ref<number | null>(null)

async function handleTest(row: McpServer) {
  testingId.value = row.id
  try {
    const resp = await testMcpConnection(row.id)
    if (resp.ok) {
      notifySuccess(resp.message)
    } else {
      notifyError(`连接失败：${resp.message}（${resp.latencyMs}ms）`)
    }
  } finally {
    testingId.value = null
  }
}

const toolsDialogVisible = ref(false)
const toolsLoading = ref(false)
const tools = ref<McpTool[]>([])
const toolsServerName = ref('')

async function openTools(row: McpServer) {
  toolsServerName.value = row.name
  toolsDialogVisible.value = true
  toolsLoading.value = true
  tools.value = []
  try {
    tools.value = await listMcpTools(row.id)
  } finally {
    toolsLoading.value = false
  }
}

async function handleDelete(row: McpServer) {
  await useConfirm(
    `确认删除 MCP 服务「${row.name}」？Agent 上的绑定将失效。`,
    () => deleteMcpServer(row.id),
  )
  tableRef.value?.refresh()
}

onMounted(() => {
  tableRef.value?.refresh()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">MCP 工具</h1>
        <p class="page-desc">接入外部 MCP 服务（Streamable HTTP），Agent 绑定后可在对话中调用工具</p>
      </div>
      <el-button type="primary" class="page-create-btn" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新增服务
      </el-button>
    </div>

    <div class="page-card">
      <HifyTable
        ref="tableRef"
        :columns="columns"
        :api="fetchServers"
        :show-pagination="false"
        empty-text="暂无 MCP 服务，点击右上角「新增服务」接入"
      >
        <template #url="{ row }">
          <span class="page-time">{{ row.url }}</span>
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
          <el-button link type="primary" :loading="testingId === row.id" @click="handleTest(row)">测试</el-button>
          <el-button link type="primary" @click="openTools(row)">工具</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </HifyTable>
    </div>

    <HifyFormDialog
      ref="dialogRef"
      v-model="dialogVisible"
      :title="editingRow ? '编辑 MCP 服务' : '新增 MCP 服务'"
      width="560px"
      :rules="rules"
      @submit="handleSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="名称" prop="name">
          <el-input v-model="model.name" placeholder="如 天气查询服务" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="model.description" placeholder="一句话说明用途，可选" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="端点地址" prop="url">
          <el-input v-model="model.url" placeholder="https://example.com/mcp（Streamable HTTP 端点）" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="请求头" prop="headersJson">
          <el-input
            v-model="model.headersJson"
            type="textarea"
            :rows="2"
            placeholder='可选，JSON 对象，如 {"Authorization":"Bearer xxx"}'
          />
          <div v-if="editingRow && Object.keys(editingRow.headers ?? {}).length" class="form-tip">
            值已掩码（******）回显，原样保存即保持原值
          </div>
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="model.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </template>
    </HifyFormDialog>

    <el-dialog v-model="toolsDialogVisible" :title="`「${toolsServerName}」的工具`" width="640px">
      <el-table :data="tools" v-loading="toolsLoading" size="small" empty-text="无工具或服务不可用">
        <el-table-column label="工具名" prop="name" width="180" show-overflow-tooltip />
        <el-table-column label="描述" prop="description" min-width="200" show-overflow-tooltip />
        <el-table-column label="参数" width="70">
          <template #default="{ row }">
            <el-popover
              v-if="row.inputSchema"
              placement="left"
              :width="420"
              trigger="hover"
            >
              <pre class="schema-json">{{ JSON.stringify(row.inputSchema, null, 2) }}</pre>
              <template #reference>
                <el-button link type="primary">Schema</el-button>
              </template>
            </el-popover>
            <span v-else class="form-tip">无</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
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

.form-tip {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  line-height: 1.6;
}

.schema-json {
  margin: 0;
  max-height: 300px;
  overflow: auto;
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-2);
}
</style>
