# 驻留静态地图图层 A/B 优化

- 日期：2026-08-29
- 任务目标：在不降低165Hz、1696条FOV射线、FOV精度、地图纹理分辨率或画质的前提下，减少QuantumRenderer对静态地图Canvas命令的重复光栅化。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`d2c2d52415f1f4c0df69bd614c6ebe6b72f367f4`
- 实现状态：尚未提交，保留在工作区；优化前基线已经提交。

## 文件变更

### `src/main/java/cs2d/client/GameClient.java`（+148 / -29）

- 新增`-Dcs2d.staticMapLayer=true`可选A/B开关，默认继续使用原单Canvas路径。
- 复用现有1024×1024障碍物分块图片和最大2048的全图概览图片，不重新压缩或降低贴图分辨率。
- 开启后将静态地图背景与障碍物图片挂载为长期驻留的JavaFX ImageView节点。
- 跟随/自由视角使用高清分块节点；全图视角使用现有概览纹理节点。
- 动态Canvas保持透明，只继续绘制玩家、掉落物、投掷物、特效、迷雾、准星和闪光效果。
- 每帧只更新一个复用Affine矩阵，使静态节点与Canvas相机的平移、缩放和居中偏移完全一致。
- 静态图层由1600×900裁剪节点限制，不会绘制到游戏区域外。
- 闪光弹视觉暂留改为捕获静态图层与动态Canvas的组合画面，维持原视觉内容。
- 地图断开、重连或服务端会话变化时立即将驻留图层标记为未就绪；未就绪期间自动使用Canvas回退路径。
- 性能日志新增`[STATIC-MAP] active/mode/cachedTiles`，用于确认A/B路径和当前tiles/overview模式。
- 未修改FOV射线数、角度、求交、轮廓简化、迷雾绘制算法、颜色、透明度、刷新率或网络频率。

### `src/test/java/cs2d/client/GameClientProtocolTest.java`（+14 / -0）

- 新增相机矩阵等价性测试，验证驻留节点的屏幕坐标与原Canvas相机公式一致。

### `.agent/changeHistory/2026-08-29-resident-static-map-layer.md`（+77 / -0）

- 记录基线、逐文件变化、验证、A/B启动方式、限制和回退方法。

## 配置与用户可见行为

- 默认安全路径不变，无需任何新参数。
- 实验优化路径新增VM参数：`-Dcs2d.staticMapLayer=true`。
- 开启后启动日志应显示`Static map layer: resident ImageView (A/B enabled)`。
- 地图缓存完成后应显示`Resident static map ready`；性能窗口应显示`[STATIC-MAP] active=true`。
- 用户本机`cs2d_settings.json`中的设置变化未被修改，也未纳入本轮实现。

## 验证

- `mvn test`：通过，20个测试，0失败，0错误，0跳过。
- 主代码和测试代码重新编译成功。
- `git diff --check`：通过；仅有仓库既有LF/CRLF转换提示。
- 回退路径默认开启，未传新参数时沿用基线中的单Canvas行为。

## A/B实机复测

基线/回退路径：

```text
-Dcs2d.renderHz=165 -Dprism.verbose=true -Djavafx.animation.fullspeed=true -Djavafx.animation.pulse=165
```

驻留静态地图路径：

```text
-Dcs2d.renderHz=165 -Dprism.verbose=true -Djavafx.animation.fullspeed=true -Djavafx.animation.pulse=165 -Dcs2d.staticMapLayer=true
```

在同一张`4392×3840 / 744障碍物`地图、同一相机模式和相近位置分别运行，比较：

- `[REAL]`实际FPS、1% Low、p99和严重迟帧；
- `[RUNTIME]`中的Prism线程CPU；
- `[STATIC-MAP]`是否为`active=true`以及`tiles/overview`模式；
- 地图与动态对象是否完全对齐；
- 分块边界是否存在缝隙、闪烁或模糊；
- 跟随、自由、全图视角切换，以及闪光弹视觉暂留。

## 限制与回退

- 自动化测试验证了矩阵和代码路径，但无法验证真实D3D/Prism像素输出和显卡性能。
- 新路径默认关闭，必须在实机确认对齐、无接缝且Prism CPU下降后才能考虑设为默认。
- 发现任何画面问题时移除`-Dcs2d.staticMapLayer=true`即可立即回到原单Canvas路径。
- 完整代码回退点：`d2c2d52415f1f4c0df69bd614c6ebe6b72f367f4`。
