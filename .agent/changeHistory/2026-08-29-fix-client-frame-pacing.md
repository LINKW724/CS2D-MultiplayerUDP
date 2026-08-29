# 修复客户端掉帧与帧调度抖动

- 日期：2026-08-29
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`5b61f6e6914d9a36fabe6c147190d10804663da3`（`fix: stabilize dropped weapon rendering`）
- 状态：实现与自动化验证已完成，掉帧修复本身尚未提交

## 目标

解决客户端在帧内逻辑和Canvas绘制仅约0.5～0.8ms时，实际帧率仍在约90～140FPS波动的问题；保持165Hz目标、120Hz输入/服务器节奏、424条FOV射线及画面精度不变。

## 日志结论

- Direct3D硬件管线成功启用，GPU为NVIDIA GeForce RTX 3060 Laptop GPU，并非软件渲染。
- 稳定阶段帧内代码通常约0.5～0.8ms，Canvas绘制通常约0.2～0.3ms。
- 大部分丢失时间位于JavaFX Pulse/呈现间隔，而不是游戏逻辑、FOV或Canvas绘制。
- JavaFX Pulse已由启动入口配置为165Hz，但游戏循环又叠加了同样165Hz的`RenderFrameScheduler`。当Pulse比截止时间早亚毫秒到达时，旧逻辑会跳过本次Pulse，直到下一次Pulse才绘制，形成明显的同频拍频和整帧丢失。
- HUD另外拥有一个匿名`AnimationTimer`，与主游戏循环分别运行；HUD持续重复写入相同文本/样式，产生不必要的JavaFX脏节点、CSS和布局工作。
- 每次120Hz状态更新都会提交购买菜单`Platform.runLater`任务；两秒一次的多行性能报告也直接在JavaFX线程输出到IDEA控制台。

## 文件改动

### `src/main/java/cs2d/client/GameClient.java`

- 移除主游戏循环中的第二层同频`RenderFrameScheduler`判断；`ClientMain`配置的JavaFX 165Hz Pulse成为唯一帧时钟，每个Pulse直接绘制。
- 将HUD更新合并到唯一游戏帧循环，移除无法单独停止的匿名HUD `AnimationTimer`。
- HUD文本和样式只在值实际变化时写入，减少无变化的CSS/布局失效，不降低HUD调用频率。
- 状态线程不再为每个状态包提交购买菜单`Platform.runLater`；改为原子刷新标志，由JavaFX帧边界一次合并消费。
- 新增守护诊断线程，两秒性能报告在后台格式化并输出，IDEA控制台阻塞不再卡住JavaFX帧线程。
- 将日志中的`[P]`说明改为“JavaFX Pulse/呈现间隔”，避免把正常帧间等待误认为帧内计算。
- 保留原有FOV后台计算、网络状态帧边界应用和输入发送机制。

### `src/test/java/cs2d/ClientMainTest.java`

- 新增45行测试，验证JavaFX Pulse被准确配置为165Hz且启用fullspeed，作为唯一客户端帧源。
- 测试结束后恢复所有系统属性，避免污染其他测试。

## 改动规模

- `src/main/java/cs2d/client/GameClient.java`：新增99行，删除90行。
- `src/test/java/cs2d/ClientMainTest.java`：新增45行，删除0行。
- `cs2d_settings.json`的一行修改是修改前已存在的用户个人设置，本次未编辑、未纳入优化范围。

## 保持不变的体验参数

- 客户端目标刷新率：165Hz。
- 输入发送目标：120Hz。
- FOV射线：424条。
- FOV视角：106°。
- 射线长度：8000。
- 未降低绘制精度、网络状态频率或视觉效果质量。

## 验证

- `mvn test`：成功。
- 共13个测试，0失败，0错误，0跳过。
- 主代码与测试代码均重新编译成功。
- `git diff --check`：成功，无空白错误或冲突标记。
- 搜索确认`GameClient`只剩主游戏循环一个`AnimationTimer`，且不再引用`renderScheduler`或`hudScheduler`。

## 手动验证

必须完全重启客户端，使JavaFX Pulse启动参数重新生效。进入游戏后连续移动和快速转头至少30秒，重点观察：

- `[REAL]`实际FPS是否接近165，样本数在约2秒窗口内应接近330。
- 1% Low、p99和严重迟帧数是否明显改善。
- `[B]`应继续保持远低于6.06ms；`[P]`现在表示Pulse之间的正常间隔，不应再被解释为代码耗时。
- 购买菜单、HUD、倒计时、交互进度条和FOV行为应保持正常。

## 回退

可回退到基线`5b61f6e6914d9a36fabe6c147190d10804663da3`。实现修改当前仍位于工作区，未创建最终提交。
