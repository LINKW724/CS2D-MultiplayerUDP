# 修复地面武器闪烁

- 日期：2026-08-29
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修复前基线：`38f37be631a0d60b085b0c7dff233509e4c27223`（`perf: optimize background fov calculations`）
- 状态：实现和验证已完成，修复本身尚未提交

## 目标

修复地面枪在客户端画面中周期性消失又出现的问题，同时保持目标刷新率、FOV射线数量、视野角、射线长度和服务端状态频率不变。

## 根因

1. 完整状态包由后台状态线程处理，旧实现先对并发 Map 执行 `clear()`，再逐项 `put()`；JavaFX绘制线程可能在两步之间观察到空集合或半集合。`ConcurrentHashMap` 可以避免结构损坏，但不能保证多步骤替换的原子性。
2. 地面物品可见性判定和迷雾绘制分别读取全局异步FOV引用；后台FOV若在同一绘制帧中间发布新结果，两部分可能使用不同多边形，造成边界处一帧内视觉不一致。

## 文件改动

### `src/main/java/cs2d/client/GameClient.java`

- 将可变 `ConcurrentHashMap` 地面物品集合替换为 `volatile` 不可变 Map 快照。
- 完整包先在临时 Map 中构建全部地面物品，完成后使用单次引用赋值整体发布。
- 断开连接和状态重置时同样使用空快照替换，不再原地清空共享集合。
- 每个绘制帧只读取一次FOV快照，并同时传给地面物品/C4可见性判断和迷雾绘制。
- 未加入可见性延迟或迟滞，避免产生短暂穿墙显示。
- 未改变165Hz目标、424条FOV射线、106°视野角或8000射线长度。

### `src/test/java/cs2d/client/GameClientProtocolTest.java`

- 新增地面物品完整不可变快照回归测试。
- 验证快照发布后不受输入数组新增元素影响。
- 验证绘制侧不能清空已发布快照。

## 改动规模

- `src/main/java/cs2d/client/GameClient.java`：新增33行，删除17行。
- `src/test/java/cs2d/client/GameClientProtocolTest.java`：新增22行，删除0行。
- `cs2d_settings.json` 的1行修改是修复前已存在的用户个人配置，本次未编辑、未纳入修复范围。

## 验证

- `mvn test`：成功。
- 共12个测试，0失败，0错误，0跳过。
- `git diff --check`：成功，无空白错误或冲突标记。
- 搜索确认不再存在 `droppedItems.clear()` 或 `droppedItems.put()` 更新路径。
- 搜索确认目标刷新率和FOV射线常量未改变。

## 手动验证

需要重启客户端并在游戏内丢下一把普通枪：

1. 站立不动观察数秒，枪的黄色边框和名称不应周期性消失。
2. 缓慢转动视角，枪在FOV内应稳定显示，在被墙体遮挡或离开FOV时正常隐藏。
3. 快速转头时，地面枪可见性与迷雾边界应保持同帧一致。

## 回退

可回退到修复前基线 `38f37be631a0d60b085b0c7dff233509e4c27223`。修复实现当前保留在工作区，尚未创建最终实现提交。
