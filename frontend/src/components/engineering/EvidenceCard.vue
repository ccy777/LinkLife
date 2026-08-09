<template>
  <article class="evidence-card">
    <div class="evidence-card__head">
      <h4>{{ title }}</h4>
      <span class="evidence-card__result">{{ result }}</span>
    </div>
    <p class="evidence-card__detail">{{ detail }}</p>
    <slot />
  </article>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  result: string
  detail: string
}>()
</script>

<style scoped>
.evidence-card {
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  padding: var(--ll-space-md);
}

.evidence-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.evidence-card__head h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}

.evidence-card__result {
  font-weight: 700;
  font-size: 13px;
  color: var(--ll-secondary);
}

.evidence-card__detail {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--ll-text-secondary);
  line-height: 1.5;
}
</style>
