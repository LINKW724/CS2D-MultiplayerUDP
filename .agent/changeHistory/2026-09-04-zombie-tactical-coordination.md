# 僵尸模式独立指挥协调与安全射击

- 日期：2026-09-04
- 仓库：O:/java/games/CS2D-MultiplayerUDP
- 分支：main
- 基线：1c1f67b0e7e99d16819c8c5c481a9af8956d672d
- 目标：守点为主，局部优势时主动清理，消除跨阵营目标污染及边移动边停火冲突。
- 基线处理：修改前将上次未提交的少人 TDM 修复单独保存为基线提交；本轮僵尸模式实现未提交。
- 范围：仅服务器源代码、测试与本记录；未修改客户端或网络协议。

## 分层与行为

- ZombieTacticalRuntime → ZombieIntelBoard / ZombieTacticalSnapshot → ZombieThreatEvaluator / ZombieTacticalCoordinator → ZombieTacticalTaskBoard / ZombieTacticalOrder → ZOMBIEcontrol。
- 指挥周期 250ms，情报有效期 3 秒，订单有效期 1200ms。
- 清理最多派 2 名 AI；保留其余守点火力，唯一可开火的队友优先掩护附近换弹者。
- 清理以守点 650 范围和 8 秒任务窗口为限，归位后冷却 5 秒；换目标不会刷新总清理时限。
- 没有合法敌情时保持守点警戒，既不盲射，也不任意挑选活着的人类。
- 守点/清理/归位/局部调整均可正常射击；机枪、步枪、冲锋枪不再因该模式的长枪停稳/固定连射冷却冲突而停火。
- 射击前复核敌对关系与友军挡枪；友军挡住射界时尝试安全侧移。
- 贴身威胁会立即触发一次局部重算；普通局部移动 500ms、紧急调整 250ms，避免每帧重算采样。
- 保留幸存者武器池、僵尸近战和僵尸寻找活着人类的模式行为；僵尸的无视觉目标回退由每帧随机挑选改为最近合法人类。
- 删除旧的静态共享敌人表、全局单一撤退落点、无上限队友吸引分数及随机不可走巡逻目标。
- 投掷保留但只在安全距离且多目标时尝试；近敌、失去目标、换弹和超时可取消，异步计算期间继续普通防御。

## 逐文件变更

以下为相对基线的精确文本增删行数；新增文件使用 git diff --no-index --numstat NUL FILE 统计。

| 文件 | 新增 | 删除 | 内容 |
| --- | ---: | ---: | --- |
| `src/main/java/cs2d/AIControl/A/AttackModule.java` | 32 | 10 | 新增可选射击策略；默认调用保持原有 TDM 行为；僵尸模式合法目标检查、禁盲射、移动射击和按实际 lastShotTime 统计已发射进度。 |
| `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` | 205 | 1071 | 重构为执行器，移除跨阵营静态共享表、无限制抱团奖励和单点集体撤退；执行指挥订单，独立选敌/开火、安全局部移动和可中断投掷；保留武器池与近战。 |
| `src/main/java/cs2d/server/AIService.java` | 21 | 2 | 接入僵尸指挥运行时、冻结/停止/模式切换清理；RL 覆盖路径也使用僵尸射击策略与友军射线门禁。 |
| `src/main/java/cs2d/AIControl/A/AttackExecutionPolicy.java` | 24 | 0 | 隔离 STANDARD 与 ZOMBIE_SURVIVOR 的目标合法性、盲射、停稳和连射规则。 |
| `src/main/java/cs2d/AIControl/zombie/ZombieHostilityPolicy.java` | 38 | 0 | 统一人类/僵尸敌对关系，排除自己、同阵营、死亡目标，并阻止对友军遮挡射线开火。 |
| `src/main/java/cs2d/AIControl/zombie/ZombieIntelBoard.java` | 40 | 0 | 实例级、按阵营隔离的不可变情报；3 秒失效，变队/死亡/来源无效清理，拒绝旧报告覆盖新报告。 |
| `src/main/java/cs2d/AIControl/zombie/ZombiePositionPlanner.java` | 62 | 0 | 有界局部采样，检查整段可走性、僵尸穿越风险、队友间距与射界；无安全点时保持原位。 |
| `src/main/java/cs2d/AIControl/zombie/ZombieTacticalCoordinator.java` | 76 | 0 | 守点主力、局部优势清理小组、换弹掩护、紧急调整与归位；不因僵尸数量少就忽略血量和可用火力。 |
| `src/main/java/cs2d/AIControl/zombie/ZombieTacticalOrder.java` | 11 | 0 | 独立任务和移动目的地，任务轮次与有效期，分配确定性的警戒方向。 |
| `src/main/java/cs2d/AIControl/zombie/ZombieTacticalSnapshot.java` | 26 | 0 | 不可变己方状态与已知敌情快照，包含血量、弹药、换弹和武器火力。 |
| `src/main/java/cs2d/AIControl/zombie/ZombieTacticalTaskBoard.java` | 48 | 0 | 稳定守点位置、任务轮次和清理时限/冷却；换目标不重置出击总时限，死亡后释放旧任务。 |
| `src/main/java/cs2d/AIControl/zombie/ZombieThreatEvaluator.java` | 23 | 0 | 局部 900 范围的威胁/己方可用火力评估，区分优势清理与贴身危险。 |
| `src/main/java/cs2d/server/ZombieTacticalRuntime.java` | 88 | 0 | 服务器级 4 Hz 指挥；只接受实际视觉报告，不向幸存者指挥器暴露全图未发现的僵尸位置。 |
| `src/test/java/cs2d/AIControl/A/ZombieAttackExecutionTest.java` | 69 | 0 | 实际 AttackModule 回归：移动机枪远距离持续射击、默认 TDM 停稳要求不变、队友/无目标禁止射击。 |
| `src/test/java/cs2d/AIControl/zombie/ZombieHostilityPolicyTest.java` | 37 | 0 | 自己/同伴/死亡/感染变队和友军挡枪、僵尸不能用枪。 |
| `src/test/java/cs2d/AIControl/zombie/ZombieIntelBoardTest.java` | 53 | 0 | 双阵营情报隔离、过期、死亡、目标及观察者变队、清空、乱序报告。 |
| `src/test/java/cs2d/AIControl/zombie/ZombiePositionPlannerTest.java` | 36 | 0 | 跨墙落点、穿越僵尸、分散逃生、无安全位置和侧移解除友军遮挡。 |
| `src/test/java/cs2d/AIControl/zombie/ZombieTacticalCoordinatorTest.java` | 89 | 0 | 无敌守点、限量清理、威胁增多、缺弹/换弹、强敌、归位/重生和局部调整。 |
| `src/test/java/cs2d/AIControl/zombie/ZombieTacticalTaskBoardTest.java` | 27 | 0 | 换目标不重置超时，持续任务目的地更新不重开任务。 |
| `.agent/changeHistory/2026-09-04-zombie-tactical-coordination.md` | 81 | 0 | 本变更记录。 |

