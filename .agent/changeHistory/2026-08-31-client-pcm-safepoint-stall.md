# 客户端 PCM 原生写入 Safepoint 冻结修复

- 日期：2026-08-31
- 目标：消除客户端实战中每约 5～11 秒发生一次、持续 3～5 秒的全局冻结。
- 仓库：`O:\java\games\CS2D-MultiplayerUDP_Client`
- 分支：`main`
- 修改前基线：`dc68535`（`fix: avoid native diagnostics safepoint stalls`）

## 本轮证据与根因

- 新 JFR 仍记录到 `3.16s`、`4.52s`、`3.69s`、`4.30s` 的 `jdk.SafepointBegin` 等待。
- 与这些长安全点对应的 G1 回收工作实际只有约 `1～3ms`；数秒卡顿发生在 JVM 等待线程抵达安全点期间。
- 上轮已移除的 `OperatingSystemMXBean.getProcessCpuLoad0()` 在新 JFR 中没有再次出现，说明上轮修复已生效，但并非唯一阻塞源。
- 新 JFR 中，自有线程 `CS2D-PCM-Mixer` 在整个实战阶段持续采样于 Windows 原生 `DirectAudioDevice.nWrite()`。
- 混音循环原先每 256 帧直接调用一次 `SourceDataLine.write()`。该 API 会等待整块请求被设备接受；Windows 音频设备背压时，线程可能在 `nWrite()` 内阻塞并延迟 JVM Safepoint。

## 文件变更

### `src/main/java/cs2d/client/PcmAudioMixer.java`

- 行数：新增 23 行，删除 3 行。
- 在进入原生写入前检查音频设备是否有容纳整个 256 帧缓冲区的可写空间。
- 空间不足时用 0.25ms 的短暂停让出 CPU，下一轮重新检查，不进入可能长期阻塞的 `nWrite()`。
- 只有完整、按帧对齐的缓冲区才允许写入，避免部分帧和声道错位。
- 保持 `48kHz / 16-bit / stereo`、256 帧混音块、声音样本、增益、并发 voice 数量及播放频率不变。
- 新增包内可测的 `hasImmediateWriteCapacity(...)` 判定函数。

### `src/test/java/cs2d/client/PcmAudioMixerTest.java`

- 行数：新增 12 行，删除 0 行。
- 新增完整缓冲区容量、容量少 1 字节和非帧对齐请求三种情况的测试。
- 原有无损 PCM 编解码、并发混音/饱和与全部 WAV 解码测试继续保留。

### `.agent/changeHistory/2026-08-31-client-pcm-safepoint-stall.md`

- 行数：新增 56 行，删除 0 行；保存基线、JFR 证据、实现、验证及回退说明。

## API、协议与体验

- 未修改客户端/服务端网络协议、60Hz 服务器、Sub-tick 输入、165Hz 渲染、FOV、射线数量、画质或游戏规则。
- 未降低音频采样率、位深、声道、音质或声音事件频率。
- `cs2d_settings.json` 是用户原有本地设置改动，本轮未触碰且不会纳入修复。

## 验证

- `mvn -Dtest=PcmAudioMixerTest test`：4 个专项测试通过。
- `mvn test`：35 个测试全部通过，0 失败、0 错误、0 跳过。
- `git diff --check`：通过，仅有仓库换行符转换提示。

## 手动验证、限制与回退

- 必须完全退出并重新启动客户端，旧 JVM 不会加载新混音循环。
- 建议继续使用相同 JFR 参数实战 1～2 分钟；预期不再出现数秒级 `SafepointBegin`，服务端也不应再因客户端全局冻结而超时。
- Java Sound 最终仍需调用一次原生设备写入，但本次通过完整容量门控避免在设备没有即时空间时进入阻塞写入。
- 本轮实现随后作为继续排查剩余客户端冻结的可回退基线提交保存。
- 如需回退，可从基线 `dc68535` 对比恢复本轮两个源码/测试文件；用户设置不受影响。
