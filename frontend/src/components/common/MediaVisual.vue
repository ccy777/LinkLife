<template>
  <div
    class="media-visual"
    :class="`media-visual--${kind}`"
    :style="{ background: gradientFor(seed) }"
    role="img"
    :aria-label="label"
  >
    <svg
      v-if="kind !== 'user'"
      class="media-visual__pattern"
      viewBox="0 0 200 120"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
    >
      <circle :cx="orbits[0].x" :cy="orbits[0].y" :r="orbits[0].r" fill="#6C63FF" opacity="0.10" />
      <circle :cx="orbits[1].x" :cy="orbits[1].y" :r="orbits[1].r" fill="#14B8A6" opacity="0.12" />
      <circle :cx="orbits[2].x" :cy="orbits[2].y" :r="orbits[2].r" fill="#FFFFFF" opacity="0.35" />
      <path :d="arc" stroke="#6C63FF" stroke-opacity="0.18" stroke-width="2" fill="none" />
      <path :d="grid" stroke="#6C63FF" stroke-opacity="0.08" stroke-width="1" fill="none" />
    </svg>

    <div class="media-visual__content">
      <span v-if="kind !== 'user'" class="media-visual__badge">
        <el-icon :size="iconSize"><component :is="iconName" /></el-icon>
      </span>
      <span class="media-visual__letter">{{ initial }}</span>
      <span v-if="kind === 'shop'" class="media-visual__kind">商铺</span>
      <span v-else-if="kind === 'blog'" class="media-visual__kind">动态</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  Shop,
  Food,
  OfficeBuilding,
  Present,
  Basketball,
  Scissor,
  CoffeeCup,
  Camera,
  User,
} from '@element-plus/icons-vue'
import { gradientFor, hashSeed } from '@/utils/media'

const props = withDefaults(
  defineProps<{
    seed: string
    label: string
    kind?: 'shop' | 'blog' | 'user' | 'type'
    typeId?: number
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { kind: 'shop', size: 'md' },
)

const typeIcons = [Food, OfficeBuilding, Present, Basketball, Scissor, CoffeeCup, Camera, User]

const iconName = computed(() => {
  if (props.kind === 'shop') return Shop
  if (props.kind === 'blog') return Camera
  if (props.kind === 'user') return User
  const idx = props.typeId != null ? props.typeId : hashSeed(props.seed)
  return typeIcons[idx % typeIcons.length]
})

const initial = computed(() => {
  const t = props.label.trim()
  return t ? t.slice(0, 1) : 'L'
})

const iconSize = computed(() => (props.size === 'sm' ? 22 : props.size === 'lg' ? 42 : 32))

const orbits = computed(() => {
  const h = hashSeed(props.seed)
  const spots = [
    { x: 18 + (h % 70), y: 14 + ((h >> 3) % 34), r: 16 + ((h >> 5) % 12) },
    { x: 130 + ((h >> 7) % 55), y: 24 + ((h >> 9) % 40), r: 12 + ((h >> 11) % 10) },
    { x: 60 + ((h >> 13) % 80), y: 66 + ((h >> 15) % 26), r: 10 + ((h >> 17) % 8) },
  ]
  return spots
})

const arc = computed(() => {
  const h = hashSeed(`${props.seed}:arc`)
  const x1 = 10 + (h % 60)
  const y1 = 20 + ((h >> 3) % 40)
  const x2 = 150 + ((h >> 7) % 40)
  const y2 = 60 + ((h >> 11) % 40)
  return `M${x1} ${y1} Q${(x1 + x2) / 2} ${y1 - 36} ${x2} ${y2}`
})

const grid = computed(() => {
  const h = hashSeed(`${props.seed}:grid`)
  const x = 12 + (h % 90)
  const y = 8 + ((h >> 4) % 24)
  return `M${x} ${y} L${x + 96} ${y} M${x} ${y + 10} L${x + 96} ${y + 10}`
})
</script>

<style scoped>
.media-visual {
  position: relative;
  display: grid;
  place-items: center;
  color: var(--ll-primary);
  overflow: hidden;
}

.media-visual__pattern {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.media-visual__content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  z-index: 1;
}

.media-visual--shop {
  height: 180px;
  width: 100%;
  border-radius: var(--ll-radius-md);
}

.media-visual--blog {
  height: 140px;
  width: 100%;
  border-radius: var(--ll-radius-md);
}

.media-visual--user {
  border-radius: 50%;
  width: 40px;
  height: 40px;
}

.media-visual--type {
  width: 64px;
  height: 64px;
  border-radius: 20px;
}

.media-visual__badge {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 6px 18px rgba(20, 24, 31, 0.08);
  color: var(--ll-primary);
}

.media-visual--type .media-visual__badge {
  width: 44px;
  height: 44px;
  border-radius: 14px;
}

.media-visual--blog .media-visual__badge {
  color: var(--ll-secondary);
}

.media-visual__letter {
  font-size: 22px;
  font-weight: 750;
  color: var(--ll-text);
  background: rgba(255, 255, 255, 0.7);
  border-radius: 10px;
  padding: 2px 10px;
}

.media-visual--user .media-visual__letter {
  font-size: 15px;
  background: transparent;
  padding: 0;
  color: var(--ll-primary);
}

.media-visual__kind {
  font-size: 11px;
  font-weight: 600;
  color: rgba(108, 99, 255, 0.9);
  background: rgba(255, 255, 255, 0.7);
  border-radius: 999px;
  padding: 2px 10px;
}
</style>
