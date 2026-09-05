# 客户端周期性 Safepoint 冻结修复

- 日期：2026-08-31
- 目标：消除客户端每约 9～11 秒冻结 3～4 秒并最终触发服务端超时的问题。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修复前基线：`6f7c5b8`（`fix: avoid full-scene flashbang snapshots`）

## 根因证据

- JFR 记录到四次异常 `jdk.SafepointBegin` 等待：`3.10s`、`4.16s`、`4.43s`、`3.25s`。
- 对应时间分别为 `12:00:31.904`、`12:00:40.868`、`12:00:50.632`、`12:01:01.834`，与客户端长帧完全对齐。
- 真正的 G1 GC 工作仅约 `1.2～2.3ms`；卡顿发生在所有线程抵达 Safepoint 之前。
- JFR 原生采样中，`Client-Diagnostics-Thread` 43/44 次停在 `OperatingSystemImpl.getProcessCpuLoad0()`。
- 线程转储也抓到该诊断线程停在同一 Windows 原生调用。
- 因此根因是性能监控每两秒调用 Windows 原生进程负载查询；该调用偶发阻塞，随后 GC/VM Handshake 等待它响应，冻结整个客户端。

## 文件变更

### `src/main/java/cs2d/client/RuntimePerformanceMonitor.java`

- 行数：新增 26 行，删除 8 行。
- 移除 `com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()` 调用。
- 在现有 `ThreadMXBean` 扫描中累计所有 Java 线程 CPU 时间，不增加新的线程遍历。
- 使用“线程 CPU 时间增量 / 墙钟时间增量 / 处理器数”计算归一化客户端 CPU 百分比。
- 首次采样、线程集合变化和异常时间差返回安全的 `0%`，结果限制在 `0～100%`。
- 保留原有日志字段、FX/Prism 线程 CPU、GC、堆内存和线程状态监控。

### `src/test/java/cs2d/client/RuntimePerformanceMonitorTest.java`

- 行数：新增 17 行，删除 0 行。
- 新增 CPU 百分比计算测试，覆盖正常归一化、上限钳制、无时间增量和首次采样。

### `.agent/changeHistory/2026-08-31-client-safepoint-stall.md`

- 行数：新增 55 行，删除 0 行；记录本次 JFR 证据、实现、验证和回退信息。

## API、协议与体验

- 未改变网络协议、服务器逻辑、渲染刷新率、FOV 射线、画质、音频或输入行为。
- 性能日志中的“进程 CPU”现在是 Java 线程 CPU 的归一化估算；不包含少量纯 JVM 内部 GC/编译线程开销，但足以判断客户端负载且不会阻塞游戏。
- `cs2d_settings.json` 是修复前已有的用户本地改动，本次未触碰、未提交。

## 验证

- `mvn -Dtest=RuntimePerformanceMonitorTest test`：2 个测试通过。
- `mvn test`：34 个测试全部通过，0 失败，0 错误，0 跳过。
- `git diff --check`：通过，仅有仓库现有的 LF/CRLF 转换提示。

## 手动验证与回退

- 必须完全重启客户端以加载新类。
- 建议继续使用同一 JFR 参数运行 1～2 分钟；不应再出现数秒级 `SafepointBegin duration`，客户端也不应因冻结而被服务端判定超时。
- 本次实现随后作为下一轮客户端停顿排查的可回退基线提交保存。
- 如需回退，可从基线 `6f7c5b8` 对比恢复本次三个文件；此前的闪光快照和 60Hz/Sub-tick 修复均保留在基线中。
