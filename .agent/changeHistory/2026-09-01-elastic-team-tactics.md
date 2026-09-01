# TDM 弹性团队指挥与动态战术任务

- 日期：2026-09-01
- 目标：在不创建固定小队的前提下，让 TDM AI 根据存活人数、地图路线、死亡热区、支援距离和交战区动态分配任务，避免低人数过度分散与大队伍集体追逐枪声。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`57cf2fc643b64f3caffcef263ed50c0304bfe879`
- 实现状态：作为“全地图路线目录”第一阶段之前的独立恢复点提交。

## 设计边界

- `TacticalCoordinator` 只接收不可变 `TeamTacticalSnapshot`，不依赖 `GameState`、`Player`、A*、输入键或具体 AI 控制器。
- `TeamTacticalRuntime` 是唯一的服务端适配层，负责把可变世界状态转换为战术快照。
- 单体 AI 只消费通用 `TacticalOrder`；看见敌人、保命、射击、投雷和局部避让仍由原模块决定。
- `AIService` 通过构造器注入 `TacticalCoordinator`。增加新战术实现时可新增协调器并替换注入，不需要修改服务端循环或单体控制器。
- 旧版注释状态的高耦合 `playerAndAi/TeamCommander.java` 没有启用或修改。

## 文件变更

### `src/main/java/cs2d/AIControl/team/TacticalCoordinator.java`

- 新增 12 行。
- 定义可替换的团队战术协调接口。

### `src/main/java/cs2d/AIControl/team/TacticalOrderProvider.java`

- 新增 9 行。
- 定义单体 AI 查询当前高层命令的只读接口。

### `src/main/java/cs2d/AIControl/team/TacticalOrder.java`

- 新增 59 行。
- 定义任务类型、角色、路线、支援对象、移动目标、声源授权、风险和过期时间。
- 集合在构造时防御性复制，避免线程间共享可变集合。

### `src/main/java/cs2d/AIControl/team/TeamTacticalSnapshot.java`

- 新增 69 行。
- 定义与服务端实现无关的 AI、声音接触、风险热区和坐标快照。

### `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`

- 新增 452 行。
- 以每名 AI 的弹性任务代替固定 3～5 人小队。
- 1 人执行路线控制；2 人优先保持补枪距离；3 人保留路线锚点；4 人以上才允许从高风险或拥挤路线抽调非锚点执行侧翼。
- 使用路线占用、沿线死亡/掉枪风险、最近队友距离与改变现有路线的成本决定命令。
- 将 700 像素内的连续枪声聚合成同一交战区；每个交战区枪声最多 4 人、脚步最多 2 人响应，每名 AI 同一规划周期只响应一个交战区。
- 优先保留路线锚点，避免一处枪声清空整条防线。

### `src/main/java/cs2d/server/TeamTacticalRuntime.java`

- 新增 180 行。
- 以 4Hz 低频构建团队战术快照，60Hz AI 微操和服务器循环频率不变。
- 汇总存活独立 AI、地图路线、3 秒声音记忆、20 秒友军死亡、30 秒死亡掉枪热度和敌方火力。
- 按 CT/T 分别规划并以不可变映射发布命令。

### `src/main/java/cs2d/AIControl/A/PresetPathModule.java`

- 新增 18 行，删除 1 行。
- 在保持旧取点入口兼容的同时，返回稳定的路线索引和关键点，使战术层能够统计真实地图路线占用。

### `src/main/java/cs2d/AIControl/A/PathfindingModule.java`

- 新增 20 行，删除 1 行。
- 保存 AI 本次生命被分配的地图路线 ID 和防御性复制的关键点。
- 局部接敌取消当前路线后仍保留路线身份用于团队统计；死亡重置时清理。

### `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java`

- 新增 61 行，删除 13 行。
- 新增兼容重载，旧调用不传战术命令时维持原声音响应策略。
- 有有效命令时，仅处理指挥官授权的远程枪声/脚步目标；视觉接敌始终不受限制。
- 将通用移动目标翻译给现有寻路模块；到达支援距离后停留，不把战术层耦合到按键、A* 或状态机细节。

### `src/main/java/cs2d/server/AIService.java`

- 新增 23 行，删除 1 行。
- 默认注入自适应协调器，并提供可注入其他实现的构造器。
- 仅在团队死斗启用团队指挥；普通死亡竞赛、僵尸和其他模式行为不变。
- 冻结或模式切换时清理旧命令。

### `src/test/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinatorTest.java`

- 新增 149 行。
- 覆盖单人无需固定小队、两人保持补枪距离、25 人枪声最多 4 人响应、25 个同区声源仍只形成一个 4 人响应任务、高风险路线抽调非锚点以及孤立 AI 回归队友。

## 行为变化

- 路线均衡不再拥有最终决定权：人数少时优先结伴，人数足够时才多线控制。
- 远处声音只产生有限的团队增援任务；未被授权的 AI 会继续原地图路线。
- 同一区域多个敌人同时开枪不会按敌人数重复叠加响应名额。
- 某条路线近期友军死亡和死亡掉枪越密集，路线风险越高；系统保留锚点并允许非锚点改走较安全路线。
- 深度孤立且没有眼前敌人的 AI 会向最近队友回收，恢复可补枪距离。

## 保持不变

- 服务器 60Hz、Sub-tick 输入、客户端刷新率、FOV、射线数量和画质没有改变。
- 地图预设路线几何、A* 精度、枪声听力范围、视觉感知精度没有降低。
- 射击、掩体、换弹、投雷、主动避让和玩家控制 BOT 的逻辑没有重写。
- 客户端项目和网络协议没有修改。

## 验证

- 聚焦测试：`AdaptiveTeamTacticalCoordinatorTest`、`TeamSoundResponsePolicyTest`、`PresetRouteAllocatorTest` 全部通过。
- `mvn clean test`：通过，45/45，0 失败、0 错误、0 跳过。
- `git diff --check`：通过；仅提示仓库既有的 LF/CRLF 自动转换策略。

## 手工验证建议

- 2 名 AI：两者相距过远时，应由一人向另一人靠拢，而不是强行走地图两端。
- 25～50 名 AI：同一交战区大量敌人开枪时，应只有少量增援，其他路线仍保留 AI。
- 连续死亡路线：应保留至少一个锚点，部分非锚点改走较低风险路线，不再流水线补人。
- 视觉接敌：任何 AI 真正看见近敌时仍立即交战，这是高于团队命令的本地生存优先级。
- 回退到修改前状态可使用基线提交 `57cf2fc643b64f3caffcef263ed50c0304bfe879`；未跟踪的 `.agent/changeHistory/2026-08-31-revert-bot-tactics.md` 不属于本次修改，不应删除。
