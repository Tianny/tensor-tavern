<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Delete, Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'

import HifyFormDialog from '@/components/HifyFormDialog.vue'
import { useConfirm } from '@/composables/useConfirm'
import { notifyError, notifySuccess } from '@/utils/notify'
import { listModelConfigs } from '@/api/modelConfig'
import { listProviders } from '@/api/provider'
import {
  createWorkflow,
  getWorkflow,
  getWorkflowRun,
  listWorkflowRuns,
  runWorkflow,
  updateWorkflow,
  type NodeType,
  type StartVariable,
  type Workflow,
  type WorkflowRun,
  type WorkflowRunSummary,
} from '@/api/workflow'
import type { HifyFormDialogDone } from '@/types/components'

const route = useRoute()
const router = useRouter()

// /workflow/new 是无参独立路由（name=workflowCreate），不能靠 params.id 判断
const isNew = route.name === 'workflowCreate'
const workflowId = isNew ? null : Number(route.params.id)

// ---------------- 本地图状态（保存时整体提交） ----------------

interface LocalNode {
  nodeKey: string
  nodeType: NodeType
  name: string
  config: Record<string, unknown>
}

interface LocalEdge {
  sourceNodeKey: string
  targetNodeKey: string
  conditionType: 'always' | 'expression'
  conditionExpr: string
}

const meta = ref({ name: '', description: '', enabled: 1 })
const nodes = ref<LocalNode[]>([])
const edges = ref<LocalEdge[]>([])
const saving = ref(false)

const workflowLoaded = ref(!isNew)

function configSummary(node: LocalNode): string {
  switch (node.nodeType) {
    case 'start': {
      const vars = (node.config.variables as StartVariable[] | undefined) ?? []
      return vars.length ? vars.map((v) => v.name + (v.required ? '*' : '')).join('、') : '无输入变量'
    }
    case 'llm': {
      const prompt = (node.config.prompt as string | undefined) ?? ''
      return `模型 #${node.config.modelConfigId ?? '-'} · ${prompt.slice(0, 40)}${prompt.length > 40 ? '…' : ''}`
    }
    case 'condition':
      return '路由条件配在连线上'
    case 'end':
      return (node.config.outputTemplate as string | undefined) ?? ''
    default:
      return ''
  }
}

// ---------------- 加载 / 保存 ----------------

async function load() {
  if (isNew) {
    return
  }
  const wf = await getWorkflow(workflowId!)
  meta.value = { name: wf.name, description: wf.description, enabled: wf.enabled }
  nodes.value = wf.nodes.map((n) => ({ ...n, config: { ...n.config } }))
  edges.value = wf.edges.map((e) => ({
    sourceNodeKey: e.sourceNodeKey,
    targetNodeKey: e.targetNodeKey,
    conditionType: e.conditionType,
    conditionExpr: e.conditionExpr,
  }))
  workflowLoaded.value = true
  loadRunHistory()
}

async function handleSave() {
  if (!meta.value.name.trim()) {
    notifyError('请先填写工作流名称')
    return
  }
  saving.value = true
  try {
    const req = { ...meta.value, nodes: nodes.value, edges: edges.value }
    if (isNew) {
      const created = await createWorkflow(req)
      notifySuccess('创建成功')
      router.replace(`/workflow/${created.id}`)
    } else {
      await updateWorkflow(workflowId!, req)
      notifySuccess('保存成功')
    }
  } finally {
    saving.value = false
  }
}

// ---------------- 节点编辑 ----------------

const NODE_TYPES: { value: NodeType; label: string }[] = [
  { value: 'start', label: 'start（起点/输入）' },
  { value: 'llm', label: 'llm（模型调用）' },
  { value: 'condition', label: 'condition（条件路由）' },
  { value: 'end', label: 'end（终点/输出）' },
]

function nodeTypeTag(type: NodeType): { type: 'success' | 'primary' | 'warning' | 'danger'; label: string } {
  switch (type) {
    case 'start':
      return { type: 'success', label: 'start' }
    case 'llm':
      return { type: 'primary', label: 'llm' }
    case 'condition':
      return { type: 'warning', label: 'condition' }
    default:
      return { type: 'danger', label: 'end' }
  }
}

