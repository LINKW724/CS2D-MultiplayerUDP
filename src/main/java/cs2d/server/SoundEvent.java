package cs2d.server;

import com.google.gson.JsonObject;


/**
 * 代表一个需要在客户端播放的、有具体位置的公共音效事件。
 */
public record SoundEvent(SoundType type, String soundName, double x, double y, String sourcePlayerId, long timestamp) {
    /**
     * 定义音效的类别，用于客户端决定如何平滑显示或过滤。
     */
    public enum SoundType {
        FOOTSTEP, // 脚步声 (范围小)
        FIRE,     // 枪声 (范围大)
        RELOAD,   // 换弹声
        EXPLODE,  // 爆炸声
        HIT,      // 受击音效 (如果是公共的)
        FIRE_LOOP_START,
        FIRE_LOOP_STOP,
        BOUNCE,
        SMOKE_EMIT,
        GENERIC
    }

    public SoundEvent(SoundType type, String soundName, double x, double y, String sourcePlayerId) {
        this(type, soundName, x, y, sourcePlayerId, System.currentTimeMillis());
    }

    /**
     * 将音效事件转换为 JSON 对象。
     */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type.name());
        obj.addProperty("soundName", soundName);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        if (sourcePlayerId != null) {
            obj.addProperty("sourcePlayerId", sourcePlayerId);
        }
        return obj;
    }
}
