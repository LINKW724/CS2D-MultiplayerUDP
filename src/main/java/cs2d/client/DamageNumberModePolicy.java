package cs2d.client;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/** Per-mode feature policy for damage-number feedback. */
public final class DamageNumberModePolicy {
    public static final String ZOMBIE_MODE = GameMode.ZOMBIE_MODE.name();

    private final Map<String, BooleanProperty> enabledByMode = new LinkedHashMap<>();

    public DamageNumberModePolicy() {
        enabledByMode.put(ZOMBIE_MODE, new SimpleBooleanProperty(true));
    }

    public boolean isEnabledFor(String gameMode) {
        BooleanProperty property = enabledByMode.get(normalizeMode(gameMode));
        return property != null && property.get();
    }

    public BooleanProperty enabledProperty(String gameMode) {
        String normalizedMode = normalizeMode(gameMode);
        if (normalizedMode.isEmpty()) {
            throw new IllegalArgumentException("game mode must not be blank");
        }
        return enabledByMode.computeIfAbsent(normalizedMode, ignored -> new SimpleBooleanProperty(false));
    }

    public void setEnabled(String gameMode, boolean enabled) {
        enabledProperty(gameMode).set(enabled);
    }

    public JsonObject toJson() {
        JsonArray enabledModes = new JsonArray();
        enabledByMode.forEach((mode, enabled) -> {
            if (enabled.get()) {
                enabledModes.add(mode);
            }
        });
        JsonObject json = new JsonObject();
        json.add("enabledModes", enabledModes);
        return json;
    }

    public void fromJson(JsonObject json) {
        if (json == null || !json.has("enabledModes") || !json.get("enabledModes").isJsonArray()) {
            return;
        }

        enabledByMode.values().forEach(property -> property.set(false));
        for (JsonElement element : json.getAsJsonArray("enabledModes")) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                continue;
            }
            String mode = normalizeMode(element.getAsString());
            if (!mode.isEmpty()) {
                enabledProperty(mode).set(true);
            }
        }
    }

    private static String normalizeMode(String gameMode) {
        return gameMode == null ? "" : gameMode.trim();
    }
}
