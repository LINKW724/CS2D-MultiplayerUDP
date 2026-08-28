# 输入与状态上下文回归修复记录

- 日期：2026-08-28
- 目标：恢复 E 键交互、数字键武器切换及小包之间的游戏上下文。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前代码基线：`f3108965b8a37676b979a57ede1cb8131fe4d580`
- 实现状态：改动保留在工作区，尚未提交。
- 用户已有改动：`cs2d_settings.json` 保持原样，未纳入本次修改。

## 文件变化

- `src/main/java/cs2d/client/GameClient.java`：新增 34 行，删除 13 行。
  - 小状态包保留自身新值，并从最新完整包补回缺失的 `mode`、`roundPhase`、炸弹状态等上下文。
  - 槽位消息改为发送 JSON 整数，不再发送字符串数字。
  - 修正数字 2 的本地预测槽位由 1 改为 2。
  - 补齐数字 3 切换刀槽位。
  - 数字 4 的投掷物循环改用整数槽位消息。
- `src/test/java/cs2d/client/GameClientProtocolTest.java`：新增 16 行，删除 0 行。
  - 增加小包继承完整状态且不覆盖动态新值的回归测试。
- `.agent/changeHistory/2026-08-28-input-regression-fix.md`：新增 28 行，删除 0 行。

## 用户可见行为

- 爆破模式按 E 时能持续识别 `DEMOLITION` 上下文并发送开始/停止交互。
- 数字 1、2、3、4 使用与服务端一致的整数槽位协议。
- HUD、购买菜单和炸弹信息不再因高频小包缺少全局字段而周期性失去上下文。

## 验证

- `mvn "-Dmaven.repo.local=C:/Users/小麦/.m2/repository" -q test`：通过。
- `git diff --check`：通过；仅有 Git 的 LF/CRLF 提示，无空白错误。

## 回退与限制

- 代码可回退到基线提交 `f310896`；回退时注意保留用户自己的 `cs2d_settings.json`。
- 仍需启动双端手工验证 E、2、3、4 以及 HUD/购买菜单行为。
