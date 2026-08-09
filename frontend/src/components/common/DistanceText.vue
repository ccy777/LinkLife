<template>
  <span class="distance">
    <el-icon><Location /></el-icon>
    {{ formatDistance(meters) }}
  </span>
</template>

<script setup lang="ts">
import { Location } from '@element-plus/icons-vue'
import { formatDistance } from '@/utils/format'

defineProps<{
  meters: number | null | undefined
}>()
</script>

<style scoped>
.distance {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ll-text-secondary);
  font-size: 13px;
}
</style>
