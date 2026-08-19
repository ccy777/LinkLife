<template>
  <div class="page engineering">
    <div class="container">
      <header class="engineering__head">
        <h1>工程验证</h1>
        <p class="engineering__note">经过本地环境验证的工程证据。</p>
        <p class="engineering__note">本页展示本地双机工程验证结果（服务端与 JMeter 压测端分离），并非实时监控数据，不代表生产 SLA 或容量。</p>
      </header>

      <section class="engineering__stats">
        <StatCard :value="evidence.tests.count" label="自动化测试" />
        <StatCard :value="evidence.officialBenchmarkRuns" label="正式双机 Benchmark runs" />
        <StatCard :value="evidence.independentDatabases" label="独立 MySQL 数据库" />
        <StatCard :value="evidence.springProcesses" label="Spring 服务进程" />
      </section>

      <section class="engineering__section">
        <h2 class="section-title">交易可靠性</h2>
        <div class="engineering__flow">
          <template v-for="(step, i) in evidence.transactionFlow" :key="step">
            <div class="engineering__step">{{ step }}</div>
            <span v-if="i < evidence.transactionFlow.length - 1" class="engineering__arrow" aria-hidden="true">→</span>
          </template>
        </div>
        <div class="engineering__sides">
          <el-tag v-for="side in evidence.reliabilitySides" :key="side" effect="plain">
            {{ side }}
          </el-tag>
        </div>
      </section>

      <section class="engineering__section">
        <h2 class="section-title">热点查询 Benchmark</h2>
        <div class="engineering__flow">
          <div class="engineering__step">Caffeine L1</div>
          <span class="engineering__arrow" aria-hidden="true">→</span>
          <div class="engineering__step">Redis L2</div>
        </div>
        <p class="engineering__cache">
          {{ evidence.cache.title }}：QPS {{ evidence.cache.qps }}，P95 {{ evidence.cache.p95 }}，P99 {{ evidence.cache.p99 }}
        </p>
        <p class="engineering__cache">
          Redis GET / 请求：{{ evidence.cache.before }} → {{ evidence.cache.after }}
        </p>
        <p class="engineering__cache">{{ evidence.cache.reduction }}</p>
        <p class="engineering__note">{{ evidence.cache.note }}</p>
      </section>

      <section class="engineering__section">
        <h2 class="section-title">秒杀压测</h2>
        <div class="engineering__seckill">
          <div class="engineering__seckill-head">
            <strong>1000 用户突发秒杀</strong>
            <span>{{ evidence.seckill1000.note }}</span>
          </div>
          <div class="engineering__seckill-grid">
            <div><span>落库订单</span><b>{{ evidence.seckill1000.persistedOrders }}</b></div>
            <div><span>独立用户</span><b>{{ evidence.seckill1000.distinctUsers }}</b></div>
            <div><span>重复订单</span><b>{{ evidence.seckill1000.duplicate }}</b></div>
            <div><span>超卖</span><b>{{ evidence.seckill1000.oversell }}</b></div>
            <div><span>HTTP 错误</span><b>{{ evidence.seckill1000.httpErrors }}</b></div>
            <div><span>P95</span><b>{{ evidence.seckill1000.p95 }}</b></div>
            <div><span>P99</span><b>{{ evidence.seckill1000.p99 }}</b></div>
            <div><span>PEL</span><b>{{ evidence.seckill1000.pel }}</b></div>
            <div><span>DLQ</span><b>{{ evidence.seckill1000.dlq }}</b></div>
          </div>
        </div>
      </section>

      <section class="engineering__section">
        <h2 class="section-title">故障演练</h2>
        <div class="engineering__reliability">
          <EvidenceCard
            v-for="card in evidence.reliability"
            :key="card.title"
            :title="card.title"
            :result="card.result"
            :detail="card.detail"
          />
        </div>
        <p class="engineering__note">{{ evidence.mysqlRecoveryNote }}</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import StatCard from '@/components/engineering/StatCard.vue'
import EvidenceCard from '@/components/engineering/EvidenceCard.vue'
import { engineeringEvidence } from '@/utils/evidence'

const evidence = engineeringEvidence
</script>

<style scoped>
.engineering__head h1 {
  margin: 0 0 8px;
  font-size: 40px;
  letter-spacing: -0.03em;
}

.engineering__note {
  margin: 4px 0;
  color: var(--ll-text-secondary);
  font-size: 13px;
}

.engineering__stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--ll-space-md);
  margin-top: var(--ll-space-md);
}

.engineering__section {
  margin-top: var(--ll-space-xl);
}

.engineering__flow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.engineering__step {
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-sm);
  padding: 12px 18px;
  font-weight: 600;
  box-shadow: var(--ll-shadow-sm);
}

.engineering__arrow {
  color: var(--ll-primary);
  font-size: 18px;
  font-weight: 700;
}

.engineering__sides {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: var(--ll-space-md);
}

.engineering__cache {
  margin: var(--ll-space-md) 0 4px;
  font-size: 18px;
  font-weight: 650;
}

.engineering__seckill {
  background: var(--ll-surface);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  padding: var(--ll-space-md);
}

.engineering__seckill-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  margin-bottom: var(--ll-space-md);
}

.engineering__seckill-head span {
  color: var(--ll-text-secondary);
  font-size: 13px;
}

.engineering__seckill-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--ll-space-sm);
}

.engineering__seckill-grid div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.engineering__seckill-grid span {
  color: var(--ll-text-secondary);
  font-size: 12px;
}

.engineering__seckill-grid b {
  font-size: 22px;
}

.engineering__reliability {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--ll-space-md);
}

@media (max-width: 1024px) {
  .engineering__stats {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .engineering__stats,
  .engineering__reliability,
  .engineering__seckill-grid {
    grid-template-columns: 1fr;
  }

  .engineering__flow {
    flex-direction: column;
    align-items: stretch;
  }

  .engineering__arrow {
    transform: rotate(90deg);
    text-align: center;
  }
}
</style>
