# PCM 音频设备停顿恢复

- 日期：2026-09-02
- 目标：修复客户端每隔数秒出现数秒画面停顿的问题；保留 48kHz、16-bit、立体声 PCM 品质及正常枪声频率。
- 仓库：`O:/java/games/CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前安全快照：`a5e0b0cc1be293b8f0bfe130d6e3859fcbf13515`

## 根因

- 旧写线程把完整 4096B 批次直接交给阻塞式 `SourceDataLine.write()`，不检查设备当前可写空间。
- Windows DirectAudio 消费暂停时，`write()` 可持续等待 2～4.6 秒；日志中的画面最大停顿与音频最大写入耗时逐次吻合。
- 旧设备缓冲只有一个 21.33ms 批次，音频线程还使用高于普通线程的优先级，设备恢复时会放大 JavaFX/Prism 调度抖动。
- 服务端同一次换弹可能产生两个事件，进一步增加重叠声部。

## 实现

- 写入前读取 `line.available()`，单次只提交当前设备明确可容纳且按 PCM frame 对齐的字节。
- 设备缓冲增至两个批次（约 42.7ms）；采样率、位深、声道数和混音块精度不变。
- 150ms 无写入进展或单次 native write 超时后，请求设备恢复。
- 恢复时立即清空未播放请求、活动声部和环形缓冲，不补播已经过期的声音。
- 看门狗从独立普通优先级线程关闭失效 line，写线程随后自动重新打开设备。
- 混音、写入和看门狗线程全部使用普通优先级，UI、网络和游戏逻辑只发布无锁请求，不等待设备。
- 新增来源级重复声音门控：同一来源 100ms 内重复脚步、350ms 内重复换弹被合并；枪声和其他声音完全绕过门控。
- 性能日志新增设备恢复数、陈旧块丢弃数以及脚步/换弹重复丢弃数。

## 文件变更

- `src/main/java/cs2d/client/PcmAudioMixer.java`：新增 197 行、删除 37 行。
- `src/main/java/cs2d/client/GameClient.java`：新增 10 行、删除 3 行。
- `src/main/java/cs2d/client/RepeatedWorldSoundGate.java`：新增 71 行。
- `src/test/java/cs2d/client/PcmAudioMixerTest.java`：新增 28 行。
- `src/test/java/cs2d/client/RepeatedWorldSoundGateTest.java`：新增 40 行。

## 验证

- `PcmAudioMixerTest,RepeatedWorldSoundGateTest` 聚焦测试通过。
- 客户端全量 Maven 测试通过。
- 本机 JDK 23 `DirectAudioDevice.DirectDL` 字节码确认：`write()` 会在内部等待设备空间；本实现通过 `available()` 避免进入该等待路径，并用独立看门狗处理异常 native 停顿。
- `git diff --check` 通过，仅有现有 LF/CRLF 提示。

## 运行与观察

- 需要完全重启客户端才能建立新的 PCM 设备线程和缓冲。
- 新日志应显示正常窗口最大写入接近 5～25ms；异常设备停顿时 `设备恢复` 与 `陈旧块丢弃` 会递增，不应再补播数秒旧声音。
- 仍需在实际 Windows 音频设备上完成长时间战斗验证；单元测试不能模拟真实驱动冻结。
- 用户修改的 `cs2d_settings.json` 未编辑、未暂存、未提交。
- 本次实现尚未提交，保留在工作区等待实机确认。
