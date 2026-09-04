package cs2d.playerAndAi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class DamageLogEntryTest {
    @Test
    void serializesAuthoritativeDamageFeedbackFields() {
        Player.DamageLogEntry entry = new Player.DamageLogEntry(
                "attacker-1", "zombie-4", 37, 1234L, false, true, 1250.5, 830.0,
                CombatFeedbackKind.NORMAL);

        JsonObject json = entry.toJson();

        assertEquals("damage_event", json.get("type").getAsString());
        assertEquals("attacker-1", json.get("atk").getAsString());
        assertEquals("zombie-4", json.get("vic").getAsString());
        assertEquals(37, json.get("dmg").getAsInt());
        assertEquals(1234L, json.get("ts").getAsLong());
        assertFalse(json.get("kill").getAsBoolean());
        assertTrue(json.get("hs").getAsBoolean());
        assertEquals(1250.5, json.get("hitX").getAsDouble());
        assertEquals(830.0, json.get("hitY").getAsDouble());
        assertEquals("NORMAL", json.get("feedbackKind").getAsString());
    }
}
