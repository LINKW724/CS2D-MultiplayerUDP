# AI 日志洪水控制

- 日期：2026-08-31
- 目标：消除50人战斗中的AI逐事件日志洪水，保留可观测性且不改变AI状态机和游戏行为。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`1957e13f`（`feat: add 60hz subtick server simulation`）
- 实现状态：本轮日志降噪改动保留在工作区，尚未创建最终提交。

## 文件级变更

- `src/main/java/cs2d/server/AiDiagnostics.java`：+68/-0；新增线程安全的AI日志门控、10秒分类汇总、详细日志开关和可配置汇总周期。
- `src/main/java/cs2d/server/AIService.java`：+4/-1；感知审计改为默认计数汇总。
- `src/main/java/cs2d/AIControl/A/GrenadeModule.java`：+12/-7；投掷超时、计划完成、实际投掷和后台计算成功改为汇总；真实计算异常仍逐条输出。
- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java`：+13/-6；预设路径取消、投掷决定/拒绝和卡住避让改为汇总。
- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java`：+5/-2；生存者投掷决定改为汇总。
- `src/main/java/cs2d/server/GameState.java`：+3/-3；爆炸遮挡和无信息量的切枪分隔日志接入汇总。
- `src/test/java/cs2d/server/AiDiagnosticsTest.java`：+17/-0；验证汇总内容稳定、紧凑并按分类排序。
- `.agent/changeHistory/2026-08-31-ai-log-flood-control.md`：+41/-0；本记录。

## 行为与配置

- 默认每10秒最多输出一条：`[AI日志汇总/10.0s] category=count | ...`。
- `-Dcs2d.ai.verbose=true`：恢复所有被门控的逐条AI诊断日志。
- `-Dcs2d.ai.logSummary=false`：完全关闭被抑制日志的周期汇总。
- `-Dcs2d.ai.logSummaryMs=30000`：自定义汇总周期，最小1000ms。
- 异步投掷计算异常、无效装备、地图/寻路错误等真实异常没有被隐藏。
- 未修改感知、目标选择、投掷冷却、投掷超时、避让、伤害或武器切换逻辑。

## 验证

- `mvn -q test`：通过，28项测试，0失败，0错误。
- 高频文本绕过扫描：仅匹配到已注释的旧版 `AIController` 代码，没有活动的直接输出。
- `git diff --check`：通过；只有LF/CRLF工作区提示。

## 回退与人工验证

- 日志降噪前恢复点：`1957e13f`。
- 重启服务端后生效；客户端无需修改或重启协议版本。
- 建议用50人地图运行至少30秒，正常应看到约3条AI汇总而不是数百条逐事件日志。
- 若需要诊断某个BOT投掷问题，临时加入 `-Dcs2d.ai.verbose=true` 后重启服务端。
