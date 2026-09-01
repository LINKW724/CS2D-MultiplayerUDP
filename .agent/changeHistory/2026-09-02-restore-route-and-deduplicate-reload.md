# 恢复 AI 路线阶段并去除重复换弹事件

- 日期：2026-09-02
- 目标：撤销上一轮路线回退，恢复已验证的开局路线初始化；同时消除同一次换弹被服务端广播两遍的问题。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前安全快照：`8f7ed78ad1c2d0fbe6ff218f7f2f659a3356af33`

## 行为恢复

- 恢复 `RouteAssignmentPolicy` 扩展点和 `BalancedRouteAssignmentPolicy` 默认实现。
- 恢复无路线 AI 在出生时按地图预设路线均衡分配。
- 恢复团队命令携带路线终点和明确移动目标。
- 恢复新复活成员加入时重新开启已完成推进任务。
- 删除上一轮路线回退记录，恢复路线启动阶段记录。

## 声音根因修复

- `Player.startReload()` 已经产生一次带 `sourcePlayerId` 的换弹事件。
- `GameState` 的上层换弹处理过去又广播一次完全相同的事件。
- 本次删除第二次广播，正常换弹仍保留一次声音，不改变真实换弹频率。

## 文件变更

- `.agent/changeHistory/2026-09-02-rollback-route-bootstrap.md`：删除 53 行。
- `.agent/changeHistory/2026-09-02-ct-route-bootstrap.md`：恢复路线阶段记录，新增 56 行。
- `src/main/java/cs2d/AIControl/team/RouteAssignmentPolicy.java`：新增 9 行。
- `src/main/java/cs2d/AIControl/team/BalancedRouteAssignmentPolicy.java`：新增 69 行。
- `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`：新增 47 行、删除 15 行。
- `src/main/java/cs2d/AIControl/team/TacticalTaskBoard.java`：新增 11 行、删除 5 行。
- `src/test/java/cs2d/AIControl/team/BalancedRouteAssignmentPolicyTest.java`：新增 77 行。
- `src/test/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinatorTest.java`：新增 37 行。
- `src/test/java/cs2d/AIControl/team/TacticalTaskBoardTest.java`：新增 30 行。
- `src/main/java/cs2d/server/GameState.java`：新增 1 行、删除 7 行，移除重复换弹广播。

## 验证

- 路线策略、协调器和任务板聚焦测试通过。
- 服务端全量 Maven 测试通过。
- 恢复的四个既有路线/任务文件内容哈希与回退前快照一致。
- `git diff --check` 通过，仅有现有 LF/CRLF 提示。

## 范围与状态

- 用户修改的 `maps/d3_2.5x无粗.json` 未编辑、未暂存、未提交。
- 本次实现尚未提交，保留在工作区等待实机验证。
- 需要重启服务端加载恢复后的路线策略和换弹事件修复。
