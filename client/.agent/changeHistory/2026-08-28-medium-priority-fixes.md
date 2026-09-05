# 中优先级顺序、线程与重连修复记录

- 日期：2026-08-28
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`6d24bc1c40bfcbf1245eba39398d802bc8142e18`
- 目标：修复 UDP 乱序与伪造来源、JavaFX 跨线程访问、客户端状态竞争和重连中断

## 文件级变更

### `pom.xml`（新增 11 行，删除 0 行）

- 新增 JUnit Jupiter 5.10.2 测试依赖。
- 使用 Maven Surefire 3.2.5 执行 JUnit 5 测试。

### `src/main/java/cs2d/client/GameClient.java`（新增 210 行，删除 116 行）

- 仅接收地址和端口都与当前 `serverAddress` 相同的 UDP 数据包。
- 跟踪协议版本、服务器会话和最后接受的状态序号；拒绝重复、倒退和其他会话的状态。
- 分片携带旧序号时可在重组前提前释放，重组完成后仍由内层状态序号再次校验。
- full/small 状态在提交后台更新前深拷贝；完整快照单独保存，小包基于完整快照补齐烟雾与火焰。
- `ClientPlayer` 和 `ClientGrenade` 使用 volatile 标量、同步更新和替换式 JSON 快照，不再原地修改共享 `JsonObject`。
- 本地击杀、伤害和分数预测通过同步复制更新，避免与状态线程同时改对象。
- 后台状态线程涉及购买菜单的读取/修改统一切换到 `Platform.runLater`。
- 重连失败后独立按 2、4、8 秒退避持续尝试，不再依赖仅在 PLAYING 生效的看门狗。
- 客户端所有游戏消息统一携带协议版本；欢迎完成后同时携带会话 ID。

### `src/test/java/cs2d/client/GameClientProtocolTest.java`（新增 95 行，删除 0 行）

- 验证旧序号、重复序号和其他会话状态被拒绝。
- 验证只接受配置服务器的 IP 与端口。
- 验证玩家动态更新发布新 JSON 快照，旧快照不会被原地修改。

### `.agent/changeHistory/2026-08-28-medium-priority-fixes.md`（新增 55 行，删除 0 行）

- 记录本轮客户端修改、验证、兼容性和回退说明。

## 行为和协议变化

- 客户端协议升级为版本 2，必须与本轮服务端一起更新并重启。
- UDP 乱序不会再让旧 full/small 或晚完成分片覆盖新状态。
- 非服务器来源无法再伪造欢迎、状态、分片或 pong。
- 重连期间会持续自动尝试，收到有效 `initialInfo` 后才停止。

## 验证

- `mvn -s C:\Users\小麦\.m2\settings.xml test`：BUILD SUCCESS。
- JUnit：3 项测试，0 失败，0 错误，0 跳过。
- `git diff --check`：无新增空白错误；仅有既有 LF/CRLF 转换提示。

## 回退

- 修改前客户端基线：`6d24bc1c40bfcbf1245eba39398d802bc8142e18`
- 本轮将以独立提交“修复中危险性bug”保存。
- 回退时优先使用 `git revert <本轮客户端提交>`，并让服务端回退到匹配协议版本。
