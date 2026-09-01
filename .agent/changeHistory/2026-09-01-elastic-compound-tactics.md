# 第三阶段：弹性复合战术与指定路线执行

- 日期：2026-09-01
- 目标：在不建立固定小队的前提下，实现人数自适应的牵制＋侧翼、返场集结与波次推进，并让战术路线真正执行地图作者路线。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`5dd3d794666cec0d3e89a9c9225c2784e393440f`
- 实现状态：第一至第三阶段都保留在工作区，尚未提交。

## 架构变化

- 新增 `TacticalPlanRefiner` 开放扩展点；基础协调器先生成安全的独立任务，再依次由可插拔战术学说装饰计划。
- 默认注入 `ElasticManeuverRefiner`，但构造器允许游戏模式或外部扩展替换、删除或组合其他战术学说，无需修改协调器主体。
- `TacticalTask.operationId` 将多个临时任务关联为同一次复合行动；任务板保证所有行动分支都达到最低人数后才原子激活。
- 地图路线执行仍由现有 `PathfindingModule` 完成；指挥层只下发稳定 `routeId` 和战术目标，不依赖A*、按键或AI状态机。

## 文件变更

前两阶段仍未提交。下面对既有已修改文件同时标注第三阶段增量和相对共同基线的当前累计 `git diff --numstat`；新文件使用完整行数。

### `src/main/java/cs2d/AIControl/team/TacticalPlanRefiner.java`

- 新增 10 行。
- 定义纯快照＋不可变计划的战术装饰扩展点。

### `src/main/java/cs2d/AIControl/team/ElasticManeuverRefiner.java`

- 新增 308 行。
- 3人起可根据两条不重合的地图路线创建临时钳形行动。
- 3人时默认2人正面牵制、1人侧翼；4人以上最多2人侧翼、3人牵制，其余AI保留原独立任务。
- 正在响应枪声、支援、集结或已经执行侧翼的AI不会被复合战术强行抢走。
- 路线重合时拒绝制造“假包抄”，自动保留基础计划。
- 前线已有至少2名队友时，出生路线起点附近的1～2名返场AI先集结；达到3～5人后作为一个波次沿地图路线推进。
- 所有关系按当前快照临时生成；人数或局势不再满足时自动解除，没有持久固定小队。

### `src/main/java/cs2d/AIControl/team/TacticalTask.java`

- 第三阶段新增 11 行；当前文件共60行。
- 新增可选 `operationId`，并保留原独立任务构造器。

### `src/main/java/cs2d/AIControl/team/TacticalTaskBoard.java`

- 第三阶段新增 48 行；当前文件共246行。
- 预先计算每个任务经人数上限截断后的分配。
- 同一 `operationId` 下任一分支未达到最低人数时，整个行动保持 `PROPOSED`，不会只派正面组或只派侧翼组。
- 一个行动分支失败会把同一行动传播为失败；已经完成的分支视为满足原子行动条件。

### `src/main/java/cs2d/AIControl/team/TacticalOrder.java`

- 第三阶段相对前阶段新增约5行、恢复2行；相对共同基线当前累计新增23行、删除3行。
- 新增 `SUPPRESS`、`ASSEMBLE` 任务类型和 `SUPPRESSOR` 角色。

### `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`

- 第三阶段相对前阶段新增约19行、恢复1行；相对共同基线当前累计新增163行、删除13行。
- 默认装配弹性复合战术，并提供可注入的战术装饰器列表。
- 对新增任务类型补齐容量、优先级和远程声音规则。

### `src/main/java/cs2d/AIControl/A/PresetPathModule.java`

- 第三阶段新增“按稳定routeId精确选择路线”入口；相对共同基线当前累计新增46行、删除162行。
- 指挥官选路不再走轮转分配器，因此指定的侧翼路线不会被替换成另一条路线。

### `src/main/java/cs2d/AIControl/A/PathfindingModule.java`

