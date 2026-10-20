<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'

import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifyError, notifySuccess } from '@/utils/notify'
import { listModelConfigs } from '@/api/modelConfig'
import { listProviders } from '@/api/provider'
import {
  listAgentKnowledgeBases,
  listKnowledgeBases,
  updateAgentKnowledgeBases,
  type KnowledgeBase,
} from '@/api/knowledge'
import {
  listAgentTools,
  listMcpServers,
  listMcpTools,
  updateAgentTools,
  type McpServer,
  type McpTool,
} from '@/api/mcp'
import {
  createAgent,
  deleteAgent,
  listAgents,
  updateAgent,
  type Agent,
  type AgentSaveReq,
} from '@/api/agent'
import type { HifyFormDialogDone, HifyTableColumn } from '@/types/components'

// ---------------- 列表 ----------------

const columns: HifyTableColumn[] = [
  { label: '名称', prop: 'name', minWidth: 150, showOverflowTooltip: true },
  { label: '描述', prop: 'description', minWidth: 180, showOverflowTooltip: true },
  { label: '绑定模型', slot: 'model', minWidth: 200 },
  { label: '温度', slot: 'temperature', width: 80 },
  { label: '状态', slot: 'enabled', width: 80 },
  { label: '创建时间', slot: 'createdAt', width: 160 },
  { label: '操作', slot: 'actions', width: 140, fixed: 'right' },
]

const tableRef = ref()

function formatDateTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

// ---------------- 模型下拉数据（启用 provider 下启用 chat 模型，按 provider 分组） ----------------

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
      listModelConfigs({ page: 1, pageSize: 100, modelType: 'chat' }),
    ])
    // 后端 model-configs 只按 providerId/modelType 过滤，启用状态前端筛
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

// ---------------- 知识库多选（RAG 检索绑定） ----------------

const knowledgeOptions = ref<KnowledgeBase[]>([])

async function loadKnowledgeOptions() {
  knowledgeOptions.value = await listKnowledgeBases()
}

onMounted(loadKnowledgeOptions)

// ---------------- MCP 工具绑定（function calling） ----------------

/** 启用的 MCP 服务（绑定下拉选项） */
const mcpOptions = ref<McpServer[]>([])

async function loadMcpOptions() {
  const servers = await listMcpServers()
  mcpOptions.value = servers.filter((s) => s.enabled === 1)
}

onMounted(loadMcpOptions)

/** 弹窗里一行的形状：服务 + 具体工具（空 = 全部工具） */
interface ToolBindingRow {
  mcpServerId: number | undefined
  toolName: string
}

/** serverId → 工具列表（懒加载缓存，给具体工具下拉用） */
const serverTools = ref<Record<number, McpTool[]>>({})
const serverToolsLoading = ref<Record<number, boolean>>({})

/** 取某服务的工具列表；首次访问时后台拉取（失败按空处理） */
function toolsOf(serverId: number | undefined): McpTool[] {
  if (!serverId || !mcpOptions.value.some((s) => s.id === serverId)) {
    return []
  }
  if (!(serverId in serverTools.value) && !serverToolsLoading.value[serverId]) {
    serverToolsLoading.value[serverId] = true
    listMcpTools(serverId)
      .then((tools) => {
        serverTools.value[serverId] = tools
      })
      .catch(() => {
        serverTools.value[serverId] = []
      })
      .finally(() => {
        serverToolsLoading.value[serverId] = false
      })
  }
  return serverTools.value[serverId] ?? []
}

// ---------------- 新增 / 编辑弹窗 ----------------

const dialogVisible = ref(false)
const dialogRef = ref()
const editingRow = ref<Agent | null>(null)   // null=新增

const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 64, message: '名称不能超过 64 个字符', trigger: 'blur' },
  ],
  modelConfigId: [{ required: true, message: '请选择绑定的模型', trigger: 'change' }],
}

/** 编辑时绑定模型已不可用（被删/停用）：追加一个禁用选项展示，提交时后端 3003 拦截 */
const currentModelMissing = computed(() => {
  if (!editingRow.value) {
    return false
  }
  const available = new Set(modelGroups.value.flatMap((g) => g.options.map((o) => o.modelConfigId)))
  return !available.has(editingRow.value.modelConfigId)
})

