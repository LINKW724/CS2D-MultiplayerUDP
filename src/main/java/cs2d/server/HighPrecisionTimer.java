package cs2d.server;

import java.util.concurrent.locks.LockSupport;

/**
 * 高精度定时器 - 修复原文漏洞
 * 使用 LockSupport + 余量自旋机制实现精确时间控制
 */
public class HighPrecisionTimer {

    private static final long SPIN_THRESHOLD_NS = 2_000_000; // 2ms
    private static final long PARK_BUFFER_NS = 500_000; // 0.5ms缓冲

    /**
     * 极高精度的休眠阻断 - 修复原文漏洞
     *
     * @param targetTimeNs 这一帧的绝对目标结束时间（纳秒）
     */
    public void preciseSleepUntil(long targetTimeNs) {
        long remainingNs;

        // 1. 系统休眠让出 CPU (阻塞阶段)
        while ((remainingNs = targetTimeNs - System.nanoTime()) > SPIN_THRESHOLD_NS) {
            // 动态调整缓冲时间，防止 Windows 调度器超期休眠
            long parkTime = Math.max(1, remainingNs - SPIN_THRESHOLD_NS - PARK_BUFFER_NS);
            LockSupport.parkNanos(parkTime);

            // 检查线程是否被中断
            if (Thread.interrupted()) {
                break; // 让外部调用者检测 interupt 或者由外层循环决定
            }
        }

        // 2. 高精度自旋阶段 - 关键修复！
        // 确保精确到达目标时间，不会提前结束
        while (System.nanoTime() < targetTimeNs) {
            Thread.onSpinWait();
            // 在 SpinWait 期间也可以检查中断以保障快速安全退出
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
    }

    /**
     * 带监控的精确睡眠 - 用于验证修复效果
     */
    public void monitoredPreciseSleep(long targetTimeNs) {
        long startTime = System.nanoTime();
        preciseSleepUntil(targetTimeNs);
        long actualElapsed = System.nanoTime() - startTime;
        long targetElapsed = targetTimeNs - startTime;

        // 监控精度偏差 - 验证修复是否成功
        long deviation = actualElapsed - targetElapsed;
        if (Math.abs(deviation) > 100_000) { // >0.1ms偏差
            System.out.printf("Timer deviation: %+d ns (%.3f ms)\n",
                    deviation, deviation / 1_000_000.0);
        }
    }
}