- 新增 54 行，删除 0 行。
- 新增 `followPresetRoute(routeId, finalTarget)`。
- 从完整地图路线中选择离AI最近的接力点，并在战术目标最近的关键点处截断路线。
- 同路线、同目标的4Hz重复命令直接复用；同路线但目标改变时重新截断，避免继续冲过牵制位置。
- 战斗临时打断后可恢复指挥官指定路线。

### `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java`

- 新增 9 行，删除 0 行。
- `FLANK`、`SUPPRESS` 和带目标的 `ADVANCE` 优先执行指定地图路线；地图没有该路线时自动回退原A*行为。
- 眼前敌人仍高于团队移动命令，不会因执行路线而拒绝自卫。

### `src/test/java/cs2d/AIControl/team/ElasticManeuverRefinerTest.java`

- 新增 132 行。
- 覆盖3人原子钳形、重合路线拒绝假包抄、1～2人返场集结和3人波次推进。

### `src/test/java/cs2d/AIControl/team/TacticalTaskBoardTest.java`

- 第三阶段新增 32 行；当前文件共141行。
- 覆盖复合行动任一分支人数不足时全体等待、全部满足后同时激活。

### `src/test/java/cs2d/AIControl/route/PresetPathModuleCatalogIntegrationTest.java`

- 第三阶段新增 6 行；当前文件共68行。
- 验证稳定routeId能够精确取回指定的原始路线索引和关键点。

## 用户可见行为

- 3人以下自动降级：1人控线，2人保持补枪距离，不会硬拆成两路。
- 3人以上且有两条独立地图路线时，部分AI正面牵制，另一部分沿独立路线侧翼；不是所有人一起追声音。
- 复合行动缺少任何一组时不会半套启动。
- 零散返场AI不再一个接一个流水线送入前线；1～2人等待，3人起作为一波推进。
- 战术侧翼和波次推进真实复用 `_prePath.json` 路线，而不是只对一个坐标做直线A*。

## 保持不变

- 服务器60Hz、Sub-tick输入、4Hz团队规划、AI微操、射击、投雷、FOV和客户端代码未修改。
- 没有固定3～5人小队；最多人数只是单次临时任务容量。
- 没有地图路线、路线重合或人数不足时，继续使用第二阶段的独立任务和原有A*。

## 验证

- 聚焦测试：`ElasticManeuverRefinerTest`、`TacticalTaskBoardTest`、`PresetPathModuleCatalogIntegrationTest`、`AdaptiveTeamTacticalCoordinatorTest` 全部通过。
- `mvn -q test`：通过，62/62，0失败、0错误、0跳过。
- `git diff --check`：通过；仅提示仓库既有LF/CRLF自动转换策略。
- 新增文件尾随空白检查通过。

## 已知后续范围

- 返场判断使用“距离本队预设路线第一个关键点”的纯地图代理；地图路线应按本方出生区到敌方方向编写。没有合格路线时该能力自动关闭。
- 当前路线选择使用占用、风险和预计算重叠；尚未加入真实路径时间、通道宽度或火力可达时间。
- 尚未实现比分/剩余时间驱动的保守或激进学说，也没有战术状态管理界面。
- 当前仅接入团队死亡竞赛运行时；爆破模式仍使用原控制系统。

## 重启、实战验证与回退

- 需要重启服务端，客户端无需更新。
- 建议先开全局观战验证：3人钳形必须同时出现正面和侧翼；重合路线地图不能显示假包抄；阵亡返场的1～2人应在路线起点附近等待，3人后一起推进。
- 基线提交仍为 `5dd3d794666cec0d3e89a9c9225c2784e393440f`。
- 当前实现未提交；实战确认后再按用户要求提交。
- 未跟踪的 `.agent/changeHistory/2026-08-31-revert-bot-tactics.md` 不属于本次修改，未删除、未覆盖、未暂存。