const nodeDialogVisible = ref(false)
const nodeDialogRef = ref()
const editingNodeKey = ref<string | null>(null)   // null=新增

/** 可引用变量提示（llm prompt / end 模板用）：start 变量 + 各 llm 输出 */
const availableRefs = computed(() => [
  ...nodes.value
    .filter((n) => n.nodeType === 'start')
    .flatMap((n) => ((n.config.variables as StartVariable[] | undefined) ?? []).map((v) => `${n.nodeKey}.${v.name}`)),
  ...nodes.value.filter((n) => n.nodeType === 'llm').map((n) => `${n.nodeKey}.output`),
])

const nodeRules: FormRules = {
  nodeKey: [
    { required: true, message: '请输入 nodeKey', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/, message: '字母开头，仅字母/数字/下划线', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入节点名称', trigger: 'blur' }],
  modelConfigId: [{ required: true, message: '请选择模型', trigger: 'change' }],
  prompt: [{ required: true, message: '请输入 prompt', trigger: 'blur' }],
  outputTemplate: [{ required: true, message: '请输入输出模板', trigger: 'blur' }],
}

function openNodeCreate() {
  editingNodeKey.value = null
  nodeDialogRef.value?.open({
    nodeKey: '',
    nodeType: 'llm',
    name: '',
    modelConfigId: undefined,
    prompt: '',
    temperature: 0.7,
    maxTokens: 0,
    outputTemplate: '',
    variables: [],
  })
}

function openNodeEdit(node: LocalNode) {
  editingNodeKey.value = node.nodeKey
  const form: Record<string, unknown> = {
    nodeKey: node.nodeKey,
    nodeType: node.nodeType,
    name: node.name,
    variables: (((node.config.variables as StartVariable[] | undefined) ?? []).map((v) => ({ ...v }))),
    outputTemplate: (node.config.outputTemplate as string | undefined) ?? '',
  }
  if (node.nodeType === 'llm') {
    form.modelConfigId = node.config.modelConfigId as number
    form.prompt = (node.config.prompt as string | undefined) ?? ''
    form.temperature = (node.config.temperature as number | undefined) ?? 0.7
    form.maxTokens = (node.config.maxTokens as number | undefined) ?? 0
  }
  nodeDialogRef.value?.open(form)
}

function buildNodeConfig(data: Record<string, unknown>, nodeType: NodeType): Record<string, unknown> {
  switch (nodeType) {
    case 'start':
      return { variables: (data.variables as StartVariable[]) ?? [] }
    case 'llm':
      return {
        modelConfigId: data.modelConfigId as number,
        prompt: (data.prompt as string | undefined) ?? '',
        temperature: data.temperature as number | undefined,
        maxTokens: data.maxTokens as number | undefined,
      }
    case 'condition':
      return {}
    case 'end':
      return { outputTemplate: (data.outputTemplate as string | undefined) ?? '' }
    default:
      return {}
  }
}

async function handleNodeSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  const nodeKey = data.nodeKey as string
  const nodeType = data.nodeType as NodeType
  // 同 key 修改排除自身
  const duplicated = nodes.value.some((n) => n.nodeKey === nodeKey && n.nodeKey !== editingNodeKey.value)
  if (duplicated) {
    notifyError(`nodeKey「${nodeKey}」已存在`)
    done(false)
    return
  }
  // 改 key 时同步连线引用
  const oldKey = editingNodeKey.value
  if (oldKey && oldKey !== nodeKey) {
    for (const edge of edges.value) {
      if (edge.sourceNodeKey === oldKey) {
        edge.sourceNodeKey = nodeKey
      }
      if (edge.targetNodeKey === oldKey) {
        edge.targetNodeKey = nodeKey
      }
    }
  }
  const node: LocalNode = {
    nodeKey,
    nodeType,
    name: (data.name as string).trim(),
    config: buildNodeConfig(data, nodeType),
  }
  if (oldKey) {
    const index = nodes.value.findIndex((n) => n.nodeKey === oldKey)
    nodes.value.splice(index, 1, node)
  } else {
    nodes.value.push(node)
  }
  done()
}

async function handleNodeDelete(node: LocalNode) {
  await useConfirm(
    `确认删除节点「${node.name}」？其关联连线会一并删除。`,
    async () => {
      nodes.value = nodes.value.filter((n) => n.nodeKey !== node.nodeKey)
      edges.value = edges.value.filter((e) => e.sourceNodeKey !== node.nodeKey && e.targetNodeKey !== node.nodeKey)
    },
  )
}

// ---------------- 模型下拉（LLM 节点：启用 provider 的启用 chat 模型，仅工作流支持的类型） ----------------

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
    // 后端仅支持 openai / openai_compatible / claude 的非流式调用
    const supported = new Set(['openai', 'openai_compatible', 'claude'])
    const enabledModels = modelRes.list.filter((m) => m.enabled === 1)
    modelGroups.value = provRes.list
      .filter((p) => p.enabled === 1 && supported.has(p.type))
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

// ---------------- 连线编辑 ----------------

const edgeDialogVisible = ref(false)
const edgeDialogRef = ref()
const editingEdgeIndex = ref<number | null>(null)

const edgeRules: FormRules = {
  sourceNodeKey: [{ required: true, message: '请选择起点', trigger: 'change' }],
  targetNodeKey: [{ required: true, message: '请选择终点', trigger: 'change' }],
  conditionExpr: [{ required: true, message: '请输入条件表达式', trigger: 'blur' }],
}

function openEdgeCreate() {
  editingEdgeIndex.value = null
  edgeDialogRef.value?.open({
    sourceNodeKey: nodes.value[0]?.nodeKey,
    targetNodeKey: nodes.value[1]?.nodeKey,
    conditionType: 'always',
    conditionExpr: '',
  })
}

function openEdgeEdit(edge: LocalEdge) {
  editingEdgeIndex.value = edges.value.indexOf(edge)
  edgeDialogRef.value?.open({ ...edge })
}

/** 表达式只对 condition 节点的出边有意义 */
function sourceIsCondition(form: Record<string, unknown>): boolean {
  const source = nodes.value.find((n) => n.nodeKey === form.sourceNodeKey)
  return source?.nodeType === 'condition'
}

async function handleEdgeSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
  const source = data.sourceNodeKey as string
  const target = data.targetNodeKey as string
  if (source === target) {
    notifyError('连线不能自环')
    done(false)
    return
  }
  const duplicated = edges.value.some((e, i) =>
    e.sourceNodeKey === source && e.targetNodeKey === target && i !== editingEdgeIndex.value)
  if (duplicated) {
    notifyError('该连线已存在')
    done(false)
    return
  }
  const conditionType = (sourceIsCondition(data) ? data.conditionType : 'always') as 'always' | 'expression'
  if (conditionType === 'expression' && !(data.conditionExpr as string | undefined)?.trim()) {
    notifyError('请输入条件表达式')
    done(false)
    return
  }
  const edge: LocalEdge = {
    sourceNodeKey: source,
    targetNodeKey: target,
    conditionType,
    conditionExpr: conditionType === 'expression' ? ((data.conditionExpr as string) || '').trim() : '',
  }
  if (editingEdgeIndex.value !== null) {
    edges.value.splice(editingEdgeIndex.value, 1, edge)
  } else {
    edges.value.push(edge)
  }
  done()
}

