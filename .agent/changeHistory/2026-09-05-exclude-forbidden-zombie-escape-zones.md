# 僵尸模式逃生路线排除复活禁区

- 日期：2026-09-05
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`4a1df7b6eee5039df91b3ef78950a95469d4615a`
- 目标：禁止幸存者 AI 将僵尸模式逃生终点或路线航点选择在地图复活禁区内。
- 状态：实现保留在工作区，尚未提交。

## 基线处理

- 开工前将已验证的出生重叠与卡死回收修复提交为 `4a1df7b`（`fix: recycle stalled zombie spawns`）。
- 本轮仅增加复活禁区查询及逃生可行走过滤。

## 逐文件变更

- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java`（+1/-0）：逃生与局部移动共用的 `walkable` 判断直接排除复活禁区，因此终点和中间航点都无法进入。
- `src/main/java/cs2d/server/GameState.java`（+4/-0）：公开只读的复活禁区查询入口。
- `src/main/java/cs2d/server/SpawnPointValidator.java`（+6/-0）：复用完整角色圆形占地判断，而不是只检查坐标中心所在网格。
- `src/test/java/cs2d/server/SpawnPointValidatorTest.java`（+2/-0）：验证禁区内外判断。
- `.agent/changeHistory/2026-09-05-exclude-forbidden-zombie-escape-zones.md`（新增 32 行）：本记录。

## 行为与验证

- 只影响使用 `ZOMBIEcontrol.walkable` 的僵尸模式 AI 路线选择，不改变出生禁区数据、协议或其他模式。
- `mvn -q -Dtest=SpawnPointValidatorTest,ZombieEscapeRoutePlannerTest test`：通过。
- `git diff --check`：通过；仅有仓库既有 LF/CRLF 转换提示。

## 部署与回退

- 重新构建并重启服务器后生效。
- 回退以 `4a1df7b6eee5039df91b3ef78950a95469d4615a` 为准；本轮实现尚未提交。
