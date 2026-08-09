<template>
  <div class="stat-card">
    <p class="stat-card__value">{{ value }}</p>
    <p class="stat-card__label">{{ label }}</p>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  value: string | number
  label: string
}>()
</script>

<style scoped>
.stat-card {
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  padding: var(--ll-space-md);
  text-align: center;
}

.stat-card__value {
  margin: 0 0 4px;
  font-size: 30px;
  font-weight: 750;
  letter-spacing: -0.02em;
  color: var(--ll-primary);
}

.stat-card__label {
  margin: 0;
  font-size: 13px;
  color: var(--ll-text-secondary);
}
</style>
