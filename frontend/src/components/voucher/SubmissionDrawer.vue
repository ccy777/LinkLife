<template>
  <el-drawer
    :model-value="open"
    title="秒杀进度"
    size="380px"
    @close="$emit('close')"
  >
    <div class="submission">
      <template v-if="admissionError">
        <el-alert
          type="error"
          :closable="false"
          title="秒杀请求未通过"
          :description="admissionError"
        />
        <p class="submission__hint">
          未创建订单。您可以重试或关闭本窗口。
        </p>
        <div class="submission__actions">
          <el-button @click="$emit('close')">关闭</el-button>
          <el-button type="primary" :loading="admitting" @click="submit">
            重试
          </el-button>
        </div>
      </template>

      <template v-else-if="admitting">
        <el-skeleton :rows="4" animated />
        <p class="submission__hint">正在提交秒杀请求…</p>
      </template>

      <template v-else-if="!orderId">
        <el-alert
          type="warning"
          :closable="false"
          title="未创建订单"
          description="请关闭后重试。"
        />
        <div class="submission__actions">
          <el-button @click="$emit('close')">关闭</el-button>
        </div>
      </template>

      <template v-else>
        <div class="submission__step">
          <el-steps direction="vertical" :active="stepIndex" finish-status="success">
            <el-step title="ACCEPTED" description="Redis 已完成原子准入" />
            <el-step title="PROCESSING" description="消费者正在处理订单" />
            <el-step title="PERSISTED" description="订单已持久化" />
          </el-steps>
        </div>

        <el-alert
          v-if="state === 'FAILED'"
          type="error"
          :closable="false"
          :title="submission?.message || '处理失败，请稍后在订单页查看。'"
        />
        <el-alert
          v-else-if="state === 'UNKNOWN'"
          type="warning"
          :closable="false"
          title="状态暂时未知，请稍后在订单页查看。"
        />
        <el-alert
          v-else-if="state === 'PERSISTED'"
          type="success"
          :closable="false"
          title="订单已落库"
          description="您的订单已成功落库。"
        />
        <el-alert
          v-else-if="state && state !== 'ACCEPTED' && state !== 'PROCESSING'"
          type="info"
          :closable="false"
          :title="`Status: ${state}`"
        />
        <el-alert
          v-else-if="state"
          type="info"
          :closable="false"
          title="准入已通过，正在等待订单落库…"
        />

        <p v-if="timedOut" class="submission__timeout">
          轮询已停止（15 秒）。请到订单页查看最终状态。
        </p>

        <div class="submission__actions">
          <el-button type="primary" @click="goOrders">查看我的订单</el-button>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as voucherApi from '@/api/voucher'
import type { OrderSubmission, SubmissionState, Voucher } from '@/types/api'

const props = defineProps<{
  open: boolean
  voucher: Voucher | null
}>()

defineEmits<{
  close: []
}>()

const router = useRouter()
const orderId = ref<number | null>(null)
const admitting = ref(false)
const admissionError = ref<string | null>(null)
const submission = ref<OrderSubmission | null>(null)
const timedOut = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
let attempts = 0

const state = computed<SubmissionState | null>(() => submission.value?.state ?? null)
const stepIndex = computed(() => {
  if (state.value === 'PERSISTED') return 3
  if (state.value === 'PROCESSING') return 1
  return 0
})

function stopPolling() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function reset() {
  stopPolling()
  orderId.value = null
  admitting.value = false
  admissionError.value = null
  submission.value = null
  timedOut.value = false
  attempts = 0
}

async function poll() {
  if (!orderId.value) return
  try {
    submission.value = await voucherApi.fetchSubmission(orderId.value)
  } catch {
    // keep polling; final timeout stops
  }
  const s = submission.value?.state
  if (s === 'PERSISTED' || s === 'FAILED' || s === 'UNKNOWN') {
    stopPolling()
    return
  }
  attempts += 1
  if (attempts >= 15) {
    timedOut.value = true
    stopPolling()
    return
  }
  const delay = attempts <= 2 ? 500 : 1000
  timer = setTimeout(() => void poll(), delay)
}

async function submit() {
  if (!props.voucher) return
  reset()
  admitting.value = true
  try {
    orderId.value = await voucherApi.seckill(props.voucher.id)
    admitting.value = false
    void poll()
  } catch (error) {
    admitting.value = false
    admissionError.value =
      error instanceof Error ? error.message : '秒杀请求未通过，请稍后重试。'
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open || !props.voucher) return
    void submit()
  },
)

function goOrders() {
  stopPolling()
  void router.push('/orders')
}

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.submission {
  display: flex;
  flex-direction: column;
  gap: var(--ll-space-md);
}

.submission__step {
  margin: 4px 0 8px;
}

.submission__hint {
  margin: 0;
  font-size: 13px;
  color: var(--ll-text-secondary);
}

.submission__timeout {
  margin: 0;
  font-size: 13px;
  color: var(--ll-text-secondary);
}

.submission__actions {
  display: flex;
  gap: var(--ll-space-sm);
}
</style>