function handleEdgeDelete(edge: LocalEdge) {
  edges.value = edges.value.filter((e) => e !== edge)
}

// ---------------- 运行（仅已保存的工作流） ----------------

const startNode = computed(() => nodes.value.find((n) => n.nodeType === 'start'))
const startVariables = computed<StartVariable[]>(
  () => ((startNode.value?.config.variables as StartVariable[] | undefined) ?? []),
)

const runInputs = ref<Record<string, string>>({})
const running = ref(false)
const currentRun = ref<WorkflowRun | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

async function handleRun() {
  if (!startNode.value) {
    notifyError('图里还没有 start 节点，先保存编排')
    return
  }
  for (const v of startVariables.value) {
    if (v.required && !(runInputs.value[v.name] ?? '').trim()) {
      notifyError(`必填变量「${v.name}」未填写`)
      return
    }
  }
  running.value = true
  currentRun.value = null
  try {
    const runId = await runWorkflow(workflowId!, { inputs: { ...runInputs.value } })
    pollRun(runId)
  } catch {
    running.value = false
  }
}

/** 2s 轮询到终态（复用知识库文档状态轮询的节奏） */
function pollRun(runId: number) {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollTimer = setTimeout(async () => {
    try {
      const run = await getWorkflowRun(runId)
      currentRun.value = run
      if (run.status === 'running') {
        pollRun(runId)
      } else {
        running.value = false
        loadRunHistory()
      }
    } catch {
      running.value = false
    }
  }, 2000)
}

