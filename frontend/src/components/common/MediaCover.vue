<template>
  <img
    v-if="display.type === 'remote' || display.type === 'demo'"
    class="media-cover"
    :class="[`media-cover--${kind}`, { 'media-cover--fill': fill }]"
    :src="display.url"
    :alt="display.alt"
    loading="lazy"
    @error="failed = true"
  />
  <MediaVisual
    v-else
    class="media-cover__fallback"
    :seed="display.seed"
    :label="display.label"
    :kind="kind"
    :size="size"
  />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import MediaVisual from '@/components/common/MediaVisual.vue'
import type { MediaDescriptor } from '@/utils/media'

const props = withDefaults(
  defineProps<{
    media: MediaDescriptor
    kind: 'shop' | 'blog'
    size?: 'sm' | 'md' | 'lg'
    fill?: boolean
  }>(),
  { size: 'md', fill: false },
)

const failed = ref(false)

const display = computed<MediaDescriptor>(() => {
  if (!failed.value && (props.media.type === 'remote' || props.media.type === 'demo')) {
    return props.media
  }
  if (props.media.type === 'fallback') return props.media
  return { type: 'fallback', seed: 'fallback', label: '' }
})

watch(
  () => props.media,
  () => {
    failed.value = false
  },
)
</script>

<style scoped>
.media-cover {
  width: 100%;
  object-fit: cover;
  display: block;
}

.media-cover--shop {
  height: 180px;
}

.media-cover--blog {
  height: 140px;
}

.media-cover--fill {
  height: 100%;
}

.media-cover__fallback {
  width: 100%;
}
</style>
