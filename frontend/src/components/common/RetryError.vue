<template>
  <div class="retry-error">
    <div class="retry-error__icon" aria-hidden="true">
      <el-icon :size="26"><WarningFilled /></el-icon>
    </div>
    <p class="retry-error__message">{{ message }}</p>
    <el-button type="primary" plain @click="$emit('retry')">重试</el-button>
  </div>
</template>

<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'

defineProps<{
  message: string
}>()

defineEmits<{
  retry: []
}>()
</script>

<style scoped>
.retry-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 56px 24px;
  text-align: center;
}

.retry-error__icon {
  width: 56px;
  height: 56px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: var(--ll-primary-soft);
  color: var(--ll-primary);
  margin-bottom: 14px;
}

.retry-error__message {
  color: var(--ll-text-secondary);
  margin: 0 0 18px;
}
</style>
