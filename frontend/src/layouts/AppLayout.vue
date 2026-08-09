<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="container app-header__inner">
        <RouterLink class="app-header__brand" to="/" aria-label="LinkLife home">
          <LogoMark />
          <span class="app-header__name">LinkLife</span>
        </RouterLink>

        <nav class="app-nav" aria-label="主导航">
          <RouterLink v-for="item in navItems" :key="item.to" class="app-nav__link" :to="item.to">
            {{ item.label }}
          </RouterLink>
        </nav>

        <div class="app-header__actions">
          <template v-if="auth.isAuthenticated">
            <el-dropdown trigger="click">
              <button class="app-user" type="button" aria-label="账户菜单">
                <UserAvatar :icon="auth.currentUser?.icon" :nickname="auth.currentUser?.nickName || 'U'" />
                <span class="app-user__name">{{ auth.currentUser?.nickName || 'Me' }}</span>
                <el-icon><ArrowDown /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="router.push('/orders')">
                    <el-icon><Tickets /></el-icon>
                    订单
                  </el-dropdown-item>
                  <el-dropdown-item divided @click="handleLogout">
                    <el-icon><SwitchButton /></el-icon>
                    退出登录
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <RouterLink v-else class="app-signin" to="/login">登录</RouterLink>

          <button
            class="app-menu-toggle"
            type="button"
            aria-label="打开菜单"
            @click="menuOpen = true"
          >
            <el-icon :size="22"><Menu /></el-icon>
          </button>
        </div>
      </div>
    </header>

    <el-drawer v-model="menuOpen" direction="rtl" size="280px" :with-header="false">
      <nav class="app-drawer-nav" aria-label="移动端导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          class="app-drawer-nav__link"
          :to="item.to"
          @click="menuOpen = false"
        >
          {{ item.label }}
        </RouterLink>
        <RouterLink
          v-if="!auth.isAuthenticated"
          class="app-drawer-nav__link"
          to="/login"
          @click="menuOpen = false"
        >
          登录
        </RouterLink>
        <button v-else class="app-drawer-nav__link app-drawer-nav__button" type="button" @click="handleLogout">
          退出登录
        </button>
      </nav>
    </el-drawer>

    <main class="app-main">
      <RouterView v-slot="{ Component }">
        <Transition name="fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <footer class="app-footer">
      <div class="container app-footer__inner">
        <span>LinkLife · 本地生活平台工程作品集</span>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Menu, SwitchButton, Tickets } from '@element-plus/icons-vue'
import LogoMark from '@/components/brand/LogoMark.vue'
import UserAvatar from '@/components/common/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const menuOpen = ref(false)

const navItems = [
  { to: '/', label: '发现' },
  { to: '/shops', label: '商铺' },
  { to: '/moments', label: '动态' },
  { to: '/engineering', label: '工程验证' },
]

async function handleLogout() {
  await auth.logout()
  ElMessage.success('已退出登录')
  await router.push('/')
}

onMounted(() => {
  if (auth.isAuthenticated && !auth.currentUser) {
    void auth.loadCurrentUser()
  }
})
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--ll-border);
}

.app-header__inner {
  height: 68px;
  display: flex;
  align-items: center;
  gap: var(--ll-space-md);
}

.app-header__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.app-header__name {
  font-size: 19px;
  font-weight: 750;
  letter-spacing: -0.02em;
}

.app-nav {
  display: flex;
  gap: 6px;
  margin-left: var(--ll-space-md);
}

.app-nav__link {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 15px;
  color: var(--ll-text-secondary);
  transition: background 0.16s ease, color 0.16s ease;
}

.app-nav__link:hover,
.app-nav__link:focus-visible,
.app-nav__link.router-link-active {
  background: var(--ll-primary-soft);
  color: var(--ll-primary);
  outline: none;
}

.app-header__actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.app-user {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 0;
  background: none;
  cursor: pointer;
  padding: 6px 8px;
  border-radius: 999px;
}

.app-user:hover,
.app-user:focus-visible {
  background: var(--ll-bg);
  outline: none;
}

.app-user__name {
  font-size: 14px;
  font-weight: 600;
}

.app-signin {
  padding: 10px 18px;
  border-radius: 999px;
  background: var(--ll-text);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.app-signin:hover,
.app-signin:focus-visible {
  opacity: 0.88;
  outline: none;
}

.app-menu-toggle {
  display: none;
  border: 0;
  background: none;
  cursor: pointer;
  padding: 8px;
  border-radius: 10px;
}

.app-main {
  flex: 1;
}

.app-footer {
  border-top: 1px solid var(--ll-border);
  padding: 20px 0;
}

.app-footer__inner {
  color: var(--ll-text-secondary);
  font-size: 13px;
}

.app-drawer-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 16px;
}

.app-drawer-nav__link {
  padding: 14px 12px;
  border-radius: 12px;
  font-size: 16px;
  color: var(--ll-text);
}

.app-drawer-nav__link:hover,
.app-drawer-nav__link.router-link-active {
  background: var(--ll-primary-soft);
  color: var(--ll-primary);
}

.app-drawer-nav__button {
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}

@media (max-width: 768px) {
  .app-nav {
    display: none;
  }

  .app-menu-toggle {
    display: inline-grid;
    place-items: center;
  }

  .app-user__name {
    display: none;
  }
}
</style>
