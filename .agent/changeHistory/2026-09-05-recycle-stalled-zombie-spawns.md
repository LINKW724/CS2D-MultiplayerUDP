# 回收出生卡死僵尸并阻止出生重叠

- 日期：2026-09-05
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`7cd61db19bd2ec19cfff90683add8dfcad6bb798`
- 目标：修复波次僵尸重叠出生后长时间不动，并保证替换期间不会让 `zombiesLeft` 瞬间归零而错误进入下一波。
- 状态：本轮实现保留在工作区，尚未提交。

## 基线处理

- 开工前将已通过完整测试的按地图尺度逃生路线修正提交为 `7cd61db`（`fix: scale zombie escape routes to map size`）。
- 本轮只修改服务器波次僵尸的出生选择和出生期卡死回收，不修改客户端、网络协议、玩家僵尸或正常战斗死亡结算。

## 逐文件变更

- `src/main/java/cs2d/server/GameState.java`（+76/-51）：出生候选同时避开人类和所有存活僵尸；普通出生保持至少 60 像素角色间距；被回收僵尸的替代出生点优先离旧位置至少 250 像素；物理更新后检查出生期卡死；先成功生成替代者再静默移除旧实体；清理旧 AI 输入和伤害缓冲，不调用击杀、掉落或计分链。
- `src/main/java/cs2d/server/ZombieSpawnStallMonitor.java`（新增 54 行）：独立维护出生位置、初始生命值和按 ID 分散的 5–10 秒截止时间；累计移动达到 30 像素或生命值发生任何变化后永久退出出生回收监控；只返回仍存活且从出生开始未参与游戏的僵尸。
- `src/main/java/cs2d/server/ZombieSpawnPointSelector.java`（新增 32 行）：在静态合法出生池上统一执行人类安全距离、存活角色物理分离和旧出生点规避；严格条件不足时仍禁止物理重叠，并选择分离度最大的合法候选点。
- `src/test/java/cs2d/server/ZombieSpawnStallMonitorTest.java`（新增 37 行）：覆盖 5–10 秒截止时间、移动永久取消回收和掉血永久取消回收。
- `src/test/java/cs2d/server/ZombieSpawnPointSelectorTest.java`（新增 39 行）：覆盖避开现有僵尸、回收后远离旧出生点，以及放宽人类 400 像素条件时仍禁止物理重叠。
- `.agent/changeHistory/2026-09-05-recycle-stalled-zombie-spawns.md`（新增 42 行）：本记录。

## 行为与计数保证

- 监控对象仅为服务器 `zombies` 列表中新生成的波次 AI；人类感染形成的玩家僵尸不会被回收。
- 替代僵尸先创建成功，旧僵尸随后从列表移除，因此 `getRemainingZombieCount()` 在完整操作前后保持不变，不需要危险的手工 `left + 1`。
- 回收不是战斗死亡：不产生击杀、死亡数、分数、击杀音效、掉落物或伤害数字。
- 若新出生点创建失败，旧僵尸仍保留，不会丢失本波名额。
- 每只僵尸的回收时间按 ID 稳定分布在 5–10 秒，避免一批重叠僵尸同一帧集中替换。
- 未新增配置、依赖、迁移或协议字段。

## 验证

- `mvn -q -Dtest=ZombieSpawnStallMonitorTest,ZombieSpawnPointSelectorTest,SpawnPointValidatorTest test`：通过。
- `mvn -q test`：服务器完整测试通过；既有测试夹具输出不是失败。
- `git diff --check`：通过；仅有仓库既有 LF/CRLF 转换提示。

## 部署与回退

- 重新构建并重启服务器后生效；建议实机观察同一出生池连续生成、地图边缘出生和特殊肉僵尸波次。
- 回退以 `7cd61db19bd2ec19cfff90683add8dfcad6bb798` 为准；本轮实现尚未提交。
