package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class DamageNumberModePolicyTest {
    @Test
    void defaultsToZombieModeOnlyAndRoundTripsFutureModes() {
        DamageNumberModePolicy policy = new DamageNumberModePolicy();
        assertTrue(policy.isEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));
        assertFalse(policy.isEnabledFor("TEAM_DEATHMATCH"));

        policy.setEnabled("TEAM_DEATHMATCH", true);
        DamageNumberModePolicy restored = new DamageNumberModePolicy();
        restored.fromJson(policy.toJson());

        assertTrue(restored.isEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));
        assertTrue(restored.isEnabledFor("TEAM_DEATHMATCH"));
    }

    @Test
    void explicitEmptyModeListDisablesAllModes() {
        DamageNumberModePolicy policy = new DamageNumberModePolicy();
        JsonObject json = new JsonObject();
        json.add("enabledModes", new JsonArray());
        policy.fromJson(json);
        assertFalse(policy.isEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));
    }
}
