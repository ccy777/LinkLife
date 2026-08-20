<template>
  <div class="page orders">
    <div class="container">
      <h1 class="orders__title">我的订单</h1>
      <LoadingSkeleton v-if="loading" :count="3" variant="list" />
      <RetryError v-else-if="error" :message="error" @retry="load" />
      <EmptyState v-else-if="orders.length === 0" text="暂无订单" />

      <div v-else class="orders__list">
        <article v-for="order in orders" :key="order.id" class="card order-row">
          <div class="order-row__main">
            <p class="order-row__id">订单 #{{ order.id }}</p>
            <p class="order-row__meta">
              优惠券 {{ order.voucherId }} · {{ formatDateTime(order.createTime) }}
            </p>
            <p class="order-row__meta">支付方式 {{ order.payType }}</p>
          </div>
          <div class="order-row__side">
            <el-tag :type="order.status === 1 ? 'warning' : 'info'">
              {{ orderStatusText(order.status) }}
            </el-tag>
            <el-button
              v-if="order.status === 1"
              size="small"
              :loading="cancelingId === order.id"
              @click="cancel(order)"
            >
              取消订单
            </el-button>
          </div>
        </article>
      </div>

      <div v-if="orders.length > 0 && hasMore" class="orders__more">
        <el-button :loading="loadingMore" @click="loadMore">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import RetryError from '@/components/common/RetryError.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { formatDateTime, orderStatusText } from '@/utils/format'
import * as orderApi from '@/api/order'
import type { Order } from '@/types/api'

const PAGE_SIZE = 10
const orders = ref<Order[]>([])
const current = ref(1)
const total = ref(0)
const loading = ref(true)
const loadingMore = ref(false)
const cancelingId = ref<string | null>(null)
const error = ref('')

const hasMore = computed(() => orders.value.length < total.value)

async function load() {
  current.value = 1
  loading.value = true
  error.value = ''
  try {
    const page = await orderApi.fetchMyOrders(1, PAGE_SIZE)
    orders.value = page.records
    total.value = page.total
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载订单失败'
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  loadingMore.value = true
  try {
    const next = current.value + 1
    const page = await orderApi.fetchMyOrders(next, PAGE_SIZE)
    orders.value = [...orders.value, ...page.records]
    total.value = page.total
    current.value = next
  } catch {
    // keep existing list
  } finally {
    loadingMore.value = false
  }
}

async function cancel(order: Order) {
  try {
    await ElMessageBox.confirm('确定要取消该订单吗？', '取消订单', {
      type: 'warning',
      confirmButtonText: '取消订单',
      cancelButtonText: '保留',
    })
  } catch {
    return
  }
  cancelingId.value = order.id
  try {
    await orderApi.cancelOrder(order.id)
    ElMessage.success('订单已取消')
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '取消失败')
  } finally {
    cancelingId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.orders__title {
  margin: 0 0 var(--ll-space-md);
  font-size: 34px;
  letter-spacing: -0.03em;
}

.orders__list {
  display: flex;
  flex-direction: column;
  gap: var(--ll-space-sm);
}

.order-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ll-space-md);
  padding: var(--ll-space-md);
}

.order-row__id {
  margin: 0 0 4px;
  font-weight: 650;
}

.order-row__meta {
  margin: 2px 0;
  font-size: 13px;
  color: var(--ll-text-secondary);
}

.order-row__side {
  display: flex;
  align-items: center;
  gap: 10px;
}

.orders__more {
  display: flex;
  justify-content: center;
  margin-top: var(--ll-space-lg);
}

@media (max-width: 768px) {
  .order-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
