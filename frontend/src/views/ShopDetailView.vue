<template>
  <div class="page shop-detail">
    <div class="container">
      <LoadingSkeleton v-if="loading" :count="2" variant="list" />
      <RetryError v-else-if="error" :message="error" @retry="load" />

      <template v-else-if="shop">
        <section class="shop-detail__hero">
          <div class="shop-detail__media">
            <MediaCover :media="shopMedia" kind="shop" size="lg" fill />
            <span v-if="typeName" class="shop-detail__media-tag">{{ typeName }}</span>
          </div>
          <div class="shop-detail__info">
            <h1 class="shop-detail__name">{{ shop.name }}</h1>
            <p class="shop-detail__meta">
              <span>{{ shop.area }}</span>
              <span v-if="shop.address">· {{ shop.address }}</span>
            </p>
            <p class="shop-detail__meta">营业 {{ shop.openHours }}</p>
            <div class="shop-detail__stats">
              <span class="shop-detail__score">评分 {{ formatScore(shop.score) }}</span>
              <span>已售 {{ shop.sold }}</span>
              <span>{{ shop.comments }} 条评论</span>
              <span class="shop-detail__price">
                人均 <YuanPriceText :yuan="shop.avgPrice" />
                <span class="text-secondary">/ 人</span>
              </span>
            </div>
          </div>
        </section>

        <section class="shop-detail__offers">
          <h2 class="section-title">限时优惠</h2>
          <LoadingSkeleton v-if="loadingVouchers" :count="2" variant="list" />
          <EmptyState v-else-if="vouchers.length === 0" text="暂无可用优惠" />
          <div v-else class="shop-detail__vouchers">
            <VoucherCard v-for="v in vouchers" :key="v.id" :voucher="v" @grab="grab" />
          </div>
        </section>
      </template>
    </div>

    <SubmissionDrawer :open="submissionOpen" :voucher="activeVoucher" @close="submissionOpen = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MediaCover from '@/components/common/MediaCover.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import RetryError from '@/components/common/RetryError.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import YuanPriceText from '@/components/common/YuanPriceText.vue'
import VoucherCard from '@/components/shop/VoucherCard.vue'
import SubmissionDrawer from '@/components/voucher/SubmissionDrawer.vue'
import { useAuthStore } from '@/stores/auth'
import { formatScore } from '@/utils/format'
import { resolveShopMedia, type MediaDescriptor } from '@/utils/media'
import * as shopApi from '@/api/shop'
import * as voucherApi from '@/api/voucher'
import type { Shop, Voucher } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const shop = ref<Shop | null>(null)
const vouchers = ref<Voucher[]>([])
const loading = ref(true)
const loadingVouchers = ref(false)
const error = ref('')
const submissionOpen = ref(false)
const activeVoucher = ref<Voucher | null>(null)

const shopId = computed(() => Number(route.params.id))
const shopMedia = computed<MediaDescriptor>(() =>
  shop.value
    ? resolveShopMedia(shop.value)
    : { type: 'fallback', seed: 'shop:0', label: '' },
)
const typeName = computed(() => (shop.value?.typeId === 1 ? '美食' : shop.value?.typeId === 2 ? 'KTV' : ''))

async function load() {
  loading.value = true
  error.value = ''
  try {
    shop.value = await shopApi.fetchShop(shopId.value)
    await loadVouchers()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载商铺失败'
  } finally {
    loading.value = false
  }
}

async function loadVouchers() {
  loadingVouchers.value = true
  try {
    vouchers.value = await voucherApi.fetchVouchers(shopId.value)
  } catch {
    vouchers.value = []
  } finally {
    loadingVouchers.value = false
  }
}

function grab(v: Voucher) {
  if (!auth.isAuthenticated) {
    void router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (v.type !== 1) return
  activeVoucher.value = v
  submissionOpen.value = true
}

onMounted(load)
</script>

<style scoped>
.shop-detail__hero {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: var(--ll-space-lg);
  align-items: center;
}

.shop-detail__media {
  position: relative;
  min-height: 260px;
  border-radius: var(--ll-radius-lg);
  overflow: hidden;
}

.shop-detail__media :deep(.media-visual--shop) {
  height: 100%;
  min-height: 260px;
}

.shop-detail__media-tag {
  position: absolute;
  top: 16px;
  left: 16px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: rgba(20, 24, 31, 0.42);
  border-radius: 999px;
  padding: 5px 12px;
}

.shop-detail__info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.shop-detail__name {
  margin: 0;
  font-size: 34px;
  letter-spacing: -0.03em;
}

.shop-detail__meta {
  margin: 0;
  color: var(--ll-text-secondary);
  font-size: 15px;
}

.shop-detail__stats {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 14px;
  color: var(--ll-text-secondary);
}

.shop-detail__score {
  color: var(--ll-warning);
  font-weight: 700;
}

.shop-detail__offers {
  margin-top: var(--ll-space-xl);
}

.shop-detail__vouchers {
  display: flex;
  flex-direction: column;
  gap: var(--ll-space-sm);
}

@media (max-width: 768px) {
  .shop-detail__hero {
    grid-template-columns: 1fr;
  }

  .shop-detail__media {
    min-height: 180px;
  }
}
</style>
