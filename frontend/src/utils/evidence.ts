/**
 * Values are frozen public engineering observations from docs/evidence;
 * not live telemetry and not production SLA.
 */
export const engineeringEvidence = {
  note: '经过本地双机工程验证的最终证据；并非实时遥测，不代表生产 SLA 或线上容量。',
  tests: {
    count: 1006,
    failures: 0,
    errors: 0,
    skipped: 5,
  },
  officialBenchmarkRuns: 36,
  independentDatabases: 4,
  springProcesses: 5,
  transactionFlow: ['Lua 原子准入', 'Redis Stream', 'Consumer Group / PEL', 'MySQL 事务'],
  reliabilitySides: ['重试', 'DLQ', '补偿', 'Outbox', '超时关单'],
  cache: {
    title: '双机 500 并发',
    qps: '≈17.4K',
    p95: '36 ms',
    p99: '45 ms',
    before: '≈0.879',
    after: '≈0.00013',
    reduction: 'Redis GET 调用量降低约 99.98%',
    note: 'Caffeine OFF → ON 对比；本地双机工程观测，不代表生产 SLA 或容量',
  },
  seckill1000: {
    persistedOrders: 1000,
    distinctUsers: 1000,
    duplicate: 0,
    oversell: 0,
    httpErrors: 0,
    p95: '≈40 ms',
    p99: '≈44 ms',
    pel: 0,
    dlq: 0,
    note: '1000 unique-user burst，连续 3 轮正确性验证通过',
  },
  reliability: [
    {
      title: 'Gateway 热点限流',
      result: '通过',
      detail: '精确 429 限流且不产生订单副作用',
    },
    {
      title: 'Identity 服务故障',
      result: '通过',
      detail: '展示型降级、必需 RPC fail-closed，恢复后自动复原',
    },
    {
      title: 'Redis 重启',
      result: '通过',
      detail: 'AOF 恢复会话 / Stream / PEL 状态',
    },
    {
      title: '秒杀期间 MySQL 故障',
      result: '通过',
      detail: '精确保留 PEL；恢复后同一 orderId 落库',
    },
  ],
  mysqlRecoveryNote: '本地恢复收敛观测：8.45 s',
}
