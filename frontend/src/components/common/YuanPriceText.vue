<template>
  <span class="yuan-price">
    <span class="yuan-price__currency">¥</span>
    {{ yuan }}
  </span>
</template>

<script setup lang="ts">
defineProps<{
  yuan: number
}>()
</script>

<style scoped>
.yuan-price {
  font-weight: 700;
  color: var(--ll-text);
}

.yuan-price__currency {
  font-size: 0.82em;
  margin-right: 1px;
  color: var(--ll-primary);
}
</style>
