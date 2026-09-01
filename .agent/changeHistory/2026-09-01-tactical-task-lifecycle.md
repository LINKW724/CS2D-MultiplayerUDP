# 第二阶段：弹性战术任务与生命周期

- 日期：2026-09-01
- 目标：在不固定编组、不耦合 `GameState`/`Player`/具体AI模块的前提下，为团队指挥增加显式任务、人数上下限、优先级和生命周期，并保持旧协调器兼容。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`5dd3d794666cec0d3e89a9c9225c2784e393440f`
- 实现状态：第一、第二阶段都保留在工作区，尚未提交。

## 架构变化

- 新增纯领域对象 `TacticalTask` 和 `TacticalPlan`；它们只使用不可变快照和值对象，不引用服务端世界对象。
- `TacticalCoordinator` 保留原唯一抽象方法 `coordinate(...)`，新增兼容默认方法 `plan(...)`。旧实现、lambda和测试替身无需修改；新实现可以选择输出显式任务。
- 新增独立 `TacticalTaskBoard`，统一负责最少人数、最多人数、任务优先级、激活、完成、失败、过期和取消。
- `TeamTacticalRuntime` 只负责把纯计划交给任务板并发布有效订单，不把任务状态逻辑塞回单体AI。
- 自适应协调器按路线、支援对象或战斗区域生成稳定任务ID；同一枪声区域的多个敌人仍归为一个响应任务。

## 文件变更

以下行数为第二阶段本身的增删量。对于第一阶段已经修改且仍未提交的文件，行数由当前基线累计 `git diff --numstat` 减去第一阶段记录得到；新文件按完整文件行数记录。

### `src/main/java/cs2d/AIControl/team/TacticalTask.java`

- 新增 49 行。
- 定义任务ID、类型、路线、目标点、最少/最多人数、优先级、风险、交战规则、到达半径、允许响应的声音目标和过期时间。
- 对集合和数值做防御性校验，保持领域对象不可变。

### `src/main/java/cs2d/AIControl/team/TacticalPlan.java`

- 新增 17 行。
- 将任务列表与每个AI的任务订单作为一次不可变计划发布。
- 提供 `ordersOnly(...)` 兼容旧协调器。

### `src/main/java/cs2d/AIControl/team/TacticalTaskBoard.java`

- 新增 198 行。
- 任务未达到最少人数时保持 `PROPOSED`，不向AI发布半成品战术。
- 超过最多人数时按稳定的AI ID顺序截断，避免同一任务无限吸人。
- 支持 `ACTIVE`、`COMPLETED`、`FAILED`、`EXPIRED`、`CANCELLED` 六种状态。
- `FLANK`、`SUPPORT`、`REGROUP` 在全部受派AI到达目标范围时自动完成。
- 已完成/失败任务在协调器刷新TTL时不会错误复活；任务消失后会取消，之后同ID的新任务可重新进入生命周期。
- 没有显式任务的旧协调器订单直接透传。

### `src/main/java/cs2d/AIControl/team/TacticalCoordinator.java`

- 新增 8 行，删除 0 行。
- 新增默认 `plan(...)` 扩展点，同时保留函数式接口和旧 `coordinate(...)` 合同。

### `src/main/java/cs2d/AIControl/team/TacticalOrder.java`

- 新增 18 行，删除 1 行。
- 订单新增可选 `taskId`，用于任务板关联。
- 保留原12参数构造器，旧调用者得到无任务ID的兼容订单。
- 新增 `withTaskId(...)` 便捷复制方法。

### `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`

- 第二阶段新增约124行、删除10行；相对于共同基线当前累计为新增144行、删除14行。
- 输出 `TacticalPlan`，并按路线、支援目标、集结目标或声音战斗区域合并任务。
- 为响应、包抄、支援、集结、控线和推进任务设置显式容量与优先级。
- 枪声响应任务最大4人；脚步响应沿用现有最多2人的限制；包抄、支援和集结任务最多2人。
- 每个订单都带稳定任务ID；原 `coordinate(...)` 仍返回相同订单视图。

### `src/main/java/cs2d/server/TeamTacticalRuntime.java`

- 第二阶段新增约8行、删除4行；相对于共同基线当前累计为新增31行、删除5行。
- 4Hz规划改为调用兼容 `plan(...)`，再由任务板筛选后发布。
- 把当前不可变团队快照交给任务板，用于有限移动任务的到达判定。
- 清场时同时清空任务生命周期状态。

### `src/test/java/cs2d/AIControl/team/TacticalTaskBoardTest.java`

- 新增 109 行。
- 覆盖最少人数门槛、最多人数截断、过期、显式完成、到达自动完成和旧协调器透传。

### `src/test/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinatorTest.java`

- 第二阶段新增 29 行，删除 0 行；相对于共同基线当前累计新增60行。
- 验证25个同区域敌方声音只生成一个最多4人的响应任务。
- 验证自适应协调器的每个订单都有对应的显式有界任务。

## 用户可见行为

- 人数不足的复合战术不会以残缺状态下发；当前单人可执行任务仍可正常工作。
- 一个战斗区域不会因敌人或枪声数量多而吸走全队，响应任务严格受最大人数约束。
- 到达包抄、支援或集结目标后任务会结束，不会持续强拉移动。
- 旧自定义协调器和原订单构造代码继续工作，不强制迁移。

## 保持不变

- 服务器60Hz、Sub-tick输入、4Hz团队规划、AI微操和客户端代码未修改。
- 没有固定3～5人小队；任务关系仍是临时、按人数弹性建立。
- 原有路线、枪声本地判断、掩体、射击、投雷和寻路模块没有被任务板直接依赖。

## 验证

- 聚焦测试：`AdaptiveTeamTacticalCoordinatorTest,TacticalTaskBoardTest` 通过。
- `mvn -q test`：通过，57/57，0失败、0错误、0跳过。
- `git diff --check`：通过；仅提示仓库既有LF/CRLF自动转换策略。
- 最终状态审查确认未修改客户端，未覆盖无关的回退记录。

## 已知后续范围

- 当前自适应协调器生成的现有任务均可由1人执行，因此 `minimumAgents` 当前为1；钳形、佯攻同步、波次进攻等后续复合战术可直接声明2人或更多，不需要修改任务板。
- `FAILED` 提供了明确入口，但自动失败判断尚未接入伤亡、路线封锁或超时原因；过期和任务撤销已经自动处理。
- 当前任务状态仅保存在服务端内存中，尚未加入管理界面或诊断日志。

## 重启、验证与回退

- 需要重启服务端加载当前工作区代码；客户端无需更新。
- 建议先用2人、3人和25～50人三档测试：确认少人数互相支援、大人数枪声响应不超过4人、包抄到点后解除强制移动。
- 回退到修改前状态可使用基线提交 `5dd3d794666cec0d3e89a9c9225c2784e393440f`。
- 当前实现未提交；用户确认实战表现后再提交。
- 未跟踪的 `.agent/changeHistory/2026-08-31-revert-bot-tactics.md` 不属于本次修改，未删除、未覆盖、未暂存。
