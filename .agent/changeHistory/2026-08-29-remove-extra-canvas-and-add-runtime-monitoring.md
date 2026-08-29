# 移除额外全屏Canvas并增加真实运行时监控

- 日期：2026-08-29
- 任务目标：在不降低165Hz、1696条FOV射线、FOV精度或画质的前提下，消除分层迷雾实验遗留的Prism合成开销，并补齐FX/Prism/GC运行时诊断。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`5d88db026316ff891aeea8069037819a0c82a494`
- 实现状态：代码尚未提交，保留在工作区；基线快照已提交。

## 文件变更

### `src/main/java/cs2d/client/GameClient.java`（+26 / -85）

- 完全移除实验性独立迷雾Canvas、屏幕覆盖Canvas及其每帧全屏清理/合成路径。
- 恢复单Canvas绘制顺序：世界 → EVEN_ODD迷雾 → 准星/瞄准线/屏外指示器 → 闪光效果。
- 保留FOV屏幕坐标直接换算，避免为最终轮廓顶点创建临时`Point2D`。
- 保留既有HUD缓存和地图分块缓存优化。
- 将启动日志固定为`single-canvas EVEN_ODD`，不再暴露已确认失效的实验开关。
- 将原`[P]`重命名为`[PACE]`，明确其包含目标帧等待、Pulse、Prism和系统调度，不能直接当成GPU耗时。
- 每2秒输出新的`[RUNTIME]`指标：进程CPU、FX线程CPU/状态、Prism线程CPU/状态、GC次数/停顿及堆内存。

### `src/main/java/cs2d/client/RuntimePerformanceMonitor.java`（+130 / -0）

- 新增低频JVM运行时采样器。
- 使用MXBean读取JavaFX Application Thread与QuantumRenderer/Prism线程的CPU增量和状态。
- 统计进程CPU、垃圾回收次数与停顿、堆内存使用量。
- 所有MXBean采样均在后台诊断线程执行，系统查询变慢时不会阻塞JavaFX游戏线程。
- 对不支持线程CPU计时的平台安全降级，不影响游戏运行。

### `src/test/java/cs2d/client/GameClientProtocolTest.java`（+0 / -7）

- 删除只服务于已移除分层迷雾实验的透明度辅助函数测试。

### `src/test/java/cs2d/client/RuntimePerformanceMonitorTest.java`（+22 / -0）

- 新增运行时采样安全性测试，验证所有计数非负、内存关系有效且线程状态始终可用。

### `.agent/changeHistory/2026-08-29-remove-extra-canvas-and-add-runtime-monitoring.md`（+75 / -0）

- 记录本轮基线、逐文件变更、验证结果、限制和复测方法。

## 保留与移除的行为

- 保留：165Hz目标、1696条射线、106度FOV、求交/轮廓精度、迷雾颜色和透明度、HUD缓存、地图缓存。
- 移除：`-Dcs2d.compositedFog=true`实验开关以及两个额外1600×900 Canvas图层。
- 用户本机`cs2d_settings.json`中的`followZoomFactor=1.0`变化未被修改，也不会纳入实现提交。

## 验证

- `mvn test`：通过，19个测试，0失败，0错误，0跳过。
- 主代码与测试代码均重新编译成功。
- 实验路径残留搜索：没有发现`compositedFog`、`fogCanvas`、`screenOverlayCanvas`或`drawFogComposited`引用。
- `git diff --check`：通过；仅有仓库既有LF/CRLF转换提示。

## 实机复测

继续使用原启动参数，无需加入新的VM选项：

```text
-Dcs2d.renderHz=165 -Dprism.verbose=true -Djavafx.animation.fullspeed=true -Djavafx.animation.pulse=165
```

启动日志应显示：

```text
[Render] Fog pipeline: single-canvas EVEN_ODD
```

重点比较`[REAL]`、`[PACE]`和新增`[RUNTIME]`。如果迟帧窗口中GC为0、FX/Prism线程CPU也不高，则剩余问题主要在系统调度或GPU等待；如果Prism线程CPU显著升高，则继续优化Canvas提交与合成。

## 限制与回退

- 自动化测试无法验证真实D3D/Prism像素输出和165Hz显示器上的体感，需要实机日志与视觉复测。
- 本轮完整回退点：`5d88db026316ff891aeea8069037819a0c82a494`。
- 回退点包含旧实验图层，正常运行时默认禁用；当前工作区则已彻底删除实验图层。
