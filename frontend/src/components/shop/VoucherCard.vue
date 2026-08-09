<template>
  <article class="voucher-card">
    <div class="voucher-card__price">
      <PriceText :cents="voucher.payValue" />
      <span class="voucher-card__value">价值 {{ formatPrice(voucher.actualValue) }}</span>
    </div>
    <div class="voucher-card__info">
      <h4 class="voucher-card__title">{{ voucher.title }}</h4>
      <p v-if="voucher.subTitle" class="voucher-card__subtitle">{{ voucher.subTitle }}</p>
      <p v-if="voucher.rules" class="voucher-card__rules">{{ voucher.rules }}</p>
      <p v-if="isSeckill" class="voucher-card__stock">
        {{ voucher.stock != null ? `剩余 ${voucher.stock}` : '秒杀' }}
      </p>
    </div>
    <div class="voucher-card__action">
      <el-tag v-if="isSeckill" type="danger" effect="light" size="small">秒杀</el-tag>
      <el-tag v-else effect="plain" size="small">优惠券</el-tag>
      <el-button
        type="primary"
        :disabled="isSeckill && (voucher.stock ?? 0) <= 0"
        @click="$emit('grab', voucher)"
      >
        {{ isSeckill ? '立即抢购' : '查看' }}
      </el-button>
    </div>
  </article>
</template>

<script setup lang="ts">
import PriceText from '@/components/common/PriceText.vue'
import { formatPrice } from '@/utils/format'
import type { Voucher } from '@/types/api'

const props = defineProps<{
  voucher: Voucher
}>()

defineEmits<{
  grab: [voucher: Voucher]
}>()

const isSeckill = props.voucher.type === 1
</script>

<style scoped>
.voucher-card {
  display: flex;
  align-items: center;
  gap: var(--ll-space-md);
  padding: var(--ll-space-md);
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  background-image: linear-gradient(135deg, rgba(108, 99, 255, 0.05), rgba(20, 184, 166, 0.06));
  position: relative;
}

.voucher-card::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 118px;
  border-left: 1px dashed rgba(108, 99, 255, 0.35);
}

.voucher-card__price {
  min-width: 110px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
}

.voucher-card__value {
  font-size: 12px;
  color: var(--ll-text-secondary);
}

.voucher-card__info {
  flex: 1;
  min-width: 0;
}

.voucher-card__title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 650;
}

.voucher-card__subtitle,
.voucher-card__rules,
.voucher-card__stock {
  margin: 2px 0;
  font-size: 13px;
  color: var(--ll-text-secondary);
}

.voucher-card__action {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

@media (max-width: 768px) {
  .voucher-card {
    flex-direction: column;
    align-items: flex-start;
  }

  .voucher-card__action {
    flex-direction: row;
    align-items: center;
  }
}
</style>
