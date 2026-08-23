# Stage6B 故障演练

`run_fault_drills.py` 针对本地微服务环境执行四组真实故障演练：

| 编号 | 场景 |
|---|---|
| A | Gateway Sentinel 热点限流 |
| B | Identity 中断、熔断、降级与恢复 |
| C | Redis 容器重建、AOF 恢复与服务重连 |
| D | MySQL 中断后，秒杀消息保留在 PEL 并恢复落库 |

## 运行

```bat
py performance-test\stage6b\run_fault_drills.py --help
```

## 结果位置

每组演练的环境、`evidence.json` 和汇总结果写入：

```text
.linklife-local/evidence/stage6b/
```

公开结果汇总见 [docs/reliability.md](../../docs/reliability.md)。
