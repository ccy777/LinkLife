<template>
  <img
    v-if="realUrl"
    class="user-avatar"
    :src="realUrl"
    :alt="nickname || 'user'"
  />
  <div v-else class="user-avatar user-avatar--fallback" aria-hidden="true">
    {{ initial }}
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { resolveImageUrl } from '@/utils/media'

const props = defineProps<{
  icon: string | null | undefined
  nickname: string
}>()

const realUrl = computed(() => resolveImageUrl(props.icon))
const initial = computed(() => (props.nickname || 'L').slice(0, 1).toUpperCase())
</script>

<style scoped>
.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
}

.user-avatar--fallback {
  display: grid;
  place-items: center;
  background: var(--ll-grad-violet);
  color: var(--ll-primary);
  font-weight: 700;
}
</style>
