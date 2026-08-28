# 恢复死亡后操控人机

- 日期：2026-08-28
- 目标：恢复团队模式玩家死亡后按 E 自动操控同队人机的原版行为。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前代码基线：`67e36883099f1ae3baa753aefe075201cc68a195`
- 实现状态：改动保留在工作区，尚未提交。
- 用户已有改动：`cs2d_settings.json` 保持原样，未纳入实现。

## 文件变化

- `src/main/java/cs2d/client/GameClient.java`：新增 12 行，删除 3 行。
  - 新增统一的人机控制请求构造方法。
  - `spectatorTargetId` 为空时省略该字段，让服务端执行同队 BOT 自动选择。
  - 有明确观战目标时继续发送目标 ID。
- `src/test/java/cs2d/client/GameClientProtocolTest.java`：新增 16 行，删除 0 行。
  - 验证空观战目标不会被序列化，明确目标仍会被发送。
- `.agent/changeHistory/2026-08-28-restore-bot-control.md`：新增 22 行，删除 0 行。

## 验证

- `mvn "-Dmaven.repo.local=C:/Users/小麦/.m2/repository" -q test`：通过。
- `git diff --check`：通过；仅有 LF/CRLF 提示。

## 回退与手工验证

- 可回退到代码基线 `67e3688`；不要覆盖用户自己的 `cs2d_settings.json`。
- 重启双端后，在 TDM 死亡期间按 E，再验证移动、射击、换弹和切枪都作用于被控制 BOT。
