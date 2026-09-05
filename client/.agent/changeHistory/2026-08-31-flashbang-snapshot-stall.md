# 闪光弹周期性卡顿修复

- 日期：2026-08-31
- 目标：消除大地图、多人战斗中被闪光弹命中时出现的 3～4 秒客户端冻结。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修复前基线：`eae3061f`（`chore: snapshot before flashbang snapshot fix`）

## 文件变更

### `src/main/java/cs2d/client/GameClient.java`

- 行数：新增 11 行，删除 8 行。
- 将闪光残影来源从整个 `gameRenderNode` 改回固定 `1600×900` 动态 Canvas。
- 为快照设置明确的屏幕视口和透明背景，避免静态地图 Atlas 参与同步合成与 GPU 回读。
- 复用单个 `WritableImage` 缓冲，避免每次闪光重新分配屏幕尺寸图片。
- 删除未配置任何颜色参数的恒等 `ColorAdjust`，避免闪光残影每帧进入额外效果合成路径。
- 保留闪光白屏、残影渐隐、持续时间和声音行为；未修改渲染 Hz、FOV 射线、精度或画质参数。

### `.agent/changeHistory/2026-08-31-flashbang-snapshot-stall.md`

- 行数：新增 46 行，删除 0 行；记录本次修复、验证和回退信息。

## 替换的旧行为

- 旧行为：收到闪光事件时，在 JavaFX 主线程同步执行 `gameRenderNode.snapshot()`；启用全分辨率地图 Atlas 后会强制 Prism 合成和回读整套渲染节点，造成数秒冻结。
- 新行为：只截取屏幕尺寸的动态 Canvas；静态地图继续由底层常驻缓存显示，不参与闪光残影快照。

## API、协议与配置

- 未改变网络协议、公共 API、存档结构、依赖或启动参数。
- `cs2d_settings.json` 是修复前已有的用户本地改动，本次未修改、未纳入基线提交。

## 验证

- `mvn test`：成功。
- 测试结果：33 个测试全部通过，0 失败，0 错误，0 跳过。
- `git diff --check`：通过，仅显示现有 LF/CRLF 转换提示，无空白错误。
- 最终差异核对：实现只涉及 `GameClient.java` 的闪光快照与残影绘制路径，以及本记录文件。

## 限制与手动验证

- JavaFX 快照行为需要在真实窗口和显卡管线中验证，自动化测试无法模拟 Prism 的 GPU 回读停顿。
- 重新启动客户端，在 50 人大地图中连续承受多次闪光弹；性能日志中不应再出现 HUD 绘制约 3～4 秒、最大帧时间约 3000～4000ms 的周期波峰。
- 实现修复当前保留在工作区，尚未创建最终实现提交。
- 如需回退实现，可从基线 `eae3061f` 对比恢复本次两个文件；基线之前的 60Hz/Sub-tick 工作不会丢失。
