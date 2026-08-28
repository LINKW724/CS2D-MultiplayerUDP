# Windows 计时精度与卡顿修复

- 日期：2026-08-28
- 目标：修复 120TPS 游戏循环在 Windows 上稳定退化到约 15.625ms 唤醒间隔的问题。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`c361ee9861689098ec7436b6798960978d97b9d7`
- 实现状态：改动保留在工作区，尚未提交。

## 文件变化

- `src/main/java/cs2d/server/HighPrecisionTimer.java`：新增 19 行，删除 5 行。
  - 测量 `parkNanos` 的实际超休眠，并保存最大调度器误差。
  - 后续等待扣除已观测误差；8.333ms 预算不足时跳过粗粒度 park，使用精确自旋。
  - 保留高精度系统上的 park 阶段，超休眠补偿上限为 20ms。
  - 中断检查不再清除线程的中断标志。
- `src/main/java/cs2d/server/GameServer.java`：新增 11 行，删除 2 行。
  - 性能日志拆分为循环间隔和真正的 Tick 工作耗时。
- `src/test/java/cs2d/server/HighPrecisionTimerTest.java`：新增 16 行，删除 0 行。
  - 验证粗粒度 Windows 超休眠会禁用 8.333ms Tick 内的 park，并验证补偿上限。
- `.agent/changeHistory/2026-08-28-windows-timer-precision.md`：新增 23 行，删除 0 行。

## 验证与限制

- `mvn "-Dmaven.repo.local=C:/Users/小麦/.m2/repository" -q test`：通过。
- `git diff --check`：通过；仅有 LF/CRLF 提示。
- JShell 独立探针被当前 JDK 的 Windows Preferences 注册表权限阻止，属于环境限制。
- 重启服务端后，预期平均循环间隔接近 8.333ms；Tick 工作耗时用于判断实际逻辑负载。
