// 文件: cs2d/AIControl/B/PerceptionInfo.java
package cs2d.AIControl.B;

        import java.awt.geom.Point2D;

/**
 * 存储单个敌人感知信息的记录类 (Record)。
 * 这是你描述的 "表格" 中的一行数据。
 *
 * @param enemyId            感知到的敌人ID
 * @param type               感知方式 (视觉, 枪声, 脚步声)
 * @param lastKnownPosition  最后感知到的精确位置
 * @param timestamp          最后更新此感知信息的时间戳 (毫秒)
 * @param isCurrentlyVisible 标记当前这一帧是否直接可见 (仅当 type 为 SIGHT 时才可能为 true)
 */
public record PerceptionInfo(
        String enemyId,
        PerceptionType type,
        Point2D.Double lastKnownPosition,
        long timestamp,
        boolean isCurrentlyVisible
) {
    // Record 类自动提供了构造函数、getter、equals、hashCode 和 toString
}