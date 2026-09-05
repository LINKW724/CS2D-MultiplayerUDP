# 高优先级网络与接口修复记录

- 日期：2026-08-28
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 修改前 HEAD：`0d57f1f`
- 分支：`main`
- 范围：UDP 握手可靠性、坏包隔离、分片完整性、RL HTTP 暴露面

## 修改原因

首次欢迎包或地图包丢失后客户端无法恢复；已连接客户端可用缺字段 JSON 触发运行时异常并结束接收线程；分片没有完整性校验；RL HTTP 服务默认监听且无访问控制。

## 文件级变更

### `src/main/java/cs2d/server/GameServer.java`（新增 172 行，删除 27 行）

- 将欢迎信息和静态地图发送拆成可重复调用的方法。
- 已登记地址再次发送 `SPECTATOR` 握手时重发欢迎信息和地图。
- 实现 `welcome_ack` 与 `request_static_data` 协议分支。
- 对字符串、整数、有限浮点数、布尔值、对象和输入按键数组做字段与范围验证。
- 在单包边界隔离 JSON/运行时异常；连续 10 个非法包后断开已连接客户端。
- 非法的首次加入请求在分配玩家 ID 前完成校验，避免残留幽灵连接。
- RL Bridge 仅在训练模式或显式 `cs2d.rl.enabled=true` 时创建，默认不启动。

### `src/main/java/cs2d/server/NetworkBroadcaster.java`（新增 7 行，删除 8 行）

- 对 GZIP 后的完整载荷计算 CRC32，并在每个分片写入相同 `checksum`。
- 压缩失败时停止发送该组分片，避免客户端将未压缩数据误当 GZIP。

### `src/main/java/cs2d/server/rl/RLBridgeService.java`（新增 85 行，删除 14 行）

- 默认绑定地址由调用方限定为 `127.0.0.1`。
- 非回环监听必须配置令牌；支持 Bearer Token 和 `X-CS2D-RL-Token`。
- `/step` 仅允许 POST，`/map_info` 仅允许 GET。
- 请求体限制为 1 MiB，超限返回 413；统一 UTF-8 JSON 响应。
- 保存并关闭 HTTP 执行器，避免停服后线程残留。

### `.agent/changeHistory/2026-08-28-high-priority-network-fixes.md`（新增 67 行，删除 0 行）

- 记录本次服务端改动、验证结果和回退基线，不影响运行时行为。

## 行为变化

- 丢失首次 `initialInfo` 后，后续握手会再次收到相同玩家 ID 和地图。
- 丢失地图或地图分片后，客户端的 `request_static_data` 可触发完整重发。
- 缺字段、错误类型、非有限数值和超长数组只会丢弃当前包，不再结束接收线程。
- 分片具备端到端 CRC 校验。
- 普通模式不再开放 8081；训练模式默认只监听本机。

## 配置说明

- 显式启用 RL：`-Dcs2d.rl.enabled=true`
- 监听地址：`-Dcs2d.rl.host=127.0.0.1`
- 端口：`-Dcs2d.rl.port=8081`
- 远程令牌：`-Dcs2d.rl.token=...` 或环境变量 `CS2D_RL_TOKEN`

## 验证

- `mvn -s C:\Users\小麦\.m2\settings.xml test`：BUILD SUCCESS（项目当前没有 JUnit 测试源）。
- 服务端生成 119 个真实 GZIP/CRC 分片，客户端重组器乱序恢复原文成功。
- 使用错误 CRC 的相同分片被客户端拒绝；负索引被拒绝。
- `git diff --check` 无新增空白错误；既有行尾转换提示未改变业务内容。

## 回退

- 修改前可回退点：`0d57f1f`
- 本次改动将作为独立提交保存；回退时优先使用 `git revert <本次提交>`。