onBeforeUnmount(() => {
  if (pollTimer) {
    clearTimeout(pollTimer)
  }
})

async function showHistoryRun(row: WorkflowRunSummary) {
  currentRun.value = await getWorkflowRun(row.id)
}

// ---------------- 运行历史 ----------------

const runHistory = ref<WorkflowRunSummary[]>([])

async function loadRunHistory() {
  if (isNew) {
    return
  }
  runHistory.value = await listWorkflowRuns(workflowId!, 20)
}

function statusTag(status: string): 'success' | 'danger' | 'warning' | 'info' {
  switch (status) {
    case 'succeeded':
      return 'success'
    case 'failed':
      return 'danger'
    case 'running':
      return 'warning'
    default:
      return 'info'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'succeeded':
      return '成功'
    case 'failed':
      return '失败'
    case 'running':
      return '运行中'
    default:
      return status
  }
}

function formatDateTime(iso: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

// ---------------- 初始化 ----------------

onMounted(async () => {
  loadModelOptions()
  await load()
  if (startVariables.value.length && !Object.keys(runInputs.value).length) {
    for (const v of startVariables.value) {
      runInputs.value[v.name] = ''
    }
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="page-header-left">
        <el-button link class="back-btn" @click="router.push('/workflow')">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <div>
          <h1 class="page-title">{{ isNew ? '新增工作流' : meta.name || '…' }}</h1>
          <p class="page-desc">{{ isNew ? '先编排节点与连线，保存后即可运行' : meta.description || '暂无描述' }}</p>
        </div>
      </div>
      <el-button type="primary" class="page-create-btn" :loading="saving" @click="handleSave">
        {{ isNew ? '创建' : '保存编排' }}
      </el-button>
    </div>

    <div class="detail-grid">
      <div class="detail-col">
        <!-- 基础信息（新工作流内联编辑；已存在的在列表页编辑） -->
        <div v-if="isNew" class="page-card meta-card">
          <h2 class="card-title">基础信息</h2>
          <el-form label-width="72px">
            <el-form-item label="名称">
              <el-input v-model="meta.name" placeholder="如 客服工单分类" maxlength="64" clearable />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="meta.description" placeholder="一句话说明用途，可选" maxlength="255" clearable />
            </el-form-item>
          </el-form>
        </div>

        <!-- 编排：节点 -->
        <div class="page-card">
          <div class="card-head">
            <h2 class="card-title">节点（{{ nodes.length }}）</h2>
            <el-button size="small" @click="openNodeCreate">
              <el-icon><Plus /></el-icon>
              新增节点
            </el-button>
          </div>
          <el-table :data="nodes" empty-text="暂无节点，点击「新增节点」开始编排">
            <el-table-column label="nodeKey" width="140">
              <template #default="{ row }">
                <span class="page-time">{{ row.nodeKey }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-tag :type="nodeTypeTag(row.nodeType).type" effect="light" disable-transitions>
                  {{ nodeTypeTag(row.nodeType).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="名称" prop="name" width="130" show-overflow-tooltip />
            <el-table-column label="配置摘要" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="config-summary">{{ configSummary(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openNodeEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="handleNodeDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 编排：连线 -->
        <div class="page-card">
          <div class="card-head">
            <h2 class="card-title">连线（{{ edges.length }}）</h2>
            <el-button size="small" @click="openEdgeCreate">
              <el-icon><Plus /></el-icon>
              新增连线
            </el-button>
          </div>
          <el-table :data="edges" empty-text="暂无连线">
            <el-table-column label="起点" width="160">
              <template #default="{ row }">
                <span class="page-time">{{ row.sourceNodeKey }}</span>
              </template>
            </el-table-column>
            <el-table-column label="终点" width="160">
              <template #default="{ row }">
                <span class="page-time">{{ row.targetNodeKey }}</span>
              </template>
            </el-table-column>
            <el-table-column label="条件" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <el-tag v-if="row.conditionType === 'always'" size="small" effect="plain" disable-transitions>总是</el-tag>
                <span v-else class="page-time expr">{{ row.conditionExpr }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdgeEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="handleEdgeDelete(row)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <!-- 右列：运行 + 历史（已保存的工作流才有） -->
      <div class="detail-col" v-if="!isNew && workflowLoaded">
        <div class="page-card">
          <h2 class="card-title">运行</h2>
          <p class="card-desc">填入 start 变量提交运行；节点轨迹与最终输出实时轮询展示</p>
          <template v-if="startVariables.length">
            <el-form label-width="90px">
              <el-form-item v-for="v in startVariables" :key="v.name" :label="v.name">
                <el-input
                  v-model="runInputs[v.name]"
                  :placeholder="v.required ? '必填' : '可选'"
                  maxlength="2000"
                  clearable
                />
              </el-form-item>
            </el-form>
          </template>
          <div v-else class="run-no-input">该工作流无输入变量，直接运行</div>
          <el-button
            type="primary"
            class="run-btn"
            :loading="running"
            :disabled="meta.enabled !== 1"
            @click="handleRun"
          >
            {{ running ? '运行中…' : '运行' }}
          </el-button>
          <div v-if="meta.enabled !== 1" class="run-disabled-tip">工作流已停用，启用后才能运行</div>

          <!-- 运行结果 -->
          <div v-if="currentRun" class="run-result">
            <div class="run-result-head">
              <el-tag :type="statusTag(currentRun.status)" effect="light" disable-transitions>
                {{ statusLabel(currentRun.status) }}
              </el-tag>
              <span class="page-time">耗时 {{ currentRun.latencyMs }}ms · {{ formatDateTime(currentRun.createdAt) }}</span>
            </div>
            <div v-if="currentRun.errorMsg" class="run-error">{{ currentRun.errorMsg }}</div>
            <div v-else class="run-output">{{ currentRun.output }}</div>
            <div class="trace-list">
              <div class="trace-title">执行轨迹</div>
              <div v-for="(t, i) in currentRun.traces" :key="t.nodeKey + i" class="trace-item">
                <div class="trace-meta">
                  <el-tag size="small" :type="nodeTypeTag(t.nodeType).type" effect="plain" disable-transitions>
                    {{ t.nodeType }}
                  </el-tag>
                  <span class="trace-name">{{ t.name }}</span>
                  <span class="trace-error" v-if="t.error">✗ {{ t.error }}</span>
                  <span class="page-time trace-latency">{{ t.latencyMs }}ms</span>
                </div>
                <div v-if="t.input" class="trace-io">{{ t.input }}</div>
                <div v-if="t.output" class="trace-io">{{ t.output }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="page-card">
          <h2 class="card-title">运行历史</h2>
          <el-table :data="runHistory" empty-text="暂无运行记录" @row-click="showHistoryRun" highlight-current-row>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)" effect="light" size="small" disable-transitions>
                  {{ statusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="输出 / 失败原因" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.errorMsg || row.output }}</span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">
                <span class="page-time">{{ row.latencyMs }}ms</span>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <span class="page-time">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <!-- 节点编辑弹窗 -->
    <HifyFormDialog
      ref="nodeDialogRef"
      v-model="nodeDialogVisible"
      :title="editingNodeKey ? '编辑节点' : '新增节点'"
      width="640px"
      :rules="nodeRules"
      @submit="handleNodeSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="nodeKey" prop="nodeKey">
          <el-input v-model="model.nodeKey" placeholder="如 judge / llm1 / start" :disabled="!!editingNodeKey" clearable />
          <div v-if="editingNodeKey" class="field-tip">连线按 nodeKey 引用，暂不支持改 key</div>
        </el-form-item>
        <el-form-item label="类型" prop="nodeType">
          <el-select v-model="model.nodeType" :disabled="!!editingNodeKey" style="width: 100%">
            <el-option v-for="t in NODE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="model.name" placeholder="展示名称" maxlength="64" clearable />
        </el-form-item>

        <!-- start：输入变量 -->
        <template v-if="model.nodeType === 'start'">
          <el-form-item label="输入变量">
            <div class="var-list">
              <div v-for="(v, i) in model.variables" :key="i" class="var-row">
                <el-input v-model="v.name" placeholder="变量名，如 query" maxlength="64" class="var-name" />
                <el-checkbox v-model="v.required">必填</el-checkbox>
                <el-button link type="danger" @click="model.variables.splice(i, 1)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
              <el-button link type="primary" @click="model.variables.push({ name: '', required: true })">
                + 添加变量
              </el-button>
              <div class="field-tip">运行时按变量名填入；prompt / 模板里用 ${节点Key.变量名} 引用，如 ${start.query}</div>
            </div>
          </el-form-item>
        </template>

        <!-- llm：模型 + prompt -->
        <template v-if="model.nodeType === 'llm'">
          <el-form-item label="模型" prop="modelConfigId">
            <el-select v-model="model.modelConfigId" placeholder="选择启用的对话模型" :loading="modelsLoading" style="width: 100%">
              <el-option-group v-for="group in modelGroups" :key="group.providerId" :label="group.label">
                <el-option v-for="opt in group.options" :key="opt.modelConfigId" :label="opt.label" :value="opt.modelConfigId" />
              </el-option-group>
              <template #footer>
                <div class="field-tip">
                  {{ modelGroups.length ? '' : '暂无可用模型：仅支持 openai / openai_compatible / claude 类提供商' }}
                </div>
              </template>
            </el-select>
          </el-form-item>
          <el-form-item label="Prompt" prop="prompt">
            <el-input
              v-model="model.prompt"
              type="textarea"
              :rows="6"
              maxlength="8000"
              show-word-limit
              placeholder="任务指令，可用变量引用"
            />
            <div v-if="availableRefs.length" class="field-tip">可引用：{{ availableRefs.map((r) => '${' + r + '}').join('  ') }}</div>
          </el-form-item>
          <el-form-item label="温度" prop="temperature">
            <el-input-number v-model="model.temperature" :min="0" :max="2" :step="0.1" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="回复上限" prop="maxTokens">
            <el-input-number v-model="model.maxTokens" :min="0" :step="256" placeholder="0=模型默认" controls-position="right" style="width: 100%" />
          </el-form-item>
        </template>

        <!-- condition：提示 -->
        <template v-if="model.nodeType === 'condition'">
          <div class="condition-tip">
            条件节点按上游 LLM 输出路由：出边表达式（如 ${judge.output} == "yes"）按顺序求值取首个命中，
            always 为兜底。条件配在「连线」上，此处无需配置。
          </div>
        </template>

        <!-- end：输出模板 -->
        <template v-if="model.nodeType === 'end'">
          <el-form-item label="输出模板" prop="outputTemplate">
            <el-input
              v-model="model.outputTemplate"
              type="textarea"
              :rows="3"
              maxlength="2000"
              placeholder="如 分类结果：${judge.output}"
            />
            <div v-if="availableRefs.length" class="field-tip">可引用：{{ availableRefs.map((r) => '${' + r + '}').join('  ') }}</div>
          </el-form-item>
        </template>
      </template>
    </HifyFormDialog>

    <!-- 连线编辑弹窗 -->
    <HifyFormDialog
      ref="edgeDialogRef"
      v-model="edgeDialogVisible"
      :title="editingEdgeIndex !== null ? '编辑连线' : '新增连线'"
      width="560px"
      :rules="edgeRules"
      @submit="handleEdgeSubmit"
    >
      <template #default="{ model }">
        <el-form-item label="起点" prop="sourceNodeKey">
          <el-select v-model="model.sourceNodeKey" placeholder="选择起点节点" style="width: 100%">
            <el-option v-for="n in nodes" :key="n.nodeKey" :label="`${n.nodeKey}（${n.name}）`" :value="n.nodeKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="终点" prop="targetNodeKey">
          <el-select v-model="model.targetNodeKey" placeholder="选择终点节点" style="width: 100%">
            <el-option v-for="n in nodes" :key="n.nodeKey" :label="`${n.nodeKey}（${n.name}）`" :value="n.nodeKey" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="sourceIsCondition(model)" label="条件" prop="conditionType">
          <el-radio-group v-model="model.conditionType">
            <el-radio value="expression">表达式满足</el-radio>
            <el-radio value="always">总是（兜底）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="sourceIsCondition(model) && model.conditionType === 'expression'" label="表达式" prop="conditionExpr">
          <el-input
            v-model="model.conditionExpr"
            placeholder='如 ${judge.output} == "yes"（支持 == / != / contains / 非空）'
            maxlength="512"
            clearable
          />
          <div class="field-tip">求值按连线顺序取首个命中；值为上游 LLM 输出或 start 变量</div>
        </el-form-item>
        <div v-else-if="sourceIsCondition(model)" class="field-tip">兜底边：所有表达式都不满足时走这条</div>
        <div v-else class="field-tip">非 condition 节点的出边为无条件直连</div>
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

:deep(.page-create-btn.el-button--primary:hover),
.page-create-btn.el-button--primary:hover {
  box-shadow: var(--hify-shadow-primary);
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(320px, 2fr);
  gap: var(--hify-space-5);
  align-items: start;
}

.detail-col {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-5);
  min-width: 0;
}

.page-card {
  background: var(--hify-bg-surface);
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-lg);
  padding: var(--hify-space-5);
  box-shadow: var(--hify-shadow-xs);
}

.meta-card :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--hify-space-4);
}

.card-head .card-title {
  margin-bottom: 0;
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

.config-summary {
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
}

.expr {
  font-size: var(--hify-text-xs);
}

.field-tip {
  width: 100%;
  margin-top: var(--hify-space-1);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  line-height: 1.6;
}

/* start 变量编辑 */
.var-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-2);
}

.var-row {
  display: flex;
  align-items: center;
  gap: var(--hify-space-3);
}

.var-name {
  flex: 1;
}

.condition-tip {
  padding: var(--hify-space-3);
  border: 1px dashed var(--hify-border);
  border-radius: var(--hify-radius-md);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  line-height: 1.7;
  margin-bottom: var(--hify-space-4);
}

/* 运行面板 */
.run-no-input {
  margin-bottom: var(--hify-space-3);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.run-btn {
  margin-top: var(--hify-space-2);
}

.run-disabled-tip {
  margin-top: var(--hify-space-2);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.run-result {
  margin-top: var(--hify-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-3);
}

.run-result-head {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
}

.run-error {
  padding: var(--hify-space-3);
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-md);
  background: var(--hify-bg-subtle);
  font-size: var(--hify-text-sm);
  color: var(--hify-danger);
  white-space: pre-wrap;
  word-break: break-word;
}

.run-output {
  padding: var(--hify-space-3);
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-md);
  background: var(--hify-bg-subtle);
  font-size: var(--hify-text-sm);
  color: var(--hify-text-2);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
}

.trace-list {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-2);
}

.trace-title {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.trace-item {
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-md);
  padding: var(--hify-space-3);
  background: var(--hify-bg-subtle);
}

.trace-meta {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
  margin-bottom: var(--hify-space-2);
}

.trace-name {
  font-size: var(--hify-text-sm);
  color: var(--hify-text-1);
}

.trace-error {
  font-size: var(--hify-text-xs);
  color: var(--hify-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-latency {
  margin-left: auto;
}

.trace-io {
  font-size: var(--hify-text-sm);
  color: var(--hify-text-2);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow-y: auto;
}
</style>
