# CT 开局路线初始化修复

- 日期：2026-09-02
- 目标：修复 `d3_2.5x无粗.json` 中 CT 开局全员伏击、T方已经推进的非对称问题。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前回退基线：`6507cb1972ab78cdb2bbaf1a90df7cd457ad14f5`

## 根因

- 地图路线数据本身正常：TDM 的 `T→CT` 与 `CT→T` 各有 5 条路线，双方出生区都与对应路线首点重合。
- 新出生的 AI 尚未拥有 `routeId`，团队指挥官因此发布了没有路线和移动目标的 `CONTROL_ROUTE` 命令。
- 伏击策略正确地把这种命令识别成“不可执行任务”并停止随机巡逻。
- 旧系统只有在个体控制器先产生随机目标后才顺带挑选预设路线，形成“没有路线就不动、不动就永远得不到路线”的初始化死锁。
- T方能够移动属于目视、声音或手雷动作偶然触发了旧路线选择，并不是双方初始化成功。

## 架构修复

新的开局数据流为：

`地图路线目录 → RouteAssignmentPolicy → 团队战术订单 → 指定路线终点 → PathfindingModule → 移动仲裁器`

- 路线选择属于团队指挥层，不再依赖控制器随机巡逻。
- 路线分配策略是独立接口，可替换为风险优先、容量优先或模式专用策略，符合开闭原则。
- 默认策略只为尚未拥有路线的 AI 分配路线，已有路线承诺不会被开局分配器改写。
- 指挥官给订单附带明确路线ID和路线终点，伏击策略能区分“有效前进任务”与“真正无任务”。
- 有5条路线、10名新生AI时稳定分成每路2人；建议容量存在时按容量归一化负载。

## 文件变更

- `src/main/java/cs2d/AIControl/team/RouteAssignmentPolicy.java`：新增 9 行、删除 0 行。
  - 新增纯路线分配扩展接口。
- `src/main/java/cs2d/AIControl/team/BalancedRouteAssignmentPolicy.java`：新增 69 行、删除 0 行。
  - 新增确定性的最小占用路线策略。
  - 保留已有路线、过滤少于两个关键点的无效路线，并考虑地图建议容量。
- `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`：新增 48 行、删除 15 行。
  - 支持注入路线分配策略和战术细化器。
  - 用有效路线ID构造占用、风险与订单，并给默认推进订单设置路线终点和到达半径。
  - 不再生成只有路线标签、没有移动目标的初始推进命令。
- `src/main/java/cs2d/AIControl/team/TacticalTaskBoard.java`：本阶段新增 11 行、删除 5 行。
  - 已完成的有限移动任务在分配成员集合改变时允许重新开启。
  - 防止新复活AI被同一路线上一批成员的“已完成”状态永久锁住。
- `src/test/java/cs2d/AIControl/team/BalancedRouteAssignmentPolicyTest.java`：新增 77 行、删除 0 行。
  - 覆盖五路均衡、保留现有路线和无效路线过滤。
- `src/test/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinatorTest.java`：新增 37 行、删除 0 行。
  - 模拟本地图CT出生波次，验证10人获得5条明确路线、每路2人且目标为各路线终点。
- `src/test/java/cs2d/AIControl/team/TacticalTaskBoardTest.java`：本阶段新增 30 行、删除 0 行。
  - 覆盖复活成员加入后重新开启已完成推进任务。
- `.agent/changeHistory/2026-09-02-ct-route-bootstrap.md`：新增 71 行、删除 0 行；记录本阶段完整审计信息。

## 用户可见行为

- CT和T在开局都会立即获得各自地图路线，不再依赖枪声、目视或手雷动作才开始移动。
- `d3_2.5x无粗` 的5条路线会被均衡使用，不会全员卡在出生点，也不会为了启动路线恢复随机乱走。
- AI到达路线任务终点后仍按上一阶段规则原地伏击；没有地图路线时也保持伏击而不是随机巡逻。
- AI复活后会重新获得有效路线任务，不受旧任务完成状态影响。

## 验证

- `mvn -q -Dmaven.repo.local=O:\maven-repository -Dtest=BalancedRouteAssignmentPolicyTest,AdaptiveTeamTacticalCoordinatorTest,TacticalTaskBoardTest,TacticalIdlePolicyTest test`：聚焦测试通过。
- `mvn -q -Dmaven.repo.local=O:\maven-repository test`：93项通过，0失败，0错误，0跳过。
- `git diff --check`：通过，仅有Git的LF/CRLF提示。
- 第一次使用默认Maven仓库运行失败，因为沙箱将仓库解析为不可写的 `C:\.m2\repository`；改用项目现有 `O:\maven-repository` 后通过，属于环境问题。

## 范围与回退

- 用户正在编辑的 `maps/d3_2.5x无粗.json` 只用于读取验证，未修改、未暂存。
- 既有未跟踪记录 `.agent/changeHistory/2026-08-31-revert-bot-tactics.md` 未修改。
- 本次没有新增依赖、配置或地图格式迁移。
- 需要重启服务端加载新策略，再观察CT与T开局是否同时沿5条路线推进。
- 本阶段及前序AI修复仍保留在工作区，尚未提交。
