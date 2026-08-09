<template>
  <div class="page moment-detail">
    <div class="container moment-detail__wrap">
      <LoadingSkeleton v-if="loading" :count="1" variant="list" />
      <RetryError v-else-if="error" :message="error" @retry="load" />

      <template v-else-if="blog">
        <article class="card moment-detail__article">
          <div class="moment-detail__author">
            <UserAvatar :icon="blog.icon" :nickname="blog.name || '作者'" />
            <div>
              <p class="moment-detail__author-name">{{ blog.name || `用户 ${blog.userId}` }}</p>
              <p class="moment-detail__time">{{ formatDateTime(blog.createTime) }}</p>
            </div>
            <el-button
              v-if="isMe === false"
              class="moment-detail__follow"
              :type="followed ? 'default' : 'primary'"
              :loading="followLoading"
              @click="toggleFollow"
            >
              {{ followed ? '已关注' : '关注' }}
            </el-button>
          </div>

          <div class="moment-detail__cover">
            <MediaCover :media="blogMedia" kind="blog" size="lg" fill />
          </div>

          <h1 class="moment-detail__title">{{ blog.title }}</h1>
          <p class="moment-detail__content">{{ blog.content }}</p>

          <div class="moment-detail__actions">
            <el-button :loading="likeLoading" @click="toggleLike">
              <el-icon><StarFilled v-if="likedByMe" /><Star v-else /></el-icon>
              {{ likedByMe ? '已赞' : '赞' }} · {{ blog.liked }}
            </el-button>
            <span class="moment-detail__count">{{ likes.length }} 人赞过</span>
          </div>
        </article>

        <aside class="moment-detail__side">
          <div class="card moment-detail__panel">
            <h3 class="moment-detail__panel-title">点赞的人</h3>
            <div v-if="likes.length === 0" class="text-secondary">暂无点赞</div>
            <ul v-else class="moment-detail__likes">
              <li v-for="u in likes" :key="u.id">
                <UserAvatar :icon="u.icon" :nickname="u.nickName" />
                <span>{{ u.nickName }}</span>
              </li>
            </ul>
          </div>
        </aside>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Star, StarFilled } from '@element-plus/icons-vue'
import MediaCover from '@/components/common/MediaCover.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import RetryError from '@/components/common/RetryError.vue'
import UserAvatar from '@/components/common/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { resolveBlogMedia, type MediaDescriptor } from '@/utils/media'
import { formatDateTime } from '@/utils/format'
import * as blogApi from '@/api/blog'
import type { Blog, BlogLikeUser } from '@/types/api'

const route = useRoute()
const auth = useAuthStore()
const blog = ref<Blog | null>(null)
const likes = ref<BlogLikeUser[]>([])
const followed = ref(false)
const likedByMe = ref(false)
const loading = ref(true)
const likeLoading = ref(false)
const followLoading = ref(false)
const error = ref('')

const id = computed(() => Number(route.params.id))
const blogMedia = computed<MediaDescriptor>(() =>
  blog.value
    ? resolveBlogMedia(blog.value)
    : { type: 'fallback', seed: 'blog:0', label: '' },
)
const isMe = computed(() => (blog.value ? blog.value.userId === auth.currentUser?.id : null))

async function load() {
  loading.value = true
  error.value = ''
  try {
    blog.value = await blogApi.fetchBlog(id.value)
    const [likeUsers, followState] = await Promise.all([
      blogApi.fetchBlogLikes(id.value),
      blogApi.fetchFollowState(blog.value.userId),
    ])
    likes.value = likeUsers
    likedByMe.value = likeUsers.some((u) => u.id === auth.currentUser?.id)
    followed.value = followState
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载动态失败'
  } finally {
    loading.value = false
  }
}

async function toggleLike() {
  likeLoading.value = true
  try {
    await blogApi.likeBlog(id.value)
    blog.value = await blogApi.fetchBlog(id.value)
    likes.value = await blogApi.fetchBlogLikes(id.value)
    likedByMe.value = likes.value.some((u) => u.id === auth.currentUser?.id)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '点赞失败')
  } finally {
    likeLoading.value = false
  }
}

async function toggleFollow() {
  if (!blog.value) return
  followLoading.value = true
  try {
    await blogApi.followUser(blog.value.userId, !followed.value)
    followed.value = !followed.value
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '关注失败')
  } finally {
    followLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.moment-detail__wrap {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: var(--ll-space-md);
  align-items: start;
}

.moment-detail__article {
  padding: var(--ll-space-md);
}

.moment-detail__author {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: var(--ll-space-md);
}

.moment-detail__author-name {
  margin: 0;
  font-weight: 650;
}

.moment-detail__time {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--ll-text-secondary);
}

.moment-detail__follow {
  margin-left: auto;
}

.moment-detail__cover {
  position: relative;
  border-radius: var(--ll-radius-md);
  min-height: 220px;
  margin-bottom: var(--ll-space-md);
}

.moment-detail__cover :deep(.media-visual--blog) {
  height: 100%;
  min-height: 220px;
}

.moment-detail__title {
  margin: 0 0 12px;
  font-size: 28px;
  letter-spacing: -0.02em;
}

.moment-detail__content {
  margin: 0 0 var(--ll-space-md);
  line-height: 1.75;
  white-space: pre-wrap;
  color: #2a2f3a;
}

.moment-detail__actions {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-top: var(--ll-space-sm);
  border-top: 1px solid var(--ll-border);
}

.moment-detail__count {
  color: var(--ll-text-secondary);
  font-size: 13px;
}

.moment-detail__panel {
  padding: var(--ll-space-md);
}

.moment-detail__panel-title {
  margin: 0 0 var(--ll-space-sm);
  font-size: 15px;
}

.moment-detail__likes {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.moment-detail__likes li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}

@media (max-width: 900px) {
  .moment-detail__wrap {
    grid-template-columns: 1fr;
  }
}
</style>
