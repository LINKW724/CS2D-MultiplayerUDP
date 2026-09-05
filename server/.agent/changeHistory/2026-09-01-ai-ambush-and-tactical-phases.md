# AI 伏击待命与战术阶段收口

- 日期：2026-09-01
- 目标：让无任务 AI 原地伏击并保持静步，消除远处枪声、未结束任务和倒退路线造成的踌躇、回头与无意义脚步声。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`6507cb1972ab78cdb2bbaf1a90df7cd457ad14f5`

## 根因

- 没有团队任务时，控制器仍会进入默认巡逻并随机生成新目标，因此 AI 会在出生区或安全区域来回走动并发出脚步声。
- 即使没有战术任务，远处枪声仍可被个体 AI 直接转成追击目标，绕过团队指挥层。
- `ADVANCE`、`ASSEMBLE` 等有限任务没有在实际抵达后结束，AI 可能长期保留一个已经完成的命令。
- 侧翼到达后，关联的正面压制任务没有统一结束，压制组会继续停在阶段目标附近。
- 钳形战术可以在没有相关接触时启动；路线后半程的 AI 还可能收到位于自身进度后方的阶段目标。

## 架构变化

待命决策调整为：

`团队命令/目视威胁/路线执行状态 → TacticalIdlePolicy → 伏击或继续执行 → MovementArbiter → AIInput`

- `TacticalIdlePolicy` 是纯策略类，不依赖游戏实体、寻路器或输入实现。
- 团队层决定“该不该动”，寻路层只负责“怎么到达”，移动仲裁器仍只负责最终按键。
- 无任务不是一个隐含巡逻任务，而是明确的伏击状态。
- 声音情报不再由个体 AI 在无命令时自行升级成移动目标；是否增援由团队指挥层决定。

## 文件变更

- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java`：相对基线累计新增 117 行、删除 138 行；其中本阶段在上一份移动解耦记录基础上净新增 13 行、删除 25 行。
  - 接入无状态依赖的伏击决策策略。
  - 无可执行任务时清除残留路径并阻止默认巡逻生成随机目标。
  - 无战术命令时不再追逐仅由声音发现的目标；直接目视威胁仍可立即接管。
  - 伏击状态保留静步标记；如果规则要求短距离调整，也不会使用奔跑脚步。
  - 删除控制器内已失去用途的个体枪声响应人数统计辅助逻辑。
- `src/main/java/cs2d/AIControl/team/TacticalIdlePolicy.java`：新增 33 行、删除 0 行。
  - 新增独立待命策略，区分立即威胁、可执行命令、仍在执行的路线和真正无任务状态。
- `src/main/java/cs2d/AIControl/team/TacticalTaskBoard.java`：新增 32 行、删除 3 行。
  - `ASSEMBLE`、`ADVANCE`、`FLANK`、`SUPPORT`、`REGROUP` 在分配成员实际抵达后完成。
  - 同一钳形行动的全部侧翼成员抵达后，统一完成并释放关联的 `SUPPRESS` 命令。
- `src/main/java/cs2d/AIControl/team/ElasticManeuverRefiner.java`：新增 59 行、删除 4 行。
  - 只有基础指挥计划已确认相关接触时才允许细化成钳形战术。
  - 阶段目标按 AI 在预设路线上的真实进度前向选择，不再把路线后半程 AI 拉回 58% 等旧位置。
- `src/test/java/cs2d/AIControl/team/TacticalIdlePolicyTest.java`：新增 41 行、删除 0 行。
  - 覆盖无任务伏击、被动任务、路线继续执行、有效移动任务和立即威胁。
- `src/test/java/cs2d/AIControl/team/TacticalTaskBoardTest.java`：新增 56 行、删除 1 行。
  - 覆盖有限任务按实际到达完成，以及侧翼完成后释放压制组。
- `src/test/java/cs2d/AIControl/team/ElasticManeuverRefinerTest.java`：新增 53 行、删除 2 行。
  - 覆盖相关枪声触发、无枪声不触发、远处无关枪声不触发，以及阶段目标不沿路线倒退。
- `.agent/changeHistory/2026-09-01-ai-ambush-and-tactical-phases.md`：新增 72 行、删除 0 行；记录本阶段完整审计信息。

## 用户可见行为

- AI 暂时没有任务时会原地观察和伏击，不再随机巡逻、左右晃动或主动制造脚步声。
- 只有眼前可确认的敌人可以立即打断伏击；远处枪声只是团队情报，不能让单个 AI 擅自追过去。
- 有明确路线任务时 AI 会持续执行；任务实际完成后转入伏击，而不是在终点附近反复纠结。
- 钳形侧翼完成后正面压制组可以恢复重新分配，不会永久堆在阶段点。
- 战术重分配不会把已经走到路线后半段的 AI 拉回路线前半段。

## 验证

- `mvn -q -Dtest=TacticalIdlePolicyTest,TacticalTaskBoardTest,ElasticManeuverRefinerTest test`：聚焦测试通过。
- `mvn -q test`：88 项测试通过，0 失败，0 错误，0 跳过。
- `git diff --check`：通过（仅有 Git 的 LF/CRLF 行尾提示）。

## 范围与限制

- 用户正在编辑的 `maps/d3_2.5x无粗.json` 未修改、未暂存，也不会纳入本次提交。
- 用户已有的 `.agent/changeHistory/2026-08-31-revert-bot-tactics.md` 保持未跟踪且未修改。
- 伏击是行为层待命规则，不会替代团队指挥官；收到新的有效命令后 AI 仍会立即开始执行。
- 需要重启服务端加载新类，并在左上红方、右下蓝方出生区域观察无任务时是否保持静止。
- 本阶段及前一阶段实现修改仍保留在工作区，尚未提交。
