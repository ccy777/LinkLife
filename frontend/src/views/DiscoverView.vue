<template>
  <div class="page discover">
    <section class="container discover__hero">
      <div class="discover__hero-text">
        <h1>探索你的城市。</h1>
        <p>发现好店、记录生活，连接每一次本地体验。</p>
        <RouterLink class="discover__cta" to="/shops">探索商铺</RouterLink>
      </div>
      <div class="discover__hero-visual" aria-hidden="true">
        <svg viewBox="0 0 520 300" fill="none" xmlns="http://www.w3.org/2000/svg">
          <rect width="520" height="300" rx="28" fill="url(#dh)" />
          <circle cx="110" cy="110" r="30" fill="#6C63FF" opacity="0.9" />
          <circle cx="250" cy="80" r="20" fill="#14B8A6" opacity="0.9" />
          <circle cx="360" cy="150" r="26" fill="#6C63FF" opacity="0.75" />
          <circle cx="440" cy="90" r="15" fill="#14B8A6" opacity="0.8" />
          <circle cx="420" cy="220" r="22" fill="#6C63FF" opacity="0.7" />
          <path d="M138 110 L232 84 M270 84 L338 150 M384 150 L420 220 M232 84 L360 150 M110 110 L360 150 M360 150 L440 90"
            stroke="#6C63FF" stroke-width="2" stroke-opacity="0.35" />
          <defs>
            <linearGradient id="dh" x1="0" y1="0" x2="520" y2="300">
              <stop stop-color="#ECE9FF" />
              <stop offset="1" stop-color="#E4F8F4" />
            </linearGradient>
          </defs>
        </svg>
      </div>
    </section>

    <section class="container discover__section">
      <h2 class="section-title">分类</h2>
      <div v-if="loadingTypes" class="discover__chips">
        <div v-for="i in 8" :key="i" class="discover__chip discover__chip--skeleton" />
      </div>
      <div v-else class="discover__chips">
        <RouterLink
          v-for="t in shopTypes"
          :key="t.id"
          class="discover__chip"
          :to="`/shops?typeId=${t.id}`"
        >
          <MediaVisual :seed="`type:${t.id}`" :label="t.name" kind="type" :type-id="t.id" size="sm" />
          <span>{{ t.name }}</span>
        </RouterLink>
      </div>
    </section>

    <section class="container discover__section">
      <div class="discover__section-head">
        <h2 class="section-title">附近商铺</h2>
        <el-button v-if="!located" :loading="locating" @click="locate">
          <el-icon><Location /></el-icon>
          使用我的位置
        </el-button>
        <span v-else class="discover__located">已使用定位</span>
      </div>
      <LoadingSkeleton v-if="loadingShops" :count="6" />
      <EmptyState v-else-if="shops.length === 0" text="附近暂无商铺" />
      <div v-else class="discover__grid">
        <ShopCard v-for="shop in shops" :key="shop.id" :shop="shop" />
      </div>
    </section>

    <section class="container discover__section">
      <div class="discover__section-head">
        <h2 class="section-title">热门动态</h2>
        <RouterLink class="discover__more" to="/moments">查看全部</RouterLink>
      </div>
      <LoadingSkeleton v-if="loadingBlogs" :count="3" />
      <EmptyState v-else-if="blogs.length === 0" text="暂无动态" />
      <div v-else class="discover__grid discover__grid--moments">
        <MomentCard v-for="blog in blogs" :key="blog.id" :blog="blog" />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Location } from '@element-plus/icons-vue'
import MediaVisual from '@/components/common/MediaVisual.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ShopCard from '@/components/shop/ShopCard.vue'
import MomentCard from '@/components/blog/MomentCard.vue'
import * as shopApi from '@/api/shop'
import * as blogApi from '@/api/blog'
import type { Blog, Shop, ShopType } from '@/types/api'

const shopTypes = ref<ShopType[]>([])
const shops = ref<Shop[]>([])
const blogs = ref<Blog[]>([])
const loadingTypes = ref(true)
const loadingShops = ref(true)
const loadingBlogs = ref(true)
const locating = ref(false)
const located = ref(false)

async function loadTypes() {
  loadingTypes.value = true
  try {
    shopTypes.value = await shopApi.fetchShopTypes()
  } catch {
    shopTypes.value = []
  } finally {
    loadingTypes.value = false
  }
}

async function loadShops(x?: number, y?: number) {
  loadingShops.value = true
  try {
    const firstType = shopTypes.value[0]?.id ?? 1
    shops.value = await shopApi.fetchShopsByType(firstType, 1, x, y)
  } catch {
    shops.value = []
  } finally {
    loadingShops.value = false
  }
}

async function loadBlogs() {
  loadingBlogs.value = true
  try {
    blogs.value = await blogApi.fetchHotBlogs(1)
  } catch {
    blogs.value = []
  } finally {
    loadingBlogs.value = false
  }
}

function locate() {
  if (!navigator.geolocation) {
    located.value = true
    void loadShops()
    return
  }
  locating.value = true
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      located.value = true
      locating.value = false
      void loadShops(pos.coords.longitude, pos.coords.latitude)
    },
    () => {
      located.value = true
      locating.value = false
      void loadShops()
    },
    { timeout: 5000 },
  )
}

onMounted(() => {
  void loadTypes().then(() => loadShops())
  void loadBlogs()
})
</script>

<style scoped>
.discover__hero {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: var(--ll-space-lg);
  align-items: center;
  padding-top: var(--ll-space-xl);
}

.discover__hero-text h1 {
  font-size: 52px;
  line-height: 1.05;
  letter-spacing: -0.035em;
  margin: 0 0 16px;
}

.discover__hero-text p {
  color: var(--ll-text-secondary);
  font-size: 19px;
  margin: 0 0 24px;
}

.discover__cta {
  display: inline-flex;
  padding: 13px 22px;
  border-radius: 999px;
  background: var(--ll-text);
  color: #fff;
  font-weight: 600;
}

.discover__hero-visual svg {
  width: 100%;
  border-radius: var(--ll-radius-lg);
  box-shadow: var(--ll-shadow-md);
}

.discover__section {
  margin-top: var(--ll-space-xl);
}

.discover__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.discover__located {
  color: var(--ll-secondary);
  font-size: 14px;
  font-weight: 600;
}

.discover__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.discover__chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  font-weight: 600;
  transition: border-color 0.16s ease, transform 0.16s ease;
}

.discover__chip:hover,
.discover__chip:focus-visible {
  border-color: var(--ll-primary);
  transform: translateY(-2px);
  outline: none;
}

.discover__chip--skeleton {
  width: 120px;
  height: 54px;
}

.discover__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--ll-space-md);
}

.discover__more {
  color: var(--ll-primary);
  font-weight: 600;
  font-size: 14px;
}

@media (max-width: 1024px) {
  .discover__grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .discover__hero {
    grid-template-columns: 1fr;
    padding-top: var(--ll-space-lg);
  }

  .discover__hero-text h1 {
    font-size: 38px;
  }

  .discover__hero-visual {
    display: none;
  }

  .discover__grid {
    grid-template-columns: 1fr;
  }
}
</style>
