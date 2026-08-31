# 165Hz 本地采样与 60Hz Sub-tick 输入发送

- 日期：2026-08-31
- 目标：客户端保持 165Hz 渲染与本地瞄准采样，使用独立 60Hz 输入线程向 60Hz 服务端发送带 Sub-tick 时间的可靠 UDP 输入批次。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`e854bec94c0f75aeb3b2435b2f23a0dd26ebc102`
- 实现状态：实现改动保留在工作区，尚未创建最终提交。

## 文件级变更

- `src/main/java/cs2d/client/GameClient.java`：+94/-45；协议升级到 v3；渲染帧只采样输入；独立 60Hz 线程发送；WASD、静步、交互、开火和低抛边沿立即发送；处理服务端 ACK；断线和换会话时清理窗口。
- `src/main/java/cs2d/client/SubtickInputTransmitter.java`：+121/-0；新增时间戳采样、按钮边沿、紧凑批次、16 条有界重发窗口和 ACK 回收。
- `src/test/java/cs2d/client/GameClientProtocolTest.java`：+1/-1；协议测试升级到 v3。
- `src/test/java/cs2d/client/SubtickInputTransmitterTest.java`：+67/-0；覆盖同 Tick 按下/释放、ACK 回收以及最大批次小于服务端 4096 字节 UDP 缓冲。
- `.agent/changeHistory/2026-08-31-60hz-subtick-input.md`：+37/-0；本记录。

## 协议与可见行为

- JavaFX 渲染目标仍为 165Hz；鼠标准星和本地显示不等待服务器 Tick 或状态包。
- 每个显示帧发布不可变输入快照，独立线程稳定以 60Hz 发送；显示掉帧不会把输入发送频率一起拖低。
- 按下/释放边沿立即加入命令，并在收到 30Hz ACK 前小窗口重发，避免 UDP 单包丢失吞掉点射或移动起停。
- 单批最多重发最近 16 条（约 267ms）；紧凑命令数组的最坏测试包小于服务端 4096 字节接收缓冲。
- 服务端状态接收仍为 30Hz；客户端继续以 165Hz 插值。旧 120Hz 速度单位由兼容常量保留，预测速度不会减半。

## 验证

- `mvn -q -DskipTests compile`：通过。
- `mvn -q test`：通过，33 项测试，0 失败，0 错误。
- `git diff --check`：通过；仅报告 Git 的 LF/CRLF 工作区提示，无空白错误。

## 迁移、回退与待验证

- 回退点：`git switch main` 后可从基线 `e854bec` 建立分支或检出对应文件；不要使用破坏工作区的强制重置。
- 必须与协议 v3 服务端一起重启运行；旧 v2 服务端不兼容。
- `cs2d_settings.json` 是用户原有的本地设置修改，本次未编辑、未纳入实现或变更行数。
- 仍需人工联机验证：165Hz 转头、WASD 起停、点射/长按射击、右键低抛、E 交互与控制 BOT，以及模拟丢包后的输入连续性。
