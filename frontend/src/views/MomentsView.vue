<template>
  <div class="page moments">
    <div class="container">
      <h1 class="moments__title">动态</h1>
      <LoadingSkeleton v-if="loading" :count="6" />
      <RetryError v-else-if="error" :message="error" @retry="reload" />
      <EmptyState v-else-if="blogs.length === 0" text="暂无动态" />
      <div v-else class="moments__grid">
        <MomentCard v-for="blog in blogs" :key="blog.id" :blog="blog" />
      </div>
      <div v-if="hasMore" class="moments__more">
        <el-button :loading="loadingMore" @click="loadMore">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import MomentCard from '@/components/blog/MomentCard.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import RetryError from '@/components/common/RetryError.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import * as blogApi from '@/api/blog'
import type { Blog } from '@/types/api'

const PAGE_SIZE = 5
const blogs = ref<Blog[]>([])
const current = ref(1)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')

const hasMore = computed(() => blogs.value.length > 0 && blogs.value.length % PAGE_SIZE === 0)

async function reload() {
  current.value = 1
  loading.value = true
  error.value = ''
  try {
    blogs.value = await blogApi.fetchHotBlogs(1)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载动态失败'
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  loadingMore.value = true
  try {
    const next = current.value + 1
    const more = await blogApi.fetchHotBlogs(next)
    blogs.value = [...blogs.value, ...more]
    current.value = next
  } catch {
    // keep existing list
  } finally {
    loadingMore.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.moments__title {
  margin: 0 0 var(--ll-space-md);
  font-size: 34px;
  letter-spacing: -0.03em;
}

.moments__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--ll-space-md);
}

.moments__more {
  display: flex;
  justify-content: center;
  margin-top: var(--ll-space-lg);
}

@media (max-width: 1024px) {
  .moments__grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .moments__grid {
    grid-template-columns: 1fr;
  }
}
</style>
