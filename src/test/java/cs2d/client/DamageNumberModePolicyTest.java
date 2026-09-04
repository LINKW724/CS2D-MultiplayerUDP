package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class DamageNumberModePolicyTest {
    @Test
    void defaultsToOwnDamageAndRoundTripsEveryScope() {
        DamageNumberModePolicy policy = new DamageNumberModePolicy();
        assertEquals(DamageNumberVisibility.OWN, policy.getVisibilityFor(DamageNumberModePolicy.ZOMBIE_MODE));
        assertEquals(DamageNumberVisibility.OFF, policy.getVisibilityFor("TEAM_DEATHMATCH"));

        policy.setVisibility(DamageNumberModePolicy.ZOMBIE_MODE, DamageNumberVisibility.TEAMMATES);
        policy.setVisibility("TEAM_DEATHMATCH", DamageNumberVisibility.ALL);
        DamageNumberModePolicy restored = new DamageNumberModePolicy();
        restored.fromJson(policy.toJson());

        assertEquals(DamageNumberVisibility.TEAMMATES,
                restored.getVisibilityFor(DamageNumberModePolicy.ZOMBIE_MODE));
        assertEquals(DamageNumberVisibility.ALL, restored.getVisibilityFor("TEAM_DEATHMATCH"));
        assertFalse(restored.showsOwnDamage(DamageNumberModePolicy.ZOMBIE_MODE));
        assertTrue(restored.showsTeammateDamage(DamageNumberModePolicy.ZOMBIE_MODE));
        assertTrue(restored.showsOwnDamage("TEAM_DEATHMATCH"));
        assertTrue(restored.showsTeammateDamage("TEAM_DEATHMATCH"));
    }

    @Test
    void migratesLegacyOwnAndIncludeTeammateSettings() {
        JsonObject ownLegacy = legacySettings(false);
        DamageNumberModePolicy own = new DamageNumberModePolicy();
        own.fromJson(ownLegacy);
        assertEquals(DamageNumberVisibility.OWN, own.getVisibilityFor(DamageNumberModePolicy.ZOMBIE_MODE));

        JsonObject allLegacy = legacySettings(true);
        DamageNumberModePolicy all = new DamageNumberModePolicy();
        all.fromJson(allLegacy);
        assertEquals(DamageNumberVisibility.ALL, all.getVisibilityFor(DamageNumberModePolicy.ZOMBIE_MODE));

        JsonObject disabledLegacy = new JsonObject();
        disabledLegacy.add("enabledModes", new JsonArray());
        DamageNumberModePolicy disabled = new DamageNumberModePolicy();
        disabled.fromJson(disabledLegacy);
        assertEquals(DamageNumberVisibility.OFF,
                disabled.getVisibilityFor(DamageNumberModePolicy.ZOMBIE_MODE));
    }

    @Test
    void clampsSliderLevelsToFourDefinedScopes() {
        assertEquals(DamageNumberVisibility.OFF, DamageNumberVisibility.fromLevel(-4));
        assertEquals(DamageNumberVisibility.OWN, DamageNumberVisibility.fromLevel(1.2));
        assertEquals(DamageNumberVisibility.TEAMMATES, DamageNumberVisibility.fromLevel(1.8));
        assertEquals(DamageNumberVisibility.ALL, DamageNumberVisibility.fromLevel(99));
    }

    private static JsonObject legacySettings(boolean includeTeammates) {
        JsonArray enabled = new JsonArray();
        enabled.add(DamageNumberModePolicy.ZOMBIE_MODE);
        JsonObject json = new JsonObject();
        json.add("enabledModes", enabled);
        if (includeTeammates) {
            JsonArray teammates = new JsonArray();
            teammates.add(DamageNumberModePolicy.ZOMBIE_MODE);
            json.add("teammateDamageModes", teammates);
        }
        return json;
    }
}
