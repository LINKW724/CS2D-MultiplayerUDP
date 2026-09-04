# 战斗反馈数字分类配色

- 日期：2026-09-05
- 仓库：`O:/java/games/CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`b20095fe7aeef3cc6b4e8194dd68e3f428fe1da6`
- 目标：将伤害数字按语义分类，并让 All 范围包含自己受伤与未来回血反馈。
- 状态：本轮改动尚未提交。
- 保留用户状态：未修改、未暂存 `cs2d_settings.json`。

## 逐文件变更

- `src/main/java/cs2d/client/DamageNumberType.java`（新增 12 行）：定义普通、爆头、受伤、回血、爆炸/燃烧、近战六种视觉语义。
- `src/main/java/cs2d/client/DamageNumberClassifier.java`（新增 28 行）：以服务器来源、爆头标记和本地受击视角执行集中优先级分类；兼容缺少新字段的旧服务器。
- `src/main/java/cs2d/client/DamageNumberEvent.java`（+3/-2）：事件由爆头布尔值升级为强类型视觉语义。
- `src/main/java/cs2d/client/DamageNumberSystem.java`（+31/-11）：映射白、橙、红、绿、紫、蓝六色；受伤显示负号、回血显示正号；只合并相同语义数字。
- `src/main/java/cs2d/client/DamageNumberVisibility.java`（+11/-5）：All 新增自己受伤/回血权限，并更新说明。
- `src/main/java/cs2d/client/DamageNumberModePolicy.java`（+4）：暴露按模式查询自己反馈权限。
- `src/main/java/cs2d/client/GameSettings.java`（+4）：转发自己反馈设置查询。
- `src/main/java/cs2d/client/DamageFeedbackAudiencePolicy.java`（+3/-2）：本地玩家是受击者时拒绝队伍广播副本，防止同一击出现红色和来源色两份数字。
- `src/main/java/cs2d/client/GameClient.java`（+19/-8）：区分本地攻击者与本地受击者、接入分类器，并修正受击事件误增本地输出统计。
- `src/test/java/cs2d/client/DamageNumberClassifierTest.java`（新增 30 行）：覆盖完整优先级和旧协议回退。
- `src/test/java/cs2d/client/DamageNumberSystemTest.java`（+31/-11）：覆盖精确颜色、正负号、分类合并、容量和清理。
- `src/test/java/cs2d/client/DamageNumberModePolicyTest.java`（+4）：验证只有 All 显示自己受伤/回血。
- `src/test/java/cs2d/client/DamageFeedbackAudiencePolicyTest.java`（+9/-1）：验证本地受击者去重。
- `.agent/changeHistory/2026-09-05-combat-feedback-colors.md`：本记录。

## 行为

- 普通白色；爆头橙色；自己受伤红色并显示 `-N`；回血绿色并显示 `+N`；手雷/燃烧紫色；刀、拳头和僵尸爪蓝色。
- 优先级：回血 > 本地受伤 > 爆头 > 爆炸/燃烧 > 近战 > 普通。自己被僵尸爪击仍按“自己受伤”显示红色；旁观或来源方看到该爪击时显示蓝色。
- Mine 与 Team 含义不变；All 才额外显示自己的受伤与未来回血反馈。
- 所有颜色保留深色描边；旧服务器缺少 `feedbackKind` 时按普通/爆头继续显示。

## 验证

- 聚焦分类、渲染、范围和观战策略测试：通过。
- `mvn -q test`：完整测试通过。
- Impeccable detector：通过，0 项发现。
- `git diff --check -- . ':!cs2d_settings.json'`：通过；仅报告既有 LF/CRLF 转换提示。

## 回退

- 以基线 `b20095fe7aeef3cc6b4e8194dd68e3f428fe1da6` 为参照，仅撤销本记录列出的实现文件；必须保留用户的 `cs2d_settings.json`。
