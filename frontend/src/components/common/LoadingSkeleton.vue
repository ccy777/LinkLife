<template>
  <div class="skeleton-grid" :class="{ 'skeleton-grid--list': variant === 'list' }">
    <div v-for="i in count" :key="i" class="skeleton-card">
      <div class="skeleton-card__media" />
      <div class="skeleton-card__body">
        <div class="skeleton-line skeleton-line--title" />
        <div class="skeleton-line" />
        <div class="skeleton-line skeleton-line--short" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    count?: number
    variant?: 'grid' | 'list'
  }>(),
  { count: 6, variant: 'grid' },
)
</script>

<style scoped>
.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--ll-space-md);
}

.skeleton-grid--list {
  grid-template-columns: 1fr;
}

.skeleton-card {
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  overflow: hidden;
}

.skeleton-card__media {
  height: 160px;
  background: linear-gradient(90deg, #eef0f6 25%, #f7f8fb 50%, #eef0f6 75%);
  background-size: 200% 100%;
  animation: shimmer 1.3s infinite;
}

.skeleton-card__body {
  padding: 16px;
}

.skeleton-line {
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef0f6 25%, #f7f8fb 50%, #eef0f6 75%);
  background-size: 200% 100%;
  animation: shimmer 1.3s infinite;
  margin-bottom: 10px;
}

.skeleton-line--title {
  width: 65%;
  height: 16px;
}

.skeleton-line--short {
  width: 40%;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

@media (max-width: 1024px) {
  .skeleton-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .skeleton-grid {
    grid-template-columns: 1fr;
  }
}
</style>
