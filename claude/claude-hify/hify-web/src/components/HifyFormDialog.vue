<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'

import type { HifyFormDialogDone } from '@/types/components'

/**
 * 通用表单弹窗：v-model 控制显示，open(data?) 打开（带 data 为编辑、不带为新增），
 * 关闭时自动重置表单。提交校验通过后触发 submit 事件，API 调用由父组件处理。
 *
 * 表单项写在默认插槽里，通过作用域插槽拿到表单模型：
 *   <HifyFormDialog v-model="visible" ref="dialogRef" title="新增 Provider" :rules="rules"
 *     @submit="onSubmit">
 *     <template #default="{ model }">
 *       <el-form-item label="名称" prop="name">
 *         <el-input v-model="model.name" />
 *       </el-form-item>
 *     </template>
 *   </HifyFormDialog>
 *
 *   const dialogRef = ref()
 *   dialogRef.value?.open()        // 新增
 *   dialogRef.value?.open(row)     // 编辑（内部深拷贝，不影响行数据）
 *
 *   async function onSubmit(data: Record<string, unknown>, done: HifyFormDialogDone) {
 *     try {
 *       await save(data)
 *       done()          // 成功：结束 loading 并关闭弹窗
 *     } catch {
 *       done(false)     // 失败：仅结束 loading，弹窗保留（错误提示已由拦截器弹出）
 *     }
 *   }
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    width?: string
    rules?: FormRules
    labelWidth?: string
    confirmText?: string
    /** 嵌套在外层 el-dialog 里时挂到 body，避免层级/裁剪问题 */
    appendToBody?: boolean
  }>(),
  { width: '500px', labelWidth: '100px', confirmText: '确 定', appendToBody: false },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', data: Record<string, unknown>, done: HifyFormDialogDone): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const form = ref<Record<string, unknown>>({})
const submitting = ref(false)
const formRef = ref<FormInstance>()

/**
 * 打开弹窗。传 data 为编辑模式（深拷贝入表单，不改动原对象），不传为新增模式。
 */
function open(data?: Record<string, unknown>) {
  form.value = data ? (JSON.parse(JSON.stringify(data)) as Record<string, unknown>) : {}
  visible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

function close() {
  visible.value = false
}

/** 关闭后重置表单：同一弹窗被新增/编辑复用时互不残留 */
function resetForm() {
  form.value = {}
  nextTick(() => formRef.value?.clearValidate())
}

function handleSubmit() {
  formRef.value?.validate((valid) => {
    if (!valid) return
    submitting.value = true
    const done: HifyFormDialogDone = (success = true) => {
      submitting.value = false
      if (success) close()
    }
    emit('submit', form.value, done)
  })
}

defineExpose({ open, close })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    :width="width"
    :close-on-click-modal="false"
    :append-to-body="appendToBody"
    @closed="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" :label-width="labelWidth">
      <slot :model="form" />
    </el-form>
    <template #footer>
      <el-button @click="close">取 消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>
