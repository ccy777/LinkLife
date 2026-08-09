<template>
  <div class="empty-state">
    <div class="empty-state__icon" aria-hidden="true">
      <el-icon :size="30"><component :is="icon" /></el-icon>
    </div>
    <p class="empty-state__text">{{ text }}</p>
    <slot />
  </div>
</template>

<script setup lang="ts">
import { Box } from '@element-plus/icons-vue'
import type { Component } from 'vue'

withDefaults(
  defineProps<{
    text: string
    icon?: Component
  }>(),
  { icon: () => Box },
)
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 56px 24px;
  color: var(--ll-text-secondary);
  text-align: center;
}

.empty-state__icon {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  display: grid;
  place-items: center;
  background: var(--ll-grad-violet);
  color: var(--ll-primary);
  margin-bottom: 14px;
}

.empty-state__text {
  margin: 0;
  font-size: 15px;
}
</style>
