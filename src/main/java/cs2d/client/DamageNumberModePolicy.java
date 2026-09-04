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
    private final Map<String, BooleanProperty> teammateDamageByMode = new LinkedHashMap<>();

    public DamageNumberModePolicy() {
        enabledByMode.put(ZOMBIE_MODE, new SimpleBooleanProperty(true));
        teammateDamageByMode.put(ZOMBIE_MODE, new SimpleBooleanProperty(false));
    }

    public boolean isEnabledFor(String gameMode) {
        BooleanProperty property = enabledByMode.get(normalizeMode(gameMode));
        return property != null && property.get();
    }

    public BooleanProperty enabledProperty(String gameMode) {
        String normalizedMode = requireMode(gameMode);
        return enabledByMode.computeIfAbsent(normalizedMode, ignored -> new SimpleBooleanProperty(false));
    }

    public void setEnabled(String gameMode, boolean enabled) {
        enabledProperty(gameMode).set(enabled);
    }

    public boolean isTeammateDamageEnabledFor(String gameMode) {
        BooleanProperty property = teammateDamageByMode.get(normalizeMode(gameMode));
        return property != null && property.get();
    }

    public BooleanProperty teammateDamageEnabledProperty(String gameMode) {
        String normalizedMode = requireMode(gameMode);
        return teammateDamageByMode.computeIfAbsent(normalizedMode, ignored -> new SimpleBooleanProperty(false));
    }

    public void setTeammateDamageEnabled(String gameMode, boolean enabled) {
        teammateDamageEnabledProperty(gameMode).set(enabled);
    }

    public JsonObject toJson() {
        JsonArray enabledModes = new JsonArray();
        enabledByMode.forEach((mode, enabled) -> {
            if (enabled.get()) {
                enabledModes.add(mode);
            }
        });
        JsonArray teammateDamageModes = new JsonArray();
        teammateDamageByMode.forEach((mode, enabled) -> {
            if (enabled.get()) {
                teammateDamageModes.add(mode);
            }
        });
        JsonObject json = new JsonObject();
        json.add("enabledModes", enabledModes);
        json.add("teammateDamageModes", teammateDamageModes);
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

        teammateDamageByMode.values().forEach(property -> property.set(false));
        if (json.has("teammateDamageModes") && json.get("teammateDamageModes").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("teammateDamageModes")) {
                if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String mode = normalizeMode(element.getAsString());
                if (!mode.isEmpty()) {
                    teammateDamageEnabledProperty(mode).set(true);
                }
            }
        }
    }

    private static String requireMode(String gameMode) {
        String normalizedMode = normalizeMode(gameMode);
        if (normalizedMode.isEmpty()) {
            throw new IllegalArgumentException("game mode must not be blank");
        }
        return normalizedMode;
    }

    private static String normalizeMode(String gameMode) {
        return gameMode == null ? "" : gameMode.trim();
    }
}
