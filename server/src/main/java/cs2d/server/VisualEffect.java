package cs2d.server;

import com.google.gson.JsonObject;

import java.awt.geom.Point2D;

/**
 * 代表一个临时的视觉效果，如枪口火焰或子弹轨迹。
 * 这些对象由服务器在事件发生时（如射击）创建，并随游戏状态一起发送给客户端。
 * 客户端负责根据这些数据渲染出相应的视觉效果。
 * 每个效果都有一个生命周期，过期后会被服务器自动移除。
 */
public class VisualEffect {
    String type; // 效果类型: "muzzle" (枪口火焰), "trail" (弹道)
    Point2D.Double start; // 起始位置
    Point2D.Double end; // 结束位置 (仅用于轨迹)
    long creationTime; // 创建时的时间戳 (System.currentTimeMillis())
    long duration; // 持续时间 (毫秒)

    /**
     * 构造函数 (用于点状效果，如枪口火焰)。
     * @param type 效果类型
     * @param pos 效果位置
     * @param duration 持续时间(ms)
     */
    public VisualEffect(String type, Point2D.Double pos, long duration) {
        this.type = type;
        this.start = pos;
        this.duration = duration;
        this.creationTime = System.currentTimeMillis();
    }

    /**
     * 构造函数 (用于线状效果，如子弹轨迹)。
     * @param type 效果类型
     * @param start 起始位置
     * @param end 结束位置
     * @param duration 持续时间(ms)
     */
    public VisualEffect(String type, Point2D.Double start, Point2D.Double end, long duration) {
        this(type, start, duration); // 调用另一个构造函数
        this.end = end;
    }

    /**
     * 检查效果是否已过期。
     * GameState 会在每帧调用此方法来清理过期的效果。
     * @return 如果效果应该被移除，则返回 true
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > creationTime + duration;
    }

    /**
     * 将视觉效果对象序列化为 JsonObject，用于发送给客户端。
     * 客户端将根据这个JSON来渲染效果。
     * @return 代表视觉效果的 JsonObject
     */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("x", start.x);
        obj.addProperty("y", start.y);
        // 如果是轨迹效果，则额外添加结束点坐标
        if (end != null) {
            obj.addProperty("endX", end.x);
            obj.addProperty("endY", end.y);
        }
        return obj;
    }
}
