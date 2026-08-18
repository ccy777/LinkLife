/**
 * Values are frozen public engineering observations from docs/evidence;
 * not live telemetry and not production SLA.
 */
export const engineeringEvidence = {
  note: '经过本地环境验证的工程证据；本地 Docker 观测，并非实时遥测。',
  tests: {
    count: 1006,
    failures: 0,
    errors: 0,
    skipped: 5,
  },
  officialBenchmarkRuns: 42,
  independentDatabases: 4,
  springProcesses: 5,
  transactionFlow: ['Lua 原子准入', 'Redis Stream', 'Consumer Group / PEL', 'MySQL 事务'],
  reliabilitySides: ['重试', 'DLQ', '补偿', 'Outbox', '超时关单'],
  cache: {
    before: '≤1',
    after: '≤0.001',
    note: '本地测量窗口观测值',
  },
  seckill300: {
    accepted: 300,
    orders: 300,
    distinctUsers: 300,
    duplicate: 0,
    p95MedianMs: 252,
    note: '本地 Docker 基准测试',
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
