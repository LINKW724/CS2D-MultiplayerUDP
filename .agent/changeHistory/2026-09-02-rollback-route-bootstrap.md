# 回退 CT 开局路线初始化阶段

- 日期：2026-09-02
- 目标：按用户要求回退最后一阶段的 AI 自动路线启动行为，恢复到该阶段实施前的交战与移动逻辑。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 回退前安全快照：`b85f95d772ef9a5e97212e6d9a1762e0601e7cd6`

## 回退范围

- 仅撤销 2026-09-02 的 CT/T 开局路线自动分配阶段。
- 保留此前的移动意图仲裁、局部避障、伏击闲置策略、团队战术任务、声音吸引力限制和其他已认可优化。
- `maps/d3_2.5x无粗.json` 是用户已有修改，本次未编辑、未暂存、未提交。
- 客户端仓库没有任何修改。

## 文件变更

- `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`：新增 15 行、删除 47 行。
  - 移除开局路线分配策略注入。
  - 恢复只使用 AI 已经持有的 `routeId`。
  - 恢复路线中点作为任务目标，不再给全部新生 AI 强制设置路线终点移动目标。
- `src/main/java/cs2d/AIControl/team/TacticalTaskBoard.java`：新增 5 行、删除 11 行。
  - 恢复已完成任务的终态保持规则。
  - 移除因复活成员变化而重新开启已完成推进任务的逻辑。
- `src/test/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinatorTest.java`：新增 0 行、删除 37 行。
  - 删除整批新生 AI 自动均分五条路线的阶段性测试。
- `src/test/java/cs2d/AIControl/team/TacticalTaskBoardTest.java`：新增 0 行、删除 30 行。
  - 删除复活成员触发已完成推进任务重开的阶段性测试。
- `src/main/java/cs2d/AIControl/team/BalancedRouteAssignmentPolicy.java`：删除文件，共 69 行。
- `src/main/java/cs2d/AIControl/team/RouteAssignmentPolicy.java`：删除文件，共 9 行。
- `src/test/java/cs2d/AIControl/team/BalancedRouteAssignmentPolicyTest.java`：删除文件，共 77 行。
- `.agent/changeHistory/2026-09-02-ct-route-bootstrap.md`：删除旧阶段记录，共 71 行，避免记录描述与当前行为不一致。
- `.agent/changeHistory/2026-09-02-rollback-route-bootstrap.md`：新增本回退记录。

## 用户可见行为

- 不再由团队协调器在出生时为所有无路线 AI 立即均分地图路线。
- 不再强制整批 AI 从出生点持续推进到路线终点。
- AI 的交战、移动和路线获得方式恢复到该阶段之前。
- 已完成的推进任务不会因为另一名 AI 复活并加入而自动重开。

## 验证

- `mvn -q '-Dmaven.repo.local=O:\maven-repository' '-Dtest=AdaptiveTeamTacticalCoordinatorTest,TacticalTaskBoardTest,TacticalIdlePolicyTest' test`：通过。
- `mvn -q '-Dmaven.repo.local=O:\maven-repository' test`：全量测试通过。
- `git diff --check`：通过，仅有现有 LF/CRLF 提示。
- 回退差异与上一阶段记录的文件及行数相互对应。

## 状态与回滚

- 回退前状态已提交为安全快照 `b85f95d772ef9a5e97212e6d9a1762e0601e7cd6`，如需恢复这次撤销的路线启动行为可从该提交取回。
- 本次回退本身尚未提交，保留在工作区等待用户实机确认。
- 需要重新启动服务端才能加载恢复后的 AI 策略。
