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
        assertFalse(policy.isTeammateDamageEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));

        policy.setEnabled("TEAM_DEATHMATCH", true);
        policy.setTeammateDamageEnabled(DamageNumberModePolicy.ZOMBIE_MODE, true);
        policy.setTeammateDamageEnabled("TEAM_DEATHMATCH", true);
        DamageNumberModePolicy restored = new DamageNumberModePolicy();
        restored.fromJson(policy.toJson());

        assertTrue(restored.isEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));
        assertTrue(restored.isEnabledFor("TEAM_DEATHMATCH"));
        assertTrue(restored.isTeammateDamageEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));
        assertTrue(restored.isTeammateDamageEnabledFor("TEAM_DEATHMATCH"));
    }

    @Test
    void explicitEmptyModeListDisablesAllModes() {
        DamageNumberModePolicy policy = new DamageNumberModePolicy();
        policy.setTeammateDamageEnabled(DamageNumberModePolicy.ZOMBIE_MODE, true);
        JsonObject json = new JsonObject();
        json.add("enabledModes", new JsonArray());
        policy.fromJson(json);
        assertFalse(policy.isEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));
        // Older settings files do not contain teammateDamageModes and must default to off.
        assertFalse(policy.isTeammateDamageEnabledFor(DamageNumberModePolicy.ZOMBIE_MODE));
    }
}
