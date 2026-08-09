<template>
  <RouterLink class="shop-card" :to="`/shops/${shop.id}`">
    <div class="shop-card__cover">
      <MediaCover :media="shopMedia" kind="shop" />
      <div class="shop-card__cover-shade" aria-hidden="true" />
      <span v-if="typeName" class="shop-card__cover-tag">{{ typeName }}</span>
    </div>
    <div class="shop-card__body">
      <div class="shop-card__title-row">
        <h3 class="shop-card__name">{{ shop.name }}</h3>
        <span v-if="shop.distance != null" class="shop-card__distance">
          <DistanceText :meters="shop.distance" />
        </span>
      </div>
      <p class="shop-card__meta">{{ shop.area }}{{ shop.address ? ` · ${shop.address}` : '' }}</p>
      <div class="shop-card__stats">
        <span class="shop-card__score">评分 {{ formatScore(shop.score) }}</span>
        <span class="shop-card__stat">已售 {{ shop.sold }}</span>
        <span class="shop-card__stat">{{ shop.comments }} 条评论</span>
        <span class="shop-card__price">
          人均 <YuanPriceText :yuan="shop.avgPrice" />
          <span class="shop-card__unit"> / 人</span>
        </span>
      </div>
      <p v-if="shop.openHours" class="shop-card__hours">营业 {{ shop.openHours }}</p>
    </div>
  </RouterLink>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MediaCover from '@/components/common/MediaCover.vue'
import YuanPriceText from '@/components/common/YuanPriceText.vue'
import DistanceText from '@/components/common/DistanceText.vue'
import { formatScore } from '@/utils/format'
import { resolveShopMedia } from '@/utils/media'
import type { Shop } from '@/types/api'

const props = defineProps<{
  shop: Shop
}>()

const shopMedia = computed(() => resolveShopMedia(props.shop))
const typeName = computed(() => (props.shop.typeId === 1 ? '美食' : props.shop.typeId === 2 ? 'KTV' : ''))
</script>

<style scoped>
.shop-card {
  display: block;
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  overflow: hidden;
  box-shadow: var(--ll-shadow-sm);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.shop-card__cover {
  position: relative;
}

.shop-card__cover-shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 58%, rgba(20, 24, 31, 0.18));
  pointer-events: none;
}

.shop-card__cover-tag {
  position: absolute;
  top: 12px;
  left: 12px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: rgba(20, 24, 31, 0.42);
  border-radius: 999px;
  padding: 4px 10px;
}

.shop-card:hover,
.shop-card:focus-visible {
  transform: translateY(-3px);
  box-shadow: var(--ll-shadow-md);
  outline: none;
}

.shop-card__body {
  padding: var(--ll-space-sm) var(--ll-space-sm) var(--ll-space-md);
}

.shop-card__title-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.shop-card__name {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-card__meta {
  margin: 6px 0 10px;
  color: var(--ll-text-secondary);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-card__stats {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--ll-text-secondary);
}

.shop-card__score {
  color: var(--ll-warning);
  font-weight: 700;
}

.shop-card__price {
  margin-left: auto;
}

.shop-card__unit {
  color: var(--ll-text-secondary);
  font-size: 12px;
}

.shop-card__hours {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ll-text-secondary);
}
</style>