function openCreate() {
  editingRow.value = null
  dialogRef.value?.open({
    temperature: 0.7,
    maxTokens: 0,
    enabled: 1,
    systemPrompt: '',
    knowledgeBaseIds: [],
    toolBindings: [] as ToolBindingRow[],
  })
}

async function openEdit(row: Agent) {
  editingRow.value = row
  // 绑定的知识库 / MCP 工具单拉（不在 Agent 响应里，各模块持有绑定关系）
  const [boundKbs, boundTools] = await Promise.all([
    listAgentKnowledgeBases(row.id),
    listAgentTools(row.id),
  ])
  dialogRef.value?.open({
    ...row,
    knowledgeBaseIds: boundKbs.map((kb) => kb.id),
    toolBindings: boundTools.map((b) => ({ mcpServerId: b.mcpServerId, toolName: b.toolName ?? '' })),
  })
}

async function handleSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  // el-input-number 清空时为 null，统一归位默认值
  const req = {
    ...data,
    temperature: data.temperature ?? 0.7,
    maxTokens: data.maxTokens ?? 0,
  } as unknown as AgentSaveReq
  const kbIds = (data.knowledgeBaseIds ?? []) as number[]
  try {
    let agentId: number
    if (editingRow.value) {
      await updateAgent(editingRow.value.id, req)
      agentId = editingRow.value.id
      notifySuccess('保存成功')
    } else {
      const created = await createAgent(req)
      agentId = created.id
      notifySuccess('创建成功')
    }
    // 知识库 / MCP 工具绑定单独保存（失败只提示，不回滚 Agent 主配置）
    try {
      await updateAgentKnowledgeBases(agentId, { knowledgeBaseIds: kbIds })
    } catch {
      notifyError('知识库绑定保存失败，请重试')
    }
    try {
      await updateAgentTools(agentId, { bindings: collectToolBindings(data) })
    } catch {
      notifyError('工具绑定保存失败，请重试')
    }
    done()
    tableRef.value?.refresh(editingRow.value ? false : true)
  } catch {
    done(false)   // 失败保留弹窗（错误提示已由拦截器弹出）
  }
}

/** 表单里的绑定行 → 提交给后端的载荷（去空、去重；工具名空 = 该服务全部工具） */
function collectToolBindings(data: Record<string, unknown>): { mcpServerId: number; toolName: string | null }[] {
  const rows = (data.toolBindings ?? []) as ToolBindingRow[]
  const seen = new Set<string>()
  const bindings: { mcpServerId: number; toolName: string | null }[] = []
  for (const row of rows) {
    if (!row.mcpServerId) {
      continue
    }
    const toolName = row.toolName?.trim() || null
    const key = `${row.mcpServerId}:${toolName ?? '*'}`
    if (seen.has(key)) {
      continue
    }
    seen.add(key)
    bindings.push({ mcpServerId: row.mcpServerId, toolName })
  }
  return bindings
}

// ---------------- 删除 ----------------

