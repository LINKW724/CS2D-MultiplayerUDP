# 预设地图路线均衡分配

- 日期：2026-08-31
- 目标：地图包含预设路线时优先沿地图路线行动，并避免同一波 AI 随机集中到少数路线；无预设路线时保留普通 A* 回退。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`ea29819074de7ad2be0a5284eb5ee9d158338f8c`
- 实现状态：保留在工作区，尚未提交。

## 文件变更

### `src/main/java/cs2d/AIControl/A/PresetRouteAllocator.java`

- 新增 46 行。
- 新增线程安全的地图路线分配器。
- 按“地图名 + 路线类型 + 路线数量”隔离分配进度。
- 连续分配会先覆盖全部可用路线，再进入下一轮；并发出生时仍保持均衡。

### `src/main/java/cs2d/AIControl/A/PresetPathModule.java`

- 新增 18 行，删除 8 行。
- 使用均衡路线分配替代每个人机独立随机抽取。
- 新增 `getPresetKeyPoints(...)`；旧的 `getRandomPresetKeyPoints(...)` 保留为兼容入口并标记弃用。
- 对应路线不存在或为空时仍返回 `null`，由原寻路流程回退普通 A*。

### `src/main/java/cs2d/AIControl/A/PathfindingModule.java`

- 新增 1 行，删除 1 行。
- 接入新的均衡预设路线入口。
- 出生区触发、每条生命只使用一次、发现敌人后取消路线、分段 A* 等既有行为保持不变。

### `src/test/java/cs2d/AIControl/A/PresetRouteAllocatorTest.java`

- 新增 79 行。
- 覆盖 25 个 AI / 5 条路线的均匀分配。
- 覆盖不同地图和不同方向之间的状态隔离。
- 覆盖 1000 次并发分配和非法空路线数量。

## 行为变化

- 有地图预设路线：同一方向的 AI 按路线数量均衡轮转。例如 25 个 AI、5 条路线时，每条路线分配 5 个。
- 无对应预设路线：行为不变，继续使用普通 A*。
- 未添加关键点偏移、编队移动或新的战斗战术；不会改变现有路线几何、FOV、Tick、网络或画面设置。

## 验证

- `mvn -Dtest=PresetRouteAllocatorTest test`：通过，4/4。
- `mvn test`：通过，35/35，0 失败、0 错误、0 跳过。
- `git diff --check`：通过；仅提示仓库既有的 LF/CRLF 自动转换策略。

## 限制与手工验证

- 当前分配按累计轮转保持均衡，不跟踪某条路线上的实时存活人数；这避免引入路线释放、死亡竞态和战斗状态耦合。
- 需要重启服务端并在带 `_prePath.json` 的地图上观察一波大量 AI 出生。出生区和敌方出生区附近仍会因地图路线共享端点而短暂汇合，这是原地图路线数据的既有特性。
- 回退时可恢复基线提交，并移除本次新增的分配器、测试和变更记录；不要影响其他未跟踪文件。
