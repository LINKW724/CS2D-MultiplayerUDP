# 第一阶段：共享不可变地图路线目录

- 日期：2026-09-01
- 目标：让寻路与团队指挥共享一次加载的完整地图路线目录，使指挥官能够分析当前无人占用的路线，同时保持旧预设寻路接口兼容。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP`
- 分支：`main`
- 修改前基线：`5dd3d794666cec0d3e89a9c9225c2784e393440f`
- 实现状态：保留在工作区，尚未提交。

## 架构变化

- 新增独立 `route` 领域包；不依赖 `GameState`、`Player`、AI控制器或JavaFX可视化器。
- `RouteCatalog` 是只读接口，服务端适配器可注入其他路线来源，满足开闭原则。
- `PresetRouteCatalogRepository` 负责每张地图只解析一次JSON，并向所有 `PresetPathModule` 和团队指挥共享同一个不可变目录实例。
- 原 `PresetPathModule` 保留原公开方法，改为兼容门面，不再直接解析JSON或维护可变Gson缓存。

## 文件变更

### `src/main/java/cs2d/AIControl/route/RoutePoint.java`

- 新增 12 行。
- 新增不可变世界坐标和值对象距离计算。

### `src/main/java/cs2d/AIControl/route/RouteType.java`

- 新增 33 行。
- 定义六类地图路线及其阵营、JSON字段和TDM/爆破模式属性。
- 路线领域不再依赖可视化器内部的 `PathType`。

### `src/main/java/cs2d/AIControl/route/RouteDescriptor.java`

- 新增 28 行。
- 定义稳定路线ID、原始索引、关键点、总长度、建议容量和重叠路线。
- 所有集合均防御性复制；当前建议容量为 `0`，表示地图尚未提供容量元数据。

### `src/main/java/cs2d/AIControl/route/RouteCatalog.java`

- 新增 20 行。
- 定义按地图、路线类型和稳定ID查询的只读目录接口。

### `src/main/java/cs2d/AIControl/route/PresetRouteCatalogRepository.java`

- 新增 269 行。
- 从 `_prePath.json` 一次性加载全部六类路线并进行进程级缓存。
- 预计算路线总长度和同阵营、同游戏类型路线之间的空间重叠。
- 优先使用项目相对目录 `maps/prePath`，保留旧绝对目录作为兼容回退。
- 新增可选系统属性 `cs2d.prePath.dir`，用于测试或自定义路线目录。
- 缺失文件不会缓存为空目录，允许工具稍后生成路线后再次加载。

### `src/main/java/cs2d/AIControl/A/PresetPathModule.java`

- 新增 36 行，删除 165 行。
- 删除直接Gson解析、静态可变POJO缓存、重复双重检查锁和硬编码文件读取。
- 改为使用共享 `RouteCatalog`，保留 `loadMap`、`getPresetKeyPoints`、`getPresetPathSelection` 和弃用兼容入口。
- 路线均衡分配行为保持不变，并保留JSON中的原始路线索引，即使前面存在空路线也不会发生ID错位。

### `src/main/java/cs2d/AIControl/team/TeamTacticalSnapshot.java`

- 新增 21 行，删除 0 行。
- 新增不可变 `RouteSnapshot`，包含本队全部路线，而不只是当前AI已占用路线。
- 保留原七参数构造器作为兼容入口。

### `src/main/java/cs2d/server/TeamTacticalRuntime.java`

- 新增 23 行，删除 1 行。
- 在服务端启动时取得共享目录，并按CT/T过滤本队TDM路线。
- 每次4Hz战术快照复用目录数据，不重新读取JSON。
- 保留可注入 `RouteCatalog` 的构造器，方便测试或替换数据源。

### `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`

- 新增 20 行，删除 4 行。
- 路线统计先建立目录中的全部路线，再叠加当前AI占用。
- 风险、拥堵和侧翼选择现在可以考虑无人路线。
- 无目录的旧调用仍从AI自身路线回退构建统计。

### `src/test/java/cs2d/AIControl/route/PresetRouteCatalogRepositoryTest.java`

- 新增 92 行。
- 覆盖六类JSON解析入口、稳定ID、路线长度、空间重叠、深层不可变、同实例缓存和缺失文件重新发现。

### `src/test/java/cs2d/AIControl/route/PresetPathModuleCatalogIntegrationTest.java`

- 新增 62 行。
- 覆盖多个旧寻路模块共享同一目录、均衡覆盖全部路线以及空路线前缀下的原始索引兼容。

### `src/test/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinatorTest.java`

- 新增 31 行，删除 0 行。
- 新增“安全路线当前无人占用，指挥官仍可选择”测试。

## 用户可见行为

- 有预设路线地图：AI原有出生路线分配与关键点执行方式不变。
- 团队指挥：能够看见并评估当前无人使用的路线，不再局限于AI已经抽到的路线。
- 无预设路线地图：目录为空，寻路继续回退普通A*。
- 地图路线只加载一次；创建大量AI不再重复持有或检查可变路线JSON对象。

## 保持不变

- 服务器60Hz、Sub-tick输入、AI微操频率和4Hz团队规划频率不变。
- 路线关键点坐标、路线均衡算法、A*精度、枪声响应、射击、投雷和客户端代码没有修改。
- 本阶段只完成路线目录；完整路线切换执行、显式弹性任务池、路径时间估计和高级战术仍属于后续阶段。

## 验证

- 聚焦测试：`PresetRouteCatalogRepositoryTest`、`PresetPathModuleCatalogIntegrationTest`、`PresetRouteAllocatorTest`、`AdaptiveTeamTacticalCoordinatorTest` 全部通过。
- `mvn clean test`：通过，51/51，0失败、0错误、0跳过。
- `git diff --check`：通过；仅提示仓库既有LF/CRLF自动转换策略。

## 重启、验证与回退

- 需要重启服务端，使当前地图的共享路线目录在启动时加载。
- 启动日志应只出现一次 `PresetRouteCatalog: loaded map ...`，随后创建AI不应重复读取路线文件。
- 在带 `_prePath.json` 的地图上观察路线分配；原路线行走应保持一致，战术层可以把非锚点分配到此前无人路线。
- 回退到修改前状态可使用基线提交 `5dd3d794666cec0d3e89a9c9225c2784e393440f`。
- 未跟踪的 `.agent/changeHistory/2026-08-31-revert-bot-tactics.md` 不属于本次修改，未删除、未覆盖、未暂存。
