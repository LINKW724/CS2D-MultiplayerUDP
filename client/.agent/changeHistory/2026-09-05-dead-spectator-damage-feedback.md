# DEAD 观战伤害数字

- 日期：2026-09-05
- 仓库：`O:/java/games/CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`90284847784a82377beebb7554c75af05f5bdbb4`
- 目标：让死亡附着观战者按 Team/All 设置正确接收僵尸模式伤害数字。
- 状态：本轮实现尚未提交。
- 保留用户状态：未修改、未暂存 `cs2d_settings.json`。

## 基线处理

- 修改前已将上一轮验证通过的伤害数字分类提交为 `9028484`；客户端运行时设置继续排除。

## 逐文件变更

- `src/main/java/cs2d/client/DamageFeedbackViewerContext.java`（新增 8 行）：明确区分存活玩家、死亡附着观战者和无玩家实体的独立观战者。
- `src/main/java/cs2d/client/DamageFeedbackAudiencePolicy.java`（+7/-2）：附着观战者在 Zombie Mode + All 下可接收任意攻击阵营反馈；Team 仍要求与当前视角阵营一致。
- `src/main/java/cs2d/client/GameClient.java`（+21/-1）：生成观看身份；死亡时优先使用服务器 `spectatorTargetId` 对应实体的阵营，不再盲用死亡后可能已变成 ZOMBIE 的自身阵营。
- `src/test/java/cs2d/client/DamageFeedbackAudiencePolicyTest.java`（+24/-13）：迁移到强类型观看身份，并覆盖 DEAD 的 Team 跟随和 All 全局接收。
- `.agent/changeHistory/2026-09-05-dead-spectator-damage-feedback.md`：本记录。

## 行为与兼容性

- 存活玩家维持原阵营过滤。
- DEAD + Team 跟随当前服务器观战目标阵营。
- DEAD + All 在僵尸模式接收所有带攻击阵营的伤害反馈。
- 初始独立观战者维持 All 全局接收、Team 不接收的既有行为。
- Mine 的语义不变：死亡后没有自己的输出时不会显示队友伤害。
- 无设置格式、协议或依赖变化。

## 验证

- `mvn -q '-Dtest=DamageFeedbackAudiencePolicyTest' test`：通过。
- `mvn -q test`：客户端完整测试通过。
- `git diff --check -- . ':!cs2d_settings.json'`：通过；仅报告既有 LF/CRLF 转换提示。

## 部署与回退

- 重启客户端后生效；实机应分别验证死亡后的 Team、All 和 Mine。
- 回退时以 `90284847784a82377beebb7554c75af05f5bdbb4` 为参照，只撤销本记录列出的实现文件，并保留 `cs2d_settings.json`。
