<template>
  <span class="price">
    <span class="price__currency">¥</span>
    {{ whole }}<span class="price__fraction">.{{ fraction }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  cents: number
}>()

const whole = computed(() => Math.floor(props.cents / 100).toString())
const fraction = computed(() => String(props.cents % 100).padStart(2, '0'))
</script>

<style scoped>
.price {
  font-weight: 700;
  color: var(--ll-text);
}

.price__currency {
  font-size: 0.72em;
  margin-right: 1px;
  color: var(--ll-primary);
}

.price__fraction {
  font-size: 0.72em;
}
</style>