## API / 配置 / 兼容性

- 新增僵尸模式的情报、快照、订单、生命周期、威胁评估、站位规划和服务器运行时 API。
- AttackModule 保留原 update(owner-target, lastKnownPosition, now) 调用方式，默认 STANDARD；增加 AttackExecutionPolicy 参数重载。
- ZOMBIEcontrol 保留构造、update(world, now)、reset、cancelPendingActions、initializeWeaponChoice、onKill、onDeath；增加携带订单的 update 重载。
- 旧 ZOMBIEcontrol 内部 AIState 枚举随旧状态机移除；仓库内无外部引用。
- 无新依赖、持久化格式或网络协议变更，无客户端迁移。
- 调整阈值集中在僵尸专用模块；TDM 指挥器和默认射击策略未修改。

## 验证

- mvn -q -DskipTests compile：通过。
- mvn -q '-Dtest=Zombie*Test' test：通过首轮专用回归。
- mvn -q test：通过完整回归；中途新增视觉过滤时枚举包名编译错误已修正后重跑通过。
- mvn -q package：最终通过，178 tests / 0 failures / 0 errors / 0 skipped；其中 25 项新增僵尸回归测试。
- git diff --check：通过；仅有仓库既有 LF/CRLF 提示。
- 构建产物：target/cs2d-server.jar（被忽略的生成文件，未纳入 Git）。
- 测试中的 “GameState is null for AI: ct” 来自无游戏世界的 PathfindingModule 测试替身，并非实服错误。
- 环境：沙箱 helper 启动失败后使用审批通过的命令；Git 所有者差异用仅限本次命令的 safe.directory 路径处理，没有改全局配置。

## 限制与部署

- 尚未启动真实服务器进行多人对局验收；已验证模块逻辑、真实攻击模块输出及全项目构建。
- 守点取 AI 当前初始位置附近的可走分散站位，不包含人工标注地图据点的择点优化。
- 局部规划是有界安全采样，不是全地图动态僵尸路径搜索；无安全落点时保持原位开火，不强行穿越危险区。
- 不新增全队撤离行为；主策略为守点、局部清理和局部重新站位，符合本次确认范围。
- 友军射线保护针对决策时刻的位置，不修改物理引擎的友伤规则，也不承诺队友在子弹飞行中闯入弹道时绝对免伤。
- 替换服务器构建并重启后生效；没有擅自停止/重启正在运行的服务。
- 实机建议：无僵尸、单只弱僵尸、少量高血量僵尸、僵尸群、队友换弹、友军挡枪、感染变队、清理超时归位。
- 恢复方式：以基线 1c1f67b0e7e99d16819c8c5c481a9af8956d672d 为恢复点，只撤销本表列出的改动；不要整体丢弃后续用户修改。
