# 恢复死亡后操控人机

- 日期：2026-08-28
- 目标：恢复团队模式玩家死亡后按 E 自动操控同队人机的原版行为。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`385d06fc47c36bcbef0ac456dcd01334e837a602`
- 实现状态：改动保留在工作区，尚未提交。

## 文件变化

- `src/main/java/cs2d/server/GameServer.java`：新增 21 行，删除 3 行。
  - 将 `requestControlBot.targetId` 明确建模为可选字段。
  - 缺失、null 或旧客户端发送的空字符串均按“未指定目标”处理。
  - 有效目标继续进行字符串类型和长度校验，异常类型仍会被拒绝。
  - 未指定目标时保留 `GameState` 原有的同队 BOT 自动选择后备策略。
- `src/main/java/cs2d/server/GameState.java`：新增 9 行，删除 0 行。
  - 高频小包同步 `isAlive`、`spectatorMode`、`spectatorTargetId`，不再等待完整分片包才能更新死亡与夺舍状态。
- `src/test/java/cs2d/server/NetworkProtocolTest.java`：新增 34 行，删除 0 行。
  - 覆盖空目标、缺失目标、明确目标和非法数字目标。
- `.agent/changeHistory/2026-08-28-restore-bot-control.md`：新增 24 行，删除 0 行。

## 验证

- `mvn "-Dmaven.repo.local=C:/Users/小麦/.m2/repository" -q test`：通过。
- `git diff --check`：通过；仅有 LF/CRLF 提示。

## 回退与手工验证

- 可回退到基线 `385d06f`。
- 重启双端后，在 TDM 死亡期间按 E，验证能够控制同队、存活、未被占用且生命值高于 50 的 BOT。
