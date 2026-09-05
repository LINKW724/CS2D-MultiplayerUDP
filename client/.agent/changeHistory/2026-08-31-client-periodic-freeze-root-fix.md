# 客户端周期性冻结根因修复

- 日期：2026-08-31
- 目标：修复客户端每约 5～6 秒冻结数秒，以及复杂视野下 JavaFX/Prism 偶发超长帧。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`ed1ada9`（`fix: avoid blocking pcm device writes`）
- 当前实现状态：工作区未提交，等待用户实机复测后决定是否提交。

## 本轮证据与根因

- 客户端日志仍出现 `3631.896ms`、`2561.646ms`、`4353.787ms` 的超长帧，而同期服务端 60Hz 循环稳定，根因在客户端。
- 新 JFR 录制 168 秒，仅剩一次超过 100ms 的 JVM Safepoint：总计 `2.551s`，实际 G1 回收工作约 `2.134ms`；上一轮 PCM 原生写入修复已显著减少全局停顿，但未解决全部冻结。
- `LAN-Discovery-Thread` 在 `NetworkInterface.getAll()` 中反复阻塞：首次约 `4.0s`，随后多次约 `2.8～3.0s`，周期约 5 秒，与用户看到的冻结周期直接吻合。
- 旧实现还会从 JavaFX `AnimationTimer` 调用发现广播，因此 Windows 网卡枚举可能直接阻塞绘制线程。
- JFR Java 热点中 Marlin 光栅化约占 64%：`MaskMarlinAlphaConsumer.setAndClearRelativeAlphas` 约 53.27%，`Renderer._endRendering` 约 10.08%。
- 4.35 秒长帧期间 UDP 接收线程仍在工作并积累 633 个分片，证明该次不是服务器停顿或 JVM 全局暂停，而是 JavaFX/Prism 渲染线程被复杂迷雾 Path 光栅化拖住。
- 上一轮用 `SourceDataLine.available()` 避免阻塞写入后，JFR 中 `DirectAudioDevice.nAvailable` 出现 536 个样本、约占 6.73%；虽然 `nWrite` 已降到 30 个样本，但高频原生容量查询成为新的无效开销。

## 文件变更

### `src/main/java/cs2d/client/GameClient.java`

- 行数：新增 101 行，删除 125 行。
- 从 JavaFX `AnimationTimer` 完全移除 LAN 探测，绘制线程不再做任何网卡发现或网络接口枚举。
- 局域网发现改用独立 `discoveryRunning` 生命周期，不再错误复用整个客户端的 `running` 标志。
- 进入手动 IP、选择服务器、正式连接和关闭客户端时立即关闭发现 Socket 并中断发现线程。
- 广播改为系统受限广播 `255.255.255.255` 加同机回环 `127.0.0.1`，彻底移除每轮 `NetworkInterface.getNetworkInterfaces()`。
- 发现线程不再从后台读取 JavaFX 控件可见性；只负责收发并通过 `Platform.runLater` 发布列表更新。
- 将全屏迷雾从 retained JavaFX `Path` 改为 retained `Canvas` 像素遮罩。
- 新 FOV 结果到达或缓存覆盖区不足时，使用同一组精确顶点和 EVEN_ODD 填充更新一次遮罩；中间 165Hz 显示帧只更新已有 Canvas 的仿射变换。
- 复用迷雾多边形坐标数组，不在每帧创建 PathElement 或顶点对象。
- FOV 颜色、顶点、射线数量、精度、可见范围及画面质量保持不变。

### `src/main/java/cs2d/client/PcmAudioMixer.java`

- 行数：新增 19 行，删除 16 行。
- 移除每 0.25ms 调用一次的原生 `SourceDataLine.available()` 容量轮询。
- 按 `48,000Hz / 256帧 = 5.333ms` 使用纯 Java 单调时钟调度每个 PCM 混音块。
- 线程被系统调度延迟后从当前时间重新定相，不突发补写历史音频，避免设备缓冲和 CPU 瞬时洪峰。
- 保持 48kHz、16-bit、双声道、256 帧块、原声音样本、并发 voice、增益及声音事件频率不变。
- 新增可测试的 `nextWriteDeadline(...)`，明确固定节拍和丢弃过期节拍的行为。

### `src/test/java/cs2d/client/PcmAudioMixerTest.java`

- 行数：新增 7 行，删除 8 行。
- 将原生容量轮询测试替换为固定 PCM 节拍测试。
- 覆盖正常节拍推进和线程迟到后不追赶、不突发补写两种情况。
- 原有无损 PCM 编解码、并发混音/饱和及全部 WAV 资源解码测试继续保留。

### `.agent/changeHistory/2026-08-31-client-periodic-freeze-root-fix.md`

- 行数：新增 74 行，删除 0 行；记录基线、JFR 证据、逐文件修改、验证与回退方式。

## API、协议与体验

- 未修改客户端/服务端 UDP 协议、60Hz 服务端、Sub-tick 输入或 165Hz 客户端刷新目标。
- 未降低射线数量、FOV 精度、地图精度、画面质量或声音质量与播放频率。
- 未修改服务器项目。
- `cs2d_settings.json` 是用户已有的本地设置改动，本轮未触碰，也不会纳入本轮实现。

## 验证

- `mvn -Dtest=PcmAudioMixerTest,GameClientProtocolTest test`：23 个关键测试通过，0 失败。
- `mvn test`：35 个测试全部通过，0 失败、0 错误、0 跳过。
- `git diff --check`：通过，仅显示仓库既有的 LF/CRLF 转换提示。

## 手动验证、限制与回退

- 必须完全退出并重新启动客户端，旧 JVM 不会加载新的发现线程、迷雾 Canvas 和 PCM 节拍。
- 建议用相同 JFR 参数进入同一张地图连续实战 1～2 分钟，并同时测试转头、全局视角和大量枪声。
- 预期 `LAN-Discovery-Thread -> NetworkInterface.getAll()` 消失，Marlin 占比显著下降，且不再按 5～6 秒周期出现 2.8～4.3 秒长帧。
- 广播发现依赖操作系统把 `255.255.255.255` 路由到活动 LAN；同机服务器始终额外发送 `127.0.0.1` 探针。
- 若需回退，可从基线 `ed1ada9` 对比恢复本轮三个源码/测试文件并删除本记录；用户设置不受影响。
