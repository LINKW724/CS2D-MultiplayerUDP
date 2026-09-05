# 解耦 AI 思考与移动执行

- 日期：2026-09-01
- 目标：消除寻路、队友避让和脱困同时抢夺移动输入造成的回头、抵消和通道堆积。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`6507cb1972ab78cdb2bbaf1a90df7cd457ad14f5`

## 根因

- A* 寻路按键与队友排斥按键通过 `HashSet` 直接合并，可能输出 `W+S` 或 `A+D`，导致同一帧的两股移动力互相抵消。
- 脱困逻辑会清空战略寻路目标、设置随机临时路径；0.8 秒后再次清空目标。团队命令随后重新设置原路线，造成“离开路线后又被拉回”的往返。
- 思考模块直接参与最终按键拼装，没有独立的行动决策边界。
- 第一版仲裁只能消除同一帧的 `A+D`，主动避让仍然每帧重新读取排斥向量。拥堵时相对位置跨过中心线会形成连续帧 `A/D/A/D`，强制脱困的随机方向也会放大这种周期摆动。

## 架构变化

移动数据流调整为：

`战斗/手雷/寻路/避让/脱困 → MovementIntent → MovementArbiter → MovementDecision → AIInput`

- 意图生产者不执行按键。
- 仲裁器只选择一个基础移动目标。
- 局部转向只能补充空闲轴或强化同方向，不能反向覆盖基础寻路。
- 高优先级独占动作由通用优先级规则接管，仲裁器无需引用具体 AI 模块，后续可通过新增意图扩展。
- 局部避让由独立的有状态规划器完成：先按稳定 ID 决定路权，再锁定让行方向；执行层不再逐帧自行改变主意。

## 文件变更

- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java`：新增 104 行，删除 113 行。
  - 删除 `HashSet` 直接合并寻路和避让按键的实现。
  - 收集不可变移动意图后只执行一次最终决策。
  - 脱困改为临时侧移提案，不再清空或替换原有战略路线。
  - 游戏专用代码只采集附近队友、排斥向量和路权事实；不再直接把瞬时向量转换成移动键。
- `src/main/java/cs2d/AIControl/movement/MovementIntent.java`：新增 58 行，删除 0 行。
  - 新增与具体 AI 模块无关的不可变移动意图。
  - 支持基础移动、局部转向和独占动作三种组合方式。
- `src/main/java/cs2d/AIControl/movement/MovementDecision.java`：新增 20 行，删除 0 行。
  - 新增最终移动决策及冲突抑制统计。
- `src/main/java/cs2d/AIControl/movement/MovementArbiter.java`：新增 137 行，删除 0 行。
  - 新增纯移动仲裁器，保证最终每个轴至多保留一个方向。
  - 支持任意新增意图来源，无需修改仲裁器实现。
- `src/main/java/cs2d/AIControl/movement/LocalAvoidancePlanner.java`：新增 171 行，删除 0 行。
  - 新增每个 AI 独立持有的局部避让记忆。
  - 转向至少保持 600ms；保持期结束后，反向信号还需持续 250ms 才允许换边。
  - 以稳定 ID 确定路权，避免相遇双方同时躲向相反方向；脱困重试也在短时间内保持同一侧，弱近邻信号不会触发无意义侧移。
- `src/test/java/cs2d/AIControl/movement/MovementArbiterTest.java`：新增 63 行，删除 0 行。
  - 覆盖相反方向抑制、垂直避让、独占优先级、异常生产者和无基础意图五类场景。
- `src/test/java/cs2d/AIControl/movement/LocalAvoidancePlannerTest.java`：新增 96 行，删除 0 行。
  - 覆盖逐帧反向抖动、持续反向确认、路权、垂直路线避让、重置及脱困方向记忆。
- `.agent/changeHistory/2026-09-01-decoupled-ai-movement.md`：新增 73 行，删除 0 行；记录本次完整审计信息。

## 用户可见行为

- 最终动作不再可能同时包含 `W+S` 或 `A+D`。
- 遇到队友时，避让可以侧移，但不能把 AI 沿路线的前进方向反转。
- 脱困完成后继续原战术路线，不再切到巡逻并重新寻找原路线。
- 战斗移动、手雷移动和脱困仍保持原有优先级，但都经过统一决策边界。
- 拥堵时不会因排斥向量轻微翻转而每帧左右换向；拥有路权的 AI 继续走，负责让行的 AI 会稳定地从一侧绕过。

## 验证

- `mvn -q -Dtest=MovementArbiterTest,LocalAvoidancePlannerTest test`：12 项通过。
- `mvn -q test`：81 项测试通过，0 失败，0 错误，0 跳过。
- `git diff --check`：通过（仅有仓库既有的 LF/CRLF 行尾提示）。

## 范围与限制

- 用户正在编辑的 `maps/d3_2.5x无粗.json` 未纳入本次修改或快照。
- 用户已有的 `.agent/changeHistory/2026-08-31-revert-bot-tactics.md` 保持未跟踪且未修改。
- 本次解决行动层互相拉扯和脱困破坏路线；团队指挥官仍可基于明确战术变化重新分配路线，这属于上层决策而非按键冲突。
- 需要重启服务端加载新类。
- 本次实现修改仍保留在工作区，尚未提交。