async function handleDelete(row: Agent) {
  await useConfirm(`确认删除 Agent「${row.name}」？其下的对话会话将无法继续使用。`, () =>
    deleteAgent(row.id),
  )
  tableRef.value?.refresh()
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">Agent 管理</h1>
        <p class="page-desc">创建 Agent，绑定模型并配置 System Prompt，对话页从这里选择</p>
      </div>
      <el-button type="primary" class="page-create-btn" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新增 Agent
      </el-button>
    </div>

    <div class="page-card">
      <HifyTable
        ref="tableRef"
        :columns="columns"
        :api="listAgents"
        empty-text="暂无 Agent，点击右上角「新增 Agent」"
      >
        <template #model="{ row }">
          <!-- 模型配置被删：标红提示，引导去提供商页处理 -->
          <template v-if="!row.modelName">
            <el-tag type="danger" effect="light" disable-transitions>配置缺失</el-tag>
          </template>
          <div v-else class="model-cell">
            <span class="model-name">{{ row.modelName }}</span>
            <span class="model-provider">{{ row.providerName }}</span>
          </div>
        </template>
        <template #temperature="{ row }">
          <span class="page-time">{{ Number(row.temperature).toFixed(1) }}</span>
        </template>
        <template #enabled="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" :effect="row.enabled === 1 ? 'light' : 'plain'" disable-transitions>
            {{ row.enabled === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
        <template #createdAt="{ row }">
          <span class="page-time">{{ formatDateTime(row.createdAt) }}</span>
        </template>
        <template #actions="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </HifyTable>
    </div>

    <HifyFormDialog
      ref="dialogRef"
      v-model="dialogVisible"
      :title="editingRow ? '编辑 Agent' : '新增 Agent'"
      width="640px"
      :rules="rules"
      @submit="handleSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="名称" prop="name">
          <el-input v-model="model.name" placeholder="如 客服助手" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="model.description" placeholder="一句话说明用途，可选" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="绑定模型" prop="modelConfigId">
          <el-select
            v-model="model.modelConfigId"
            placeholder="选择启用的对话模型"
            :loading="modelsLoading"
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
            <!-- 原绑定已删/停用：保留展示（禁用），换绑后才能保存 -->
            <el-option
              v-if="currentModelMissing"
              :value="editingRow!.modelConfigId"
              :label="`${editingRow!.modelName || `模型 #${editingRow!.modelConfigId}`}（已不可用，请换绑）`"
              disabled
            />
            <template #footer>
              <div class="model-empty">
                {{ modelGroups.length ? '' : '暂无可用模型：先在「提供商管理」里配置并启用 chat 模型' }}
              </div>
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="知识库" prop="knowledgeBaseIds">
          <!-- RAG：绑定后对话会检索这些库并注入参考资料，空=不检索 -->
          <el-select
            v-model="model.knowledgeBaseIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="可选：绑定向量化完成的知识库"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="kb in knowledgeOptions"
              :key="kb.id"
              :label="kb.name"
              :value="kb.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="MCP 工具" prop="toolBindings">
          <!-- function calling：绑定后对话中模型可调用这些外部工具 -->
          <div class="tool-bindings">
            <div
              v-for="(binding, idx) in (model.toolBindings as ToolBindingRow[])"
              :key="idx"
              class="tool-binding-row"
            >
              <el-select
                v-model="binding.mcpServerId"
                placeholder="选择 MCP 服务"
                clearable
                style="width: 46%"
                @change="binding.toolName = ''"
              >
                <el-option v-for="s in mcpOptions" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
              <el-select
                v-model="binding.toolName"
                placeholder="全部工具"
                clearable
                filterable
                :loading="!!serverToolsLoading[binding.mcpServerId ?? 0]"
                style="flex: 1"
              >
                <el-option
                  v-for="t in toolsOf(binding.mcpServerId)"
                  :key="t.name"
                  :label="t.name"
                  :value="t.name"
                />
              </el-select>
              <el-button
                link
                type="danger"
                @click="(model.toolBindings as ToolBindingRow[]).splice(idx, 1)"
              >删除</el-button>
            </div>
            <el-button
              link
              type="primary"
              @click="(model.toolBindings as ToolBindingRow[]).push({ mcpServerId: undefined, toolName: '' })"
            >+ 添加工具绑定</el-button>
            <div class="form-tip">工具名留空 = 该服务全部工具；绑定后 Agent 对话时模型可调用（function calling）</div>
          </div>
        </el-form-item>
        <el-form-item label="System Prompt" prop="systemPrompt">
          <el-input
            v-model="model.systemPrompt"
            type="textarea"
            :rows="6"
            maxlength="8000"
            show-word-limit
            placeholder="设定 Agent 的角色、能力边界和行为规范，如：你是售后客服，只回答订单与退换货相关问题…"
          />
        </el-form-item>
        <el-form-item label="温度" prop="temperature">
          <div class="temperature-row">
            <el-slider v-model="model.temperature" :min="0" :max="2" :step="0.1" :marks="{ 0: '严谨', 1: '均衡', 2: '发散' }" />
          </div>
        </el-form-item>
        <el-form-item label="回复上限" prop="maxTokens">
          <el-input-number
            v-model="model.maxTokens"
            :min="0"
            :max="2000000"
            :step="256"
            placeholder="0=模型默认"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="model.enabled" :active-value="1" :inactive-value="0" />
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

/* 绑定模型：主名 + 提供商小字 */
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

/* slider 的 marks 需要额外宽度，防止溢出表单 */
.temperature-row {
  width: 100%;
  padding-right: var(--hify-space-2);
}

.model-empty {
  padding: var(--hify-space-2) var(--hify-space-3);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

/* MCP 工具绑定：每行 服务 + 具体工具（可空） */
.tool-bindings {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-2);
  width: 100%;
}

.tool-binding-row {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
}

.form-tip {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  line-height: 1.6;
}
</style>
