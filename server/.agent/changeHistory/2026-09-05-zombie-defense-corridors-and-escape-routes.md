# 僵尸入口纵深防线与多路线逃生

- 日期：2026-09-05
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`0b4e0ae4d872f5a4005f8e627966fe729ee0d860`
- 目标：让幸存者 AI 提前占据僵尸必经入口、分层后撤，并在被追击前准备多条宽阔逃生路线，避免钻入墙角或死胡同。
- 状态：实现保留在工作区，尚未提交。

## 基线处理

- 开工前发现上一轮安全出生修复尚未提交；已核对其源码、测试和记录，并单独提交为 `0b4e0ae`（`fix: validate zombie spawn locations`）。
- 本轮所有改动均建立在该可恢复基线上，没有修改客户端仓库、地图 JSON、协议或用户配置。

## 逐文件变更

- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java`（+100/-11）：以主路线加两条备用路线替换 250ms 单点逃跑；在威胁进入 520 像素时提前预演；路线承诺 1.2 秒；遇到停滞、截击风险或路线失效时切换备用路线；按分段航点执行并保留原武器机动策略。
- `src/main/java/cs2d/AIControl/zombie/ZombieEscapeRoutePlanner.java`（新增 160 行）：18 层、约 576 像素的局部机动图；按出口数、远距开放方向、路线/终点僵尸距离、队友间距、行程和僵尸到达时间评分；重罚单出口口袋；返回最多三条低重叠路线；支持从燃烧区内部向外逃生。
- `src/main/java/cs2d/AIControl/zombie/ZombieEscapePlanner.java`（+5/-76）：缩减为兼容门面，旧调用者仍可获得主路线终点，具体算法移入独立路线规划器。
- `src/main/java/cs2d/AIControl/zombie/ZombieDefenseCorridor.java`（新增 30 行）：入口走廊纯数据模型，保存监视方向、压力、ETA，以及前压/第一后撤/第二后撤阵位。
- `src/main/java/cs2d/AIControl/zombie/ZombieDefenseCorridorPlanner.java`（新增 120 行）：合并 210 像素内且流向相近的预测路线；沿代表路线生成 128/360/640 像素纵深防线；横向展开阵位并检查可行走、连线和火焰危险。
- `src/main/java/cs2d/AIControl/zombie/ZombieDefenseStateBoard.java`（新增 60 行）：保存入口 30 秒租约；按僵尸压力、幸存者有效火力和 ETA 选择防线层级；后撤后至少 8 秒才允许重新前推；下一层到位前保留掩护者，到位后释放掩护组。
- `src/main/java/cs2d/AIControl/zombie/ZombieDefenseAllocator.java`（+52/-20）：取消每条路线固定四人上限；最多部署约 72% 可战斗 AI，并保留至少 20% 容量作为机动预备队；按阵位容量和压力分配；支持掩护撤退与两层后撤角色。
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalCoordinator.java`（+27/-10）：在指挥层串联入口规划、状态板和分配器；入口命令优先于普通火线拉人；连续防守 8 秒后可把阵地升级为新 home；短暂失去预测时继续守当前入口。
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalOrder.java`（+1/-0）：增加 `COVER_WITHDRAWAL`、`FALL_BACK_LINE_1`、`FALL_BACK_LINE_2` 战术任务。
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalTaskBoard.java`（+19/-1）：增加 30 秒部署锚点租约及稳定阵地晋升，避免敌情短暂消失后立即返回出生点；死亡/清局时清理状态。
- `src/main/java/cs2d/server/ZombieTacticalRuntime.java`（+1/-0）：清局时同步清理入口防线状态。
- `src/test/java/cs2d/AIControl/zombie/ZombieEscapeRoutePlannerTest.java`（新增 50 行）：覆盖死胡同规避、多路线分流、预测截击和无可见僵尸时逃离火区。
- `src/test/java/cs2d/AIControl/zombie/ZombieDefenseCorridorPlannerTest.java`（新增 33 行）：覆盖相邻预测路线合并和三层纵深间距。
- `src/test/java/cs2d/AIControl/zombie/ZombieDefenseStateBoardTest.java`（新增 39 行）：覆盖预测短暂消失、后撤防抖、掩护组等待和到位释放。
- `src/test/java/cs2d/AIControl/zombie/ZombieDefenseAllocatorTest.java`（+25/-12）：更新多数前压、预备容量和交替后撤断言。
- `src/test/java/cs2d/AIControl/zombie/ZombieTacticalCoordinatorTest.java`（+15/-0）：覆盖预测消失后继续保留入口任务而非回出生点。
- `src/test/java/cs2d/AIControl/zombie/ZombieTacticalTaskBoardTest.java`（+12/-0）：覆盖临时部署锚点过期及稳定阵地晋升。
- `.agent/changeHistory/2026-09-05-zombie-defense-corridors-and-escape-routes.md`（新增 56 行）：本记录。

## 行为、接口与兼容性

- 逃跑目标不再是每 250ms 重选的单点；AI 会预先保存多条空间上不同的路线，并沿航点持续执行。
- 路线规划只使用执行者已知威胁、团队战术预测、地图可行走性和权威危险区，不新增客户端信息或网络字段。
- 防线从“每条预测路线一个点”升级为“多路线汇聚后的入口走廊”，并区分前压、掩护撤退和两层后撤。
- 出生位置仍是初始 home；入口部署先获得 30 秒租约，连续稳定防守 8 秒后晋升为新的长期 home，直到死亡或战术运行时清理。
- 未改变其他游戏模式的控制器、地图格式、存档、依赖和协议。

## 验证

- `mvn -q -DskipTests compile`：通过。
- 僵尸逃生、防线、分配、指挥和任务板相关测试：通过。
- `mvn -q test`：服务器完整测试通过；输出中的 `GameState is null` 与预设路线目录信息来自既有测试夹具，不是失败。
- `git diff --check`：通过；仅有仓库既有 LF/CRLF 转换提示。

## 限制、部署与回退

- 路线开放度来自运行时机动图采样，不需要地图作者手工标注；复杂超大地图仍建议实机观察 576 像素搜索范围是否足够。
- 入口合并和三层距离是通用默认值，实机可根据地图尺度继续微调，但不需要改动执行器接口。
- 需要重新构建并重启服务器后生效；建议实机验证 U 型房间、双出口、狭窄门洞、肉僵尸推进、燃烧区封路及多 AI 同时撤退。
- 回退以基线 `0b4e0ae4d872f5a4005f8e627966fe729ee0d860` 为准；本轮实现尚未提交，可按本记录列出的文件单独审查。
