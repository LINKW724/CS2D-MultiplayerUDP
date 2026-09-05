# 客户端周期性冻结：隔离 PCM 设备输出

- 日期：2026-09-02
- 目标：修复客户端每隔数秒出现一次、持续约 3～4 秒的整画面冻结，同时保持原有 48kHz、16-bit、双声道音质与声音触发频率。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 预改动基线：`e2dcb12a386463d432e380df009830d6903c1a00`
- 实现状态：实现文件已暂存，未创建最终实现提交；`cs2d_settings.json` 是用户本地改动，未暂存且未修改。

## 根因证据

- 服务器日志显示 60Hz 循环稳定，Tick 工作耗时和网络处理远低于 16.667ms 预算，不是周期性冻结来源。
- 客户端日志中约 3.25～3.60 秒的画面停顿与 PCM 设备写入停顿逐次重合，网络、FOV 和游戏逻辑耗时没有同步升高。
- JFR 中发现四次异常安全点进入等待：约 3.913s、3.433s、3.253s、3.580s；对应 GC 真正执行仅约 1.4～1.7ms。
- 异常前原生采样持续位于 `DirectAudioDevice.nAvailable/nWrite`。Windows JavaSound 在设备停顿时持有 JNI 临界数组，导致游戏 JVM 无法进入安全点，因此即使音频在普通 Java 后台线程，JavaFX/Prism 仍会整进程停顿。

## 变更文件

### `src/main/java/cs2d/client/IsolatedPcmOutputTransport.java`（+219 / -0）

- 新增回环 UDP PCM 传输层，使用直接缓冲区发送固定 PCM 批次。
- 使用随机会话令牌校验辅助进程 ACK，拒绝旧会话或其他来源的数据包。
- 750ms 未收到辅助进程 ACK 或进程退出时自动替换辅助进程。
- 重启时丢弃过期 PCM，不追赶补播旧声音；记录恢复次数和丢弃批次数。
- 关闭客户端时发送停止包并回收子进程与本机通道。

### `src/main/java/cs2d/client/PcmAudioMixer.java`（+37 / -159）

- 保留原有预解码、固定线程混音、48kHz/16-bit/双声道格式和四块批次大小。
- 移除游戏 JVM 内的 `SourceDataLine`、`available()`、`write()`、设备关闭和重开逻辑。
- PCM 写线程改为按真实音频时钟节奏把混音结果交给隔离传输层。
- 调度严重落后时重置发送时间基准，避免恢复后快速补发陈旧音频。
- 性能快照继续报告输出恢复和丢弃数据，但这些数据现在来自隔离传输层。

### `src/main/java/cs2d/client/PcmOutputTransport.java`（+19 / -0）

- 新增游戏混音器与操作系统音频设备之间的抽象边界。
- 定义启动、提交 PCM、恢复/丢弃统计和关闭生命周期。

### `src/main/java/cs2d/client/PcmOutputWorkerMain.java`（+129 / -0）

- 新增独立 JVM 音频设备进程；只有该进程会调用 `SourceDataLine.write()`。
- 每轮只播放最新可用 PCM 包，设备卡住恢复后不会补播积压声音。
- 单次设备写入超过 150ms 时关闭并重新打开音频设备。
- 每秒检查父客户端进程；父进程退出后自动关闭音频设备并结束。
- 复用直接 ACK 缓冲区，正常播放期间不反复创建 ACK 对象。

### `src/test/java/cs2d/client/IsolatedPcmOutputTransportTest.java`（+34 / -0）

- 覆盖正确会话令牌与序号解析。
- 覆盖旧辅助进程会话令牌被拒绝。

### `src/test/java/cs2d/client/PcmAudioMixerTest.java`（+6 / -14）

- 移除已经不存在的主进程 JavaSound 可写空间和设备停顿测试。
- 新增写线程调度落后后重置实时发送基准的测试。

### `.agent/changeHistory/2026-09-02-isolated-pcm-output.md`（+83 / -0）

- 记录根因证据、逐文件变化、验证结果、运行说明和回滚点。

## API、协议与用户可见行为

- 新增仅限客户端包内使用的 `PcmOutputTransport` 接口与回环 UDP 小协议；未改变游戏服务器网络协议。
- 启动 PCM 混音器时会额外启动一个 `PcmOutputWorkerMain` Java 进程，这是预期行为。
- 声音格式、素材、音量混合和正常触发频率保持不变。
- 音频设备若再次卡住，最坏影响被限制在辅助进程；客户端画面、输入、网络和状态线程不再等待该设备。
- 设备恢复期间可能丢弃过期的约 21ms PCM 批次，以保证声音时间线立即恢复到当前时刻。

## 验证

- `mvn test`：通过；48 个测试，0 失败，0 错误，0 跳过。
- `mvn -DskipTests package`：通过；主代码、测试代码、JAR、jpackage 和 assembly 均成功生成。
- `git diff --check`：通过；仅出现仓库既有的 LF/CRLF 转换提示，无空白错误。
- 源码检索确认：`SourceDataLine` 只存在于 `PcmOutputWorkerMain`，游戏混音器不再直接调用 JavaSound 设备。

## 运行、限制与回滚

- 需要完全退出并重新启动客户端，旧进程不会热更新。
- 首次实际验证时，任务管理器中出现额外的 Java 音频辅助进程属于正常现象。
- 自动测试验证了协议、混音与构建；Windows 音频驱动的长时间实机行为仍需用同一张地图和相同人数复测。
- 若系统无法启动辅助音频进程或打开 48kHz 双声道设备，客户端会保留原有 JavaFX 音频回退路径。
- 回滚到预改动状态：基于提交 `e2dcb12a386463d432e380df009830d6903c1a00` 新建分支或使用正常 Git 反向提交；不要覆盖未提交的 `cs2d_settings.json`。
