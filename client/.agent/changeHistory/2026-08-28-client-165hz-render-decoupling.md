# 客户端165Hz渲染解耦

- 日期：2026-08-28
- 目标：让客户端以165Hz独立刷新本地视觉与预测，同时保持服务器权威模拟和输入发送为120Hz。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`c63c7740b9628339c1ea695aff3e3b1936007a50`
- 实现状态：改动保留在工作区，尚未提交。
- 用户已有改动：`cs2d_settings.json` 未修改、未纳入实现。

## 文件变化

- `src/main/java/cs2d/ClientMain.java`：新增24行，删除0行。
  - 默认配置客户端目标渲染率为165Hz。
  - 在JavaFX启动前启用高频Pulse时钟，绕过JavaFX 17原生VSync错误停留在约120Hz的问题。
  - 支持通过 `-Dcs2d.renderHz=<频率>` 覆盖目标刷新率，合法范围为30～500Hz。
- `src/main/java/cs2d/client/GameClient.java`：新增75行，删除62行。
  - 将服务器模拟频率120Hz、输入发送频率120Hz和客户端渲染频率165Hz拆成独立常量。
  - 删除每8ms把网络批次异步塞入JavaFX队列的独立时钟；改为网络线程只入队、每个渲染帧边界消费。
  - 主Canvas、镜头、位置外推、后坐力、特效和HUD按客户端渲染频率更新。
  - 本地鼠标瞄准视觉每个165Hz渲染帧更新，发送给服务器仍保持120Hz。
  - 将错误的“FX隐性开销”替换为帧调度等待，并输出真实FPS、1% Low、p99、最大帧时间和严重迟帧数。
- `src/main/java/cs2d/client/FramePacingMonitor.java`：新增71行，删除0行。
  - 新增真实渲染帧间隔采集与1% Low/p99/最大帧时间统计。
- `src/main/java/cs2d/client/RenderFrameScheduler.java`：新增38行，删除0行。
  - 将JavaFX高频Pulse转换成稳定目标渲染率。
  - 长时间停顿后跳过缺失帧，不进行爆发式补帧。
- `src/test/java/cs2d/client/FramePacingMonitorTest.java`：新增31行，删除0行。
  - 验证真实FPS、1% Low、p99、最大帧时间和刷新率边界。
- `src/test/java/cs2d/client/RenderFrameSchedulerTest.java`：新增31行，删除0行。
  - 验证1kHz Pulse输入每秒产生约165个游戏帧。
  - 验证长暂停后不会连续补帧。
- `.agent/changeHistory/2026-08-28-client-165hz-render-decoupling.md`：本变更记录。

## 行为与配置

- 默认目标：165Hz，理论帧间隔约6.061ms。
- 服务器权威模拟：继续120Hz。
- 客户端输入发送：继续120Hz。
- 客户端视觉瞄准、镜头、后坐力、特效、状态外推和Canvas：目标165Hz。
- 可使用 `-Dcs2d.renderHz=240` 切换到240Hz。
- 可使用 `-Djavafx.animation.fullspeed=false` 禁用高频Pulse；禁用后JavaFX可能重新受系统报告的120Hz节拍限制。

## 验证

- `mvn "-Dmaven.repo.local=C:\Users\小麦\.m2\repository" -q test`：通过。
  - `GameClientProtocolTest`：5项通过。
  - `FramePacingMonitorTest`：2项通过。
  - `RenderFrameSchedulerTest`：2项通过。
- `mvn "-Dmaven.repo.local=C:\Users\小麦\.m2\repository" -q -DskipTests package`：通过。
- `git diff --check`：通过；仅有仓库现有LF/CRLF转换提示。

## 手工验证与限制

- 必须完全重启客户端，让JavaFX启动前的Pulse设置生效。
- 启动日志应显示 `[Render] Client render target: 165 Hz`。
- 进入游戏后观察 `[REAL]` 行；稳定时实际FPS应接近165、帧间隔约6.061ms。
- 硬件实际呈现仍不能超过Windows为当前显示器设置的刷新率。
- 当前本地移动使用服务器速度的165Hz外推和平滑校正；尚未复制服务端完整墙体/玩家碰撞，也没有实现输入命令序号回放式的完整CS2预测与回滚，避免本轮引入穿墙或大幅橡皮筋。
- 回退本轮实现可恢复到基线 `c63c774`；不要覆盖用户自己的 `cs2d_settings.json`。
