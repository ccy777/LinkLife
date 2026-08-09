<template>
  <div class="login">
    <section class="login__visual" aria-hidden="true">
      <div class="login__brand">
        <LogoMark />
        <span>LinkLife</span>
      </div>
      <div class="login__headline">
        <h1>探索你的城市。</h1>
        <p>地点、动态与本地体验，在这里连接。</p>
      </div>
      <svg class="login__city" viewBox="0 0 480 300" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="lg" x1="0" y1="0" x2="480" y2="300">
            <stop stop-color="#ECE9FF" />
            <stop offset="1" stop-color="#E4F8F4" />
          </linearGradient>
        </defs>
        <rect width="480" height="300" rx="24" fill="url(#lg)" />
        <circle cx="96" cy="120" r="26" fill="#6C63FF" opacity="0.9" />
        <circle cx="220" cy="86" r="18" fill="#14B8A6" opacity="0.9" />
        <circle cx="330" cy="150" r="22" fill="#6C63FF" opacity="0.75" />
        <circle cx="410" cy="90" r="14" fill="#14B8A6" opacity="0.8" />
        <circle cx="390" cy="210" r="20" fill="#6C63FF" opacity="0.7" />
        <path d="M116 120 L204 90 M238 90 L310 150 M350 150 L390 210 M204 90 L330 150 M96 120 L330 150 M330 150 L410 90"
          stroke="#6C63FF" stroke-width="2" stroke-opacity="0.35" />
      </svg>
    </section>

    <section class="login__panel">
      <form class="login__form" @submit.prevent="submit">
        <h2>欢迎回来</h2>
        <p class="login__hint">使用手机号与验证码登录</p>

        <label class="field">
          <span class="field__label" for="phone">手机号</span>
          <el-input
            id="phone"
            v-model="phone"
            placeholder="请输入手机号"
            inputmode="tel"
            maxlength="11"
            autocomplete="tel"
            aria-label="手机号"
          />
        </label>

        <label class="field">
          <span class="field__label" for="code">验证码</span>
          <div class="login__code-row">
            <el-input
              id="code"
              v-model="code"
              placeholder="请输入 6 位验证码"
              inputmode="numeric"
              maxlength="6"
              autocomplete="one-time-code"
              aria-label="验证码"
            />
            <el-button :disabled="countdown > 0 || sending" @click="send">
              {{ countdown > 0 ? `${countdown} 秒后重试` : sending ? '发送中…' : '获取验证码' }}
            </el-button>
          </div>
        </label>

        <el-button class="login__submit" type="primary" native-type="submit" :loading="submitting" size="large">
          登录
        </el-button>
      </form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import LogoMark from '@/components/brand/LogoMark.vue'
import { useAuthStore } from '@/stores/auth'
import * as userApi from '@/api/user'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const phone = ref('')
const code = ref('')
const countdown = ref(0)
const sending = ref(false)
const submitting = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

function startCountdown() {
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function send() {
  if (sending.value || countdown.value > 0) return
  if (!/^1\d{10}$/.test(phone.value)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  sending.value = true
  try {
    await userApi.sendCode(phone.value)
    ElMessage.success('验证码已发送')
    startCountdown()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '验证码发送失败')
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (submitting.value) return
  if (!/^1\d{10}$/.test(phone.value)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  if (code.value.length !== 6) {
    ElMessage.warning('请输入 6 位验证码')
    return
  }
  submitting.value = true
  try {
    await auth.loginWithCode(phone.value, code.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.push(redirect)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '登录失败')
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  background: var(--ll-bg);
}

.login__visual {
  padding: var(--ll-space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--ll-space-md);
}

.login__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 19px;
  font-weight: 750;
}

.login__headline h1 {
  font-size: 42px;
  line-height: 1.1;
  letter-spacing: -0.03em;
  margin: 0 0 12px;
}

.login__headline p {
  color: var(--ll-text-secondary);
  font-size: 17px;
  margin: 0;
}

.login__city {
  width: 100%;
  max-width: 520px;
  margin-top: auto;
  border-radius: var(--ll-radius-lg);
}

.login__panel {
  display: grid;
  place-items: center;
  padding: var(--ll-space-lg);
}

.login__form {
  width: min(380px, 100%);
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-lg);
  padding: var(--ll-space-lg);
  box-shadow: var(--ll-shadow-md);
  display: flex;
  flex-direction: column;
  gap: var(--ll-space-md);
}

.login__form h2 {
  margin: 0;
  font-size: 24px;
  letter-spacing: -0.02em;
}

.login__hint {
  margin: 0;
  color: var(--ll-text-secondary);
  font-size: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field__label {
  font-size: 13px;
  font-weight: 600;
}

.login__code-row {
  display: flex;
  gap: 8px;
}

.login__code-row .el-input {
  flex: 1;
}

.login__submit {
  width: 100%;
  height: 46px;
  margin-top: 4px;
}

@media (max-width: 900px) {
  .login {
    grid-template-columns: 1fr;
  }

  .login__visual {
    display: none;
  }

  .login__panel {
    min-height: 100vh;
  }
}
</style>
