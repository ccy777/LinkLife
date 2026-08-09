<template>
  <div class="page shops">
    <div class="container">
      <div class="shops__head">
        <h1 class="shops__title">商铺</h1>
        <div class="shops__controls">
          <el-input
            v-model="keyword"
            class="shops__search"
            placeholder="搜索商铺名称"
            clearable
            aria-label="搜索商铺"
            @keyup.enter="applySearch"
            @clear="applySearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button :loading="locating" @click="toggleLocation">
            <el-icon><Location /></el-icon>
            {{ located ? '附近已开启' : '附近' }}
          </el-button>
        </div>
      </div>

      <div class="shops__tabs">
        <button
          v-for="t in shopTypes"
          :key="t.id"
          type="button"
          class="shops__tab"
          :class="{ 'shops__tab--active': activeType === t.id }"
          @click="selectType(t.id)"
        >
          {{ t.name }}
        </button>
      </div>

      <LoadingSkeleton v-if="loading" :count="6" />
      <RetryError v-else-if="error" :message="error" @retry="reload" />
      <EmptyState v-else-if="shops.length === 0" text="暂时没有找到符合条件的商铺" />
      <div v-else class="shops__grid">
        <ShopCard v-for="shop in shops" :key="shop.id" :shop="shop" />
      </div>

      <div v-if="shops.length > 0 && hasMore" class="shops__more">
        <el-button :loading="loadingMore" @click="loadMore">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Location, Search } from '@element-plus/icons-vue'
import ShopCard from '@/components/shop/ShopCard.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import RetryError from '@/components/common/RetryError.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import * as shopApi from '@/api/shop'
import type { Shop, ShopType } from '@/types/api'

const PAGE_SIZE = 5
const route = useRoute()
const shopTypes = ref<ShopType[]>([])
const activeType = ref<number>(1)
const keyword = ref('')
const shops = ref<Shop[]>([])
const current = ref(1)
const loading = ref(true)
const loadingMore = ref(false)
const locating = ref(false)
const located = ref(false)
const coords = ref<{ x: number; y: number } | null>(null)
const error = ref('')

const hasMore = computed(() => shops.value.length > 0 && shops.value.length % PAGE_SIZE === 0)

async function loadTypes() {
  try {
    shopTypes.value = await shopApi.fetchShopTypes()
    const fromQuery = Number(route.query.typeId)
    if (fromQuery > 0) {
      activeType.value = fromQuery
    } else if (shopTypes.value.length > 0) {
      activeType.value = shopTypes.value[0].id
    }
  } catch {
    shopTypes.value = []
  }
}

async function reload() {
  current.value = 1
  error.value = ''
  loading.value = true
  try {
    if (keyword.value.trim()) {
      shops.value = await shopApi.searchShops(keyword.value.trim(), 1)
    } else {
      shops.value = await shopApi.fetchShopsByType(
        activeType.value,
        1,
        coords.value?.x,
        coords.value?.y,
      )
    }
  } catch (err) {
    shops.value = []
    error.value = err instanceof Error ? err.message : '加载商铺失败'
  } finally {
    loading.value = false
  }
}

async function selectType(id: number) {
  activeType.value = id
  await reload()
}

async function applySearch() {
  await reload()
}

async function loadMore() {
  loadingMore.value = true
  try {
    const next = current.value + 1
    const more = keyword.value.trim()
      ? await shopApi.searchShops(keyword.value.trim(), next)
      : await shopApi.fetchShopsByType(activeType.value, next, coords.value?.x, coords.value?.y)
    shops.value = [...shops.value, ...more]
    current.value = next
  } catch {
    // keep existing list
  } finally {
    loadingMore.value = false
  }
}

function toggleLocation() {
  if (located.value) {
    located.value = false
    coords.value = null
    void reload()
    return
  }
  if (!navigator.geolocation) {
    located.value = true
    void reload()
    return
  }
  locating.value = true
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      located.value = true
      locating.value = false
      coords.value = { x: pos.coords.longitude, y: pos.coords.latitude }
      void reload()
    },
    () => {
      located.value = true
      locating.value = false
      void reload()
    },
    { timeout: 5000 },
  )
}

watch(
  () => route.query.typeId,
  (value) => {
    const id = Number(value)
    if (id > 0 && id !== activeType.value) {
      activeType.value = id
      void reload()
    }
  },
)

onMounted(async () => {
  await loadTypes()
  await reload()
})
</script>

<style scoped>
.shops__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--ll-space-md);
  margin-bottom: var(--ll-space-md);
}

.shops__title {
  margin: 0;
  font-size: 34px;
  letter-spacing: -0.03em;
}

.shops__controls {
  display: flex;
  gap: 10px;
}

.shops__search {
  width: 240px;
}

.shops__tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: var(--ll-space-md);
}

.shops__tab {
  border: 1px solid var(--ll-border);
  background: var(--ll-surface);
  border-radius: 999px;
  padding: 9px 16px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  color: var(--ll-text-secondary);
  transition: all 0.16s ease;
}

.shops__tab:hover,
.shops__tab:focus-visible {
  border-color: var(--ll-primary);
  outline: none;
}

.shops__tab--active {
  background: var(--ll-text);
  color: #fff;
  border-color: var(--ll-text);
}

.shops__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--ll-space-md);
}

.shops__more {
  display: flex;
  justify-content: center;
  margin-top: var(--ll-space-lg);
}

@media (max-width: 1024px) {
  .shops__grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .shops__head {
    flex-direction: column;
    align-items: stretch;
  }

  .shops__controls {
    flex-direction: column;
  }

  .shops__search {
    width: 100%;
  }

  .shops__grid {
    grid-template-columns: 1fr;
  }
}
</style>
