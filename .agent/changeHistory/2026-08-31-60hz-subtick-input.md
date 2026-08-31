# 60Hz 权威模拟与 Sub-tick 输入

- 日期：2026-08-31
- 目标：将服务端权威模拟调整为 60Hz，引入带时间戳、排序、去重和 ACK 的 Sub-tick 输入，同时保持 30Hz 网络快照与旧版移动手感。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`021cb8ea6575f6d2b3f1fdd08a2c3c1a17cdc106`
- 实现状态：实现改动保留在工作区，尚未创建最终提交。

## 文件级变更

- `src/main/java/cs2d/AIControl/BW/ServerStatusWindow.java`：+4/-4；监控标签改为实际 60Hz 游戏逻辑与 30Hz 网络广播。
- `src/main/java/cs2d/server/GameServer.java`：+124/-4；协议升级到 v3；权威 Tick 改为 60Hz；接收紧凑 Sub-tick 批次；在主线程按序应用；30Hz 返回输入 ACK；断线时清理输入邮箱。
- `src/main/java/cs2d/server/GameState.java`：+176/-48；人物、手雷和烟雾在每个 60Hz 主 Tick 内执行两个旧 120Hz 运动子步；按 Sub-tick 占空比折算移动输入；快速射击与交互边沿按时序执行。
- `src/main/java/cs2d/server/HighPrecisionTimer.java`：+1/-1；移除误导性的固定 120TPS 注释。
- `src/main/java/cs2d/server/NetworkBroadcaster.java`：+1/-1；广播线程说明改为与实际权威频率无关。
- `src/main/java/cs2d/server/SubtickInputBuffer.java`：+64/-0；新增每玩家有界输入邮箱、重发去重、时序排序与 ACK 游标。
- `src/main/java/cs2d/server/SubtickInputCommand.java`：+58/-0；新增协议命令模型、按钮位掩码、字段范围验证和 Sub-tick 比例换算。
- `src/main/java/cs2d/server/TickRateScaler.java`：+45/-0；新增旧 120Hz 运动常量到目标 Tick 率的等价换算工具。
- `src/test/java/cs2d/server/GameLoopLoadSheddingTest.java`：+4/-4；断言更新为 60Hz 模拟仍产生 30Hz 快照。
- `src/test/java/cs2d/server/NetworkProtocolTest.java`：+2/-2；分片协议断言升级为 v3。
- `src/test/java/cs2d/server/SubtickInputBufferTest.java`：+76/-0；覆盖乱序、重发去重、非法字段和 Tick 尾部移动占空比。
- `src/test/java/cs2d/server/TickRateScalerTest.java`：+27/-0；覆盖两个旧物理子步的速度、摩擦和累计位移。
- `.agent/changeHistory/2026-08-31-60hz-subtick-input.md`：+45/-0；本记录。

## 协议与可见行为

- 协议版本由 2 升级为 3，旧 v2 客户端会被明确拒绝；服务端和客户端必须一起重启升级。
- 输入命令采用固定 8 项数组：`[sequence, clientTick, subTick, clientTimeNanos, angle, buttons, pressed, released]`。
- 服务端按 `clientTick -> subTick -> sequence` 执行；重复 UDP 命令不会重复生效。
- 60Hz 主循环不降低人物/投掷物速度：每个主 Tick 内保留两个旧 120Hz 运动积分子步。
- 状态广播仍为 30Hz，完整状态仍每 3 个快照一次；不会因服务端改为 60Hz 而翻倍带宽。

## 验证

- `mvn -q -DskipTests compile`：通过。
- `mvn -q test`：通过，27 项测试，0 失败，0 错误。
- `git diff --check`：通过；仅报告 Git 的 LF/CRLF 工作区提示，无空白错误。

## 迁移、回退与待验证

- 回退点：`git switch main` 后可从基线 `021cb8e` 建立分支或检出对应文件；不要使用破坏工作区的强制重置。
- 必须与协议 v3 客户端一起运行；混用旧客户端无法连接是预期行为。
- 仍需人工联机验证：WASD 起停、点射/连射、低抛、安包/拆弹、死亡后控制 BOT、丢包重传，以及 50 人大地图的 TPS 与上下行带宽。
- 本次没有把 30Hz 状态快照提高到 60Hz，也没有修改地图、AI 决策质量或画面精度。
