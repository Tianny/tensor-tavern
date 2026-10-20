<script setup lang="ts" generic="T">
import { onMounted, ref, watch } from 'vue'

import type { HifyTableApi, HifyTableColumn } from '@/types/components'

/**
 * 通用列表页表格：列配置 + 数据源 API，内部自动管理 loading / 分页 / 请求。
 *
 * 用法示例：
 *   <HifyTable ref="tableRef" :columns="columns" :api="listProviders">
 *     <template #status="{ row }">
 *       <el-tag :type="row.status === 1 ? 'success' : 'info'">…</el-tag>
 *     </template>
 *   </HifyTable>
 *
 *   const tableRef = ref()
 *   tableRef.value?.refresh()          // 删除/保存后刷新当前页
 *   tableRef.value?.refresh(true)      // 查询条件变化后回到第 1 页
 */
const props = withDefaults(
  defineProps<{
    /** 列配置（自定义单元格用 slot 指向同名作用域插槽） */
    columns: HifyTableColumn[]
    /** 数据源：接收分页 + 查询参数，返回 PageResult */
    api: HifyTableApi<T>
    /** 是否显示分页器（默认 true） */
    showPagination?: boolean
    /** 查询条件（深层变化自动回到第 1 页重新请求） */
    params?: Record<string, unknown>
    /** 空状态文案 */
    emptyText?: string
  }>(),
  { showPagination: true, emptyText: '暂无数据' },
)

const loading = ref(false)
const list = ref<T[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const PAGE_SIZES = [10, 20, 50, 100]

/** 快速翻页时丢弃过期响应，只认最后一次请求 */
let requestId = 0

async function fetchPage() {
  const id = ++requestId
  loading.value = true
  try {
    const result = await props.api({ ...props.params, page: page.value, pageSize: pageSize.value })
    if (id !== requestId) return
    list.value = result.list ?? []
    total.value = result.total ?? 0
    // 删除末页最后一条后自动回退一页，避免停在空页
    if (list.value.length === 0 && page.value > 1 && total.value > 0) {
      page.value -= 1
      fetchPage()
      return
    }
  } finally {
    if (id === requestId) loading.value = false
  }
}

/**
 * 刷新列表：默认重新请求当前页；resetPage = true 时回到第 1 页。
 */
function refresh(resetPage = false) {
  if (resetPage) page.value = 1
  fetchPage()
}

function onCurrentChange(current: number) {
  page.value = current
  fetchPage()
}

function onSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  fetchPage()
}

// 查询条件变化：回到第 1 页重新请求
watch(
  () => props.params,
  () => {
    page.value = 1
    fetchPage()
  },
  { deep: true },
)

onMounted(fetchPage)

/** list 暴露给外部读取（如拉取远端模型时比对已有条目），不要直接改它 */
defineExpose({ refresh, loading, list })
</script>

<template>
  <div class="hify-table">
    <el-table v-loading="loading" :data="list" stripe class="hify-table__body">
      <el-table-column
        v-for="col in columns"
        :key="col.prop ?? col.label"
        :label="col.label"
        :prop="col.prop"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align"
        :fixed="col.fixed"
        :show-overflow-tooltip="col.showOverflowTooltip"
      >
        <!-- 注意：自定义单元格必须经 #default 转发。写成 #[col.slot] 动态插槽名时
             el-table-column 收到的是具名插槽（它只消费 default），单元格会静默渲染为空 -->
        <template v-if="col.slot" #default="scope">
          <slot :name="col.slot" v-bind="scope" />
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="emptyText" :image-size="80" />
      </template>
    </el-table>
    <el-pagination
      v-if="showPagination && total > 0"
      v-model:current-page="page"
      v-model:page-size="pageSize"
      class="hify-table__pagination"
      :total="total"
      :page-sizes="PAGE_SIZES"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="onCurrentChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<style scoped>
.hify-table__body {
  border-radius: var(--hify-radius-lg);
}

.hify-table__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--hify-space-4);
}
</style>
