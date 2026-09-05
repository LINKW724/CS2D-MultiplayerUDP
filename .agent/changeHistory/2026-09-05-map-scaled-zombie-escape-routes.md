# 按地图尺度扩展僵尸逃生路线

- 日期：2026-09-05
- 仓库：`O:/java/games/CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`20d1c31f0c39384f7016ce8a7b4d6102d92bd409`
- 目标：修复大地图仍使用固定 576 像素局部搜索、AI 看不到远处开放区域而继续钻角落的问题。
- 状态：本轮实现保留在工作区，尚未提交。

## 基线处理

- 开工前将上一轮已通过完整测试的入口纵深防线与多路线逃生提交为 `20d1c31`（`feat: add zombie defense corridors and escape routes`）。
- 本轮只调整服务器僵尸模式逃生搜索配置、评分和测试，不修改客户端、地图数据、网络协议或其他模式控制器。

## 逐文件变更

- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java`（+5/-1）：按当前地图宽高构建一次逃生搜索配置，并传给路线规划器；每个 AI 不再使用固定范围。
- `src/main/java/cs2d/AIControl/zombie/ZombieEscapeRoutePlanner.java`（+117/-8）：新增 `SearchProfile`；规划半径按地图对角线的 28% 计算，从 576 像素平滑增长并限制在 1280 像素；拓扑检测半径随搜索深度增长；额外探索拓扑光环；用连通单元密度识别长走廊、口袋和伪开放终点；加强地图边缘惩罚和安全队友距离奖励；备用路线优先从不同方向离开，单出口时才允许共享前段。
- `src/test/java/cs2d/AIControl/zombie/ZombieEscapeRoutePlannerTest.java`（+21/-0）：验证小/大/超大地图的搜索尺度、最大预算，以及旧 576 像素范围外才有出口的长走廊能够连接到开放区域。
- `.agent/changeHistory/2026-09-05-map-scaled-zombie-escape-routes.md`（新增 40 行）：本记录。

## 行为与接口

- 小地图保持原来的 576 像素最低规划范围，避免因地图较小降低既有逃生能力。
- 大地图最大规划半径为 1280 像素，防止超大地图导致单次搜索无限增长。
- 地图大小只决定预算；最终路线仍必须通过可行走、危险区、僵尸截击、队友间距和局部连通区域验证。
- `ZombieEscapeRoutePlanner.plan(...)` 保留原签名并使用默认配置；新增带 `SearchProfile` 的重载，兼容现有调用和测试。
- 未新增依赖、配置、迁移或协议字段。

## 验证

- `mvn -q -Dtest=ZombieEscapePlannerTest,ZombieEscapeRoutePlannerTest test`：通过。
- `mvn -q test`：服务器完整测试通过；既有夹具仍输出 `GameState is null` 和预设路线目录信息，不是测试失败。
- `git diff --check`：通过；仅有仓库既有 LF/CRLF 转换提示。

## 限制、部署与回退

- 1280 像素是性能保护上限，而不是整张地图全局寻路；路线会优先连接到宽阔连通区，再由现有 A* 分段执行。
- 需要重新构建并重启服务器后生效；建议在大地图中心、长走廊、U 型房间和地图边缘分别观察 AI 的路线选择。
- 回退以 `20d1c31f0c39384f7016ce8a7b4d6102d92bd409` 为准；本轮实现尚未提交。
