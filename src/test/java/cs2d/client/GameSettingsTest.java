package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class GameSettingsTest {
    @Test
    void clampsKillFeedLimitLoadedFromSettings() {
        GameSettings settings = new GameSettings();
        int original = settings.getMaxKillFeedEntries();
        JsonObject json = new JsonObject();
        try {
            json.addProperty("maxKillFeedEntries", -3);
            settings.fromJson(json);
            assertEquals(GameSettings.MIN_KILL_FEED_ENTRIES, settings.getMaxKillFeedEntries());

            json.addProperty("maxKillFeedEntries", 99);
            settings.fromJson(json);
            assertEquals(GameSettings.MAX_KILL_FEED_ENTRIES, settings.getMaxKillFeedEntries());
        } finally {
            settings.setMaxKillFeedEntries(original);
        }
    }
}
