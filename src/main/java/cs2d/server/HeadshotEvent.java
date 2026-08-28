package cs2d.server;

import com.google.gson.JsonObject;

/**
 * 代表一个私有声音事件，只发送给特定的客户端。
 */
public class HeadshotEvent {
    String recipientId;
    String soundName;
    long timestamp; // [新增]

    public HeadshotEvent(String recipientId, String soundName) {
        this.recipientId = recipientId;
        this.soundName = soundName;
        this.timestamp = System.currentTimeMillis(); // [新增]
    }

    public long timestamp() { return timestamp; } // [新增]

    /**
     * 将事件序列化为JSON对象，以便包含在主游戏状态的JSON中发送给客户端。
     * @return 包含接收者ID和声音名称的JsonObject。
     */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("recipientId", recipientId);
        obj.addProperty("soundName", soundName);
        return obj;
    }
}
