# 最终结算复用 TAB 计分板

- 日期：2026-08-28
- 目标：删除重复的独立 Game Over 遮罩，让最终赛果直接复用完整 TAB 计分板。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`58f05c320d87d5677d02a64504f15a1e443128c4`
- 实现状态：修改保留在工作区，尚未创建实现提交。

## 文件变更

- `src/main/java/cs2d/client/GameClient.java`（+39/-49）：移除独立 `gameOverPane`；GAME_OVER 状态显示并锁定共用计分板；最终板增加胜方/波次结果和返回大厅按钮；普通 TAB 行为保持不变。
- `src/test/java/cs2d/client/GameClientProtocolTest.java`（+14/-0）：覆盖 PLAYING 使用普通计分板、GAME_OVER 使用最终计分板模式。
- `.agent/changeHistory/2026-08-28-reuse-scoreboard-for-game-over.md`（+38/-0）：本次变更审计记录。

## 用户可见变化

- 比赛结束后背景上方直接显示 `Final Scoreboard`，包含原 TAB 计分板的完整玩家统计。
- 最终计分板顶部显示 CT/T 胜利、平局、死斗结束或僵尸生存波次。
- `Back to Lobby` 移入最终计分板；结束状态下 TAB 不会把最终板隐藏。
- 删除了功能重复的独立 Game Over 面板，普通游戏中的 TAB 开关与长按行为不变。
- 没有新增协议、依赖、配置或数据迁移。

## 验证

- `mvn test`：通过；10 项测试，0 失败、0 错误。
- `git diff --check`：通过；仅提示现有换行符将在 Git 后续写入时转换为 CRLF。
- 客户端 Maven 编译成功。

## 既有工作区内容

- `cs2d_settings.json` 在本次开始前已经被用户修改，本次没有编辑，也没有把它计入实现文件。

## 回退与限制

- 可回退到基线提交 `58f05c320d87d5677d02a64504f15a1e443128c4`。
- JavaFX 最终布局已通过编译与逻辑测试，仍建议实际完成一局检查不同分辨率下的视觉效果。
- 运行中的客户端需要重启后才会加载本次 Java 修改。
