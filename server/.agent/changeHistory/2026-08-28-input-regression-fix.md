# 输入回归修复记录

- 日期：2026-08-28
- 目标：修复严格协议校验导致的武器槽位切换失败，并兼容尚未升级的客户端。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`1dcc94c005a096cb7a16b265b735ecbe2f68a3fe`
- 实现状态：改动保留在工作区，尚未提交。

## 文件变化

- `src/main/java/cs2d/server/GameServer.java`：新增 30 行，删除 1 行。
  - `switchToSlot` 使用迁移期整数解析器。
  - 接受标准 JSON 整数及旧客户端的纯整数字符串。
  - 继续拒绝小数、指数形式字符串、空值、非数字和越界槽位，未取消协议安全校验。
- `src/test/java/cs2d/server/NetworkProtocolTest.java`：新增 16 行，删除 0 行。
  - 增加数字槽位、旧字符串槽位及非法小数的回归测试。
- `.agent/changeHistory/2026-08-28-input-regression-fix.md`：新增 25 行，删除 0 行。

## 用户可见行为

- 新客户端发送数字槽位后可正常切换武器。
- 旧客户端仍发送 `"slot":"2"` 时不会被记为非法包。
- 非整数及越界槽位仍会被拒绝。

## 验证

- `mvn "-Dmaven.repo.local=C:/Users/小麦/.m2/repository" -q test`：通过。
- `git diff --check`：通过；仅有 Git 的 LF/CRLF 提示，无空白错误。

## 回退与限制

- 可回退到基线提交 `1dcc94c`。
- 仍需启动服务端和客户端，手工验证真实 UDP 环境下的数字键切换。
