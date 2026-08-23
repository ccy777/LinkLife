# LinkLife 性能测试与故障演练

```text
performance-test/
├── stage6a/       JMeter 场景、压测编排与 JTL 分析
├── stage6b/       Gateway、Identity、Redis、MySQL 故障演练
├── two-machine/   双机正式结果汇总脚本
└── deploy/        压测与故障演练 Compose 配置
```

## 功能

- `stage6a/`：执行商铺热点查询、商铺分类、动态热点和秒杀突发测试，并分析原始 JTL；
- `stage6b/`：执行 Gateway 热点限流、Identity 中断、Redis 重启和 MySQL 中断恢复演练；
- `two-machine/`：将双机 JTL、Redis 监控和秒杀一致性结果汇总为公开逐轮 CSV。

## 本地结果

JTL、日志和环境快照保存在 `.linklife-local/`：

```text
.linklife-local/evidence/stage6a/
.linklife-local/evidence/stage6b/
.linklife-local/results/
.linklife-local/reports/
```

仓库中保留适合直接查看的结果：

- [性能测试报告](../docs/performance.md)
- [双机正式测试逐轮数据](../docs/evidence/two-machine-results.csv)
- [可靠性验证报告](../docs/reliability.md)

重新执行脚本时，本地结果写入 `.linklife-local/`，不会覆盖已提交的报告。
