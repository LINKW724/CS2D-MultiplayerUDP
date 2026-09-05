// 文件: cs2d/AIControl/B/PerceptionType.java
package cs2d.AIControl.B;

/**
 * 感知类型枚举：表示 AI 是如何感知到敌人的。
 */
public enum PerceptionType {
    SIGHT,    // 直接视觉看到
    GUNSHOT,  // 听到枪声 (可能在障碍物后)
    FOOTSTEP  // 听到脚步声 (可能在障碍物后)
}

// ======================================================

