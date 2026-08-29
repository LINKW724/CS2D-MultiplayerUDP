# 比赛结束后冻结 AI

- 日期：2026-08-28
- 目标：比赛胜负确定后停止 BOT 决策、移动和异步投掷，避免结算后继续行动与刷日志。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`119cad653fe6cbdae4839d8979be687c032d9fc5`
- 实现状态：已提交，作为子弹穿透修复前的可回退基线。

## 文件变更

- `src/main/java/cs2d/server/GameState.java`（+41/-13）：集中建立比赛终态；清除角色输入、交互、速度和待处理 AI 丢枪请求；TDM 达到分数上限立即结算；新增统一 AI 冻结判定。
- `src/main/java/cs2d/server/AIService.java`（+42/-14）：回合或比赛结束时只发布中性输入；一次性撤销控制器动作；异步决策提交前后都检查终态，旧决策不能覆盖冻结状态。
- `src/main/java/cs2d/AIControl/A/GrenadeModule.java`（+27/-13）：新增计算代次；取消后旧后台结果失效；冻结状态拒绝新投掷；成功与错误日志仅对仍有效的任务输出。
- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java`（+17/-4）：新增统一取消目标、寻路、攻击和手雷任务入口；投掷请求被真正接受后才输出决定日志。
- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java`（+19/-4）：重置和终态均取消幸存者异步手雷任务，并清空控制器动作；拒绝的投掷不再输出决定日志。
- `src/test/java/cs2d/server/NetworkProtocolTest.java`（+8/-0）：覆盖手动冻结、回合结束、比赛结束与正常进行四种冻结判定。
- `.agent/changeHistory/2026-08-28-freeze-ai-after-match.md`（+37/-0）：本次变更审计记录。

## 行为变化

- TDM 分数上限触发后，不再等待五秒才向客户端发布整场结束状态。
- 比赛结束后角色不会继续移动、射击或交互。
- 回合结束或比赛结束后，BOT 不再生成新决策；已在计算的手雷轨迹即使完成也会被丢弃。
- 没有新增网络协议、依赖、配置或数据迁移。

## 验证

- `mvn test`：通过；8 项测试，0 失败、0 错误。
- `git diff --check`：通过；仅提示现有换行符将在 Git 后续写入时转换为 CRLF。
- 双端 Maven 编译均成功。

## 回退与限制

- 可回退到基线提交 `119cad653fe6cbdae4839d8979be687c032d9fc5`。
- 自动化测试覆盖终态判定；异步 BOT 取消与最终游戏画面仍建议实际开一局达到分数上限后人工确认。
- 运行中的服务器需要重启后才会加载本次 Java 修改。
