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

    @Test
    void clampsFogDarknessAndFallsBackForNonFiniteValues() {
        GameSettings settings = new GameSettings();
        double original = settings.getFogDarkness();
        try {
            settings.setFogDarkness(-0.2);
            assertEquals(GameSettings.MIN_FOG_DARKNESS, settings.getFogDarkness());

            settings.setFogDarkness(1.2);
            assertEquals(GameSettings.MAX_FOG_DARKNESS, settings.getFogDarkness());

            settings.setFogDarkness(Double.NaN);
            assertEquals(GameSettings.DEFAULT_FOG_DARKNESS, settings.getFogDarkness());
        } finally {
            settings.setFogDarkness(original);
        }
    }

    @Test
    void persistsFogDarknessAndKeepsBackwardCompatibleDefault() {
        GameSettings settings = new GameSettings();
        double original = settings.getFogDarkness();
        try {
            settings.setFogDarkness(GameSettings.DEFAULT_FOG_DARKNESS);
            settings.fromJson(new JsonObject());
            assertEquals(GameSettings.DEFAULT_FOG_DARKNESS, settings.getFogDarkness());

            JsonObject json = new JsonObject();
            json.addProperty("fogDarkness", 0.42);
            settings.fromJson(json);
            assertEquals(0.42, settings.getFogDarkness(), 0.000_001);
            assertEquals(0.42, GameSettings.toJson().get("fogDarkness").getAsDouble(), 0.000_001);
        } finally {
            settings.setFogDarkness(original);
        }
    }
}
