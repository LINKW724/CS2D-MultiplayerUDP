package cs2d.client;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Per-mode source-visibility policy for damage-number feedback. */
public final class DamageNumberModePolicy {
    public static final String ZOMBIE_MODE = GameMode.ZOMBIE_MODE.name();

    private final Map<String, ObjectProperty<DamageNumberVisibility>> visibilityByMode = new LinkedHashMap<>();

    public DamageNumberModePolicy() {
        visibilityByMode.put(ZOMBIE_MODE, new SimpleObjectProperty<>(DamageNumberVisibility.OWN));
    }

    public DamageNumberVisibility getVisibilityFor(String gameMode) {
        ObjectProperty<DamageNumberVisibility> property = visibilityByMode.get(normalizeMode(gameMode));
        return property == null || property.get() == null ? DamageNumberVisibility.OFF : property.get();
    }

    public ObjectProperty<DamageNumberVisibility> visibilityProperty(String gameMode) {
        String normalizedMode = requireMode(gameMode);
        return visibilityByMode.computeIfAbsent(normalizedMode,
                ignored -> new SimpleObjectProperty<>(DamageNumberVisibility.OFF));
    }

    public void setVisibility(String gameMode, DamageNumberVisibility visibility) {
        visibilityProperty(gameMode).set(visibility == null ? DamageNumberVisibility.OFF : visibility);
    }

    public boolean showsOwnDamage(String gameMode) {
        return getVisibilityFor(gameMode).showsOwnDamage();
    }

    public boolean showsTeammateDamage(String gameMode) {
        return getVisibilityFor(gameMode).showsTeammateDamage();
    }

    public JsonObject toJson() {
        JsonObject modes = new JsonObject();
        visibilityByMode.forEach((mode, visibility) -> modes.addProperty(mode,
                (visibility.get() == null ? DamageNumberVisibility.OFF : visibility.get()).name()));
        JsonObject json = new JsonObject();
        json.add("visibilityByMode", modes);
        return json;
    }

    public void fromJson(JsonObject json) {
        if (json == null) {
            return;
        }
        if (json.has("visibilityByMode") && json.get("visibilityByMode").isJsonObject()) {
            loadVisibilityMap(json.getAsJsonObject("visibilityByMode"));
            return;
        }
        loadLegacyModeLists(json);
    }

    private void loadVisibilityMap(JsonObject modes) {
        visibilityByMode.values().forEach(property -> property.set(DamageNumberVisibility.OFF));
        for (Map.Entry<String, JsonElement> entry : modes.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                continue;
            }
            String mode = normalizeMode(entry.getKey());
            if (mode.isEmpty()) {
                continue;
            }
            try {
                setVisibility(mode, DamageNumberVisibility.valueOf(entry.getValue().getAsString()));
            } catch (IllegalArgumentException ignored) {
                setVisibility(mode, DamageNumberVisibility.OFF);
            }
        }
    }

    private void loadLegacyModeLists(JsonObject json) {
        if (!json.has("enabledModes") || !json.get("enabledModes").isJsonArray()) {
            return;
        }
        visibilityByMode.values().forEach(property -> property.set(DamageNumberVisibility.OFF));
        for (JsonElement element : json.getAsJsonArray("enabledModes")) {
            String mode = modeFromElement(element);
            if (!mode.isEmpty()) {
                setVisibility(mode, legacyContains(json, "teammateDamageModes", mode)
                        ? DamageNumberVisibility.ALL
                        : DamageNumberVisibility.OWN);
            }
        }
    }

    private static boolean legacyContains(JsonObject json, String member, String expectedMode) {
        if (!json.has(member) || !json.get(member).isJsonArray()) {
            return false;
        }
        for (JsonElement element : json.getAsJsonArray(member)) {
            if (expectedMode.equals(modeFromElement(element))) {
                return true;
            }
        }
        return false;
    }

    private static String modeFromElement(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? normalizeMode(element.getAsString())
                : "";
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
