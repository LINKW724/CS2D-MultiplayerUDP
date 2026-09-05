# 分层迷雾 / Prism 合成优化

- 日期：2026-08-29
- 任务目标：不降低165Hz刷新率、1696条射线、FOV精度或画面质量，减少玩家视角迷雾对JavaFX/Prism呈现阶段的压力。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`20c6d9d0690dd62a2299e048bef5596bc85499a8`
- 实现状态：尚未提交。实机发现分层裁剪清除路径无法正确显示FOV孔洞，已恢复旧路径为默认。

## 文件变更

### `src/main/java/cs2d/client/GameClient.java`（+104 / -32）

- 将单一游戏Canvas拆为世界、迷雾、屏幕特效三个固定1600×900图层。
- 保持原有绘制顺序：世界 → 迷雾 → 准星/瞄准线/屏外指示器/闪光效果 → JavaFX HUD。
- 实验性迷雾路径使用独立迷雾层的“不透明底色覆盖 → FOV多边形裁剪 → 裁剪区域透明清除”。
- 迷雾层使用节点透明度保持跟随视角0.85、全局/自由视角0.5的原始视觉参数。
- 不再为每个最终FOV顶点创建临时屏幕坐标 `Point2D`。
- FOV为空时隐藏迷雾层；恢复FOV时覆盖旧孔洞并生成新孔洞。
- 闪光弹视觉暂留改为捕获三层游戏画面的合成快照，保持原绘制内容。
- 实机验证发现JavaFX 17 Canvas裁剪清除没有正确生成FOV孔洞，因此默认值改回旧 `EVEN_ODD` 路径。
- 只有显式加入 `-Dcs2d.compositedFog=true` 才启用实验路径，避免再次影响正常游戏。
- 启动日志会输出当前迷雾管线：`layered clipped-clear` 或 `legacy EVEN_ODD`。
- 未修改刷新率、射线数量、FOV角度、交点算法、轮廓精度、颜色或透明度。

### `src/test/java/cs2d/client/GameClientProtocolTest.java`（+7 / -0）

- 新增迷雾透明度兼容测试，验证跟随、全局和自由相机模式保持原值。

### `cs2d_settings.json`（+1 / -1）

- Maven测试曾将用户缩放设置从2.0写回1.0；已恢复为基线中的2.0。
- 文件仅因补丁工具补充结尾换行产生文本差异，设置值没有变化。

## 验证

- 最终 `mvn test`：通过。
- 结果：19个测试，0失败，0错误，0跳过。
- 客户端主代码和测试代码重新编译成功。
- `git diff --check`：通过；仅有仓库既有LF/CRLF提示。

## 手动A/B复测

默认安全路径：

```text
-Dcs2d.renderHz=165 -Djavafx.animation.fullspeed=true -Djavafx.animation.pulse=165
```

实验路径（当前存在FOV孔洞显示问题，仅供后续诊断）：

```text
-Dcs2d.renderHz=165 -Djavafx.animation.fullspeed=true -Djavafx.animation.pulse=165 -Dcs2d.compositedFog=true
```

检查项目：

- 跟随、全局、自由视角的迷雾颜色、透明度和FOV边界。
- 准星、瞄准线和屏外指示器始终位于迷雾上方。
- 闪光弹白屏和视觉暂留。
- 平均FPS、1% Low、严重迟帧、`[P]` 和 `[3] 迷雾绘制`。

## 限制与回退

- 自动化测试不能验证真实Prism/GPU性能和最终像素，需要实际客户端A/B复测。
- 分层裁剪清除路径当前不得作为默认优化方案；正常启动始终使用旧迷雾管线。
- 完整代码回退点为 `20c6d9d0690dd62a2299e048bef5596bc85499a8`。
