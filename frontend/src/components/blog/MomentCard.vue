<template>
  <RouterLink class="moment-card" :to="`/moments/${blog.id}`">
    <div class="moment-card__cover-wrap">
      <MediaCover :media="blogMedia" kind="blog" />
    </div>
    <div class="moment-card__body">
      <div class="moment-card__author">
        <UserAvatar :icon="blog.icon" :nickname="blog.name || '作者'" />
        <span class="moment-card__author-name">{{ blog.name || `用户 ${blog.userId}` }}</span>
      </div>
      <h3 class="moment-card__title">{{ blog.title }}</h3>
      <p class="moment-card__preview">{{ excerpt }}</p>
      <div class="moment-card__meta">
        <span><el-icon><View /></el-icon> {{ blog.liked }} 赞</span>
        <span><el-icon><ChatDotRound /></el-icon> {{ blog.comments }} 条评论</span>
        <span>{{ formatDateTime(blog.createTime) }}</span>
      </div>
    </div>
  </RouterLink>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { View, ChatDotRound } from '@element-plus/icons-vue'
import MediaCover from '@/components/common/MediaCover.vue'
import UserAvatar from '@/components/common/UserAvatar.vue'
import { resolveBlogMedia } from '@/utils/media'
import { formatDateTime } from '@/utils/format'
import type { Blog } from '@/types/api'

const props = defineProps<{
  blog: Blog
}>()

const blogMedia = computed(() => resolveBlogMedia(props.blog))
const excerpt = computed(() => {
  const text = props.blog.content.replace(/\s+/g, ' ').trim()
  return text.length > 96 ? `${text.slice(0, 96)}...` : text
})
</script>

<style scoped>
.moment-card {
  display: flex;
  flex-direction: column;
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  overflow: hidden;
  box-shadow: var(--ll-shadow-sm);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.moment-card:hover,
.moment-card:focus-visible {
  transform: translateY(-3px);
  box-shadow: var(--ll-shadow-md);
  outline: none;
}

.moment-card__cover-wrap {
  height: 140px;
  width: 100%;
  overflow: hidden;
}

.moment-card__body {
  padding: var(--ll-space-sm);
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.moment-card__author {
  display: flex;
  align-items: center;
  gap: 8px;
}

.moment-card__author-name {
  font-size: 13px;
  color: var(--ll-text-secondary);
}

.moment-card__title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}

.moment-card__preview {
  margin: 0;
  font-size: 13px;
  color: var(--ll-text-secondary);
  line-height: 1.5;
}

.moment-card__meta {
  display: flex;
  gap: 14px;
  margin-top: auto;
  font-size: 12px;
  color: var(--ll-text-secondary);
}

.moment-card__meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
