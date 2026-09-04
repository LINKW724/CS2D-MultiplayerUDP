# 伤害反馈权威分类

- 日期：2026-09-05
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`01729e72c38f28919459fa254ca419c3beb12621`
- 目标：由服务器权威标注普通、爆炸、燃烧、近战和预留回血反馈，支持客户端可靠分类上色。
- 状态：本轮改动尚未提交。

## 逐文件变更

- `src/main/java/cs2d/playerAndAi/CombatFeedbackKind.java`（新增 25 行）：集中定义反馈来源枚举，并从武器/伤害来源名称分类。
- `src/main/java/cs2d/playerAndAi/Player.java`（+6/-1）：伤害日志增加非空 `feedbackKind`，JSON 增加可选兼容字段。
- `src/main/java/cs2d/server/GameState.java`（+4/-3）：伤害结算写入权威来源；僵尸模式向攻击者阵营广播所有有归属伤害，使观战 All 可看到僵尸爪击等反馈。
- `src/test/java/cs2d/playerAndAi/CombatFeedbackKindTest.java`（新增 24 行）：覆盖枪械、手雷、C4、燃烧、刀、拳头和僵尸爪分类。
- `src/test/java/cs2d/playerAndAi/DamageLogEntryTest.java`（+3/-1）：验证协议字段序列化。
- `src/test/java/cs2d/server/DamageFeedbackAudienceTest.java`（+5/-3）：验证僵尸模式双阵营和同阵营实际伤害的广播归属。
- `.agent/changeHistory/2026-09-05-combat-feedback-categories.md`：本记录。

## 协议与兼容性

- `damage_event` 新增字符串字段 `feedbackKind`：`NORMAL`、`EXPLOSIVE`、`FIRE`、`MELEE`、`HEALING`。
- 保留原有 `dmg`、`hs`、坐标和事件类型；旧客户端会忽略新字段。
- 当前工程没有治疗结算机制，`HEALING` 是明确预留口；未来治疗逻辑应发送实际恢复量并使用该类型。

## 验证

- `mvn -q '-Dtest=CombatFeedbackKindTest,DamageLogEntryTest' test`：通过。
- `mvn -q test`：完整测试通过。
- `git diff --check`：通过；仅报告仓库既有 LF/CRLF 转换提示。

## 回退

- 以基线 `01729e72c38f28919459fa254ca419c3beb12621` 为参照，仅撤销本记录列出的文件，避免影响之后的用户改动。
