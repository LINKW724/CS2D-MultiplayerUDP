package cs2d.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameClientProtocolTest {
    @Test
    void rejectsOutOfOrderAndForeignSessionSnapshots() throws Exception {
        GameClient client = new GameClient();
        setField(client, "serverSessionId", "session-a");
        Method accept = GameClient.class.getDeclaredMethod("acceptStatePacket", JsonObject.class);
        accept.setAccessible(true);

        assertTrue((boolean) accept.invoke(client, state("session-a", 10)));
        assertFalse((boolean) accept.invoke(client, state("session-a", 9)));
        assertFalse((boolean) accept.invoke(client, state("session-a", 10)));
        assertFalse((boolean) accept.invoke(client, state("session-b", 11)));
        assertTrue((boolean) accept.invoke(client, state("session-a", 11)));
    }

    @Test
    void acceptsPacketsOnlyFromConfiguredServerEndpoint() throws Exception {
        GameClient client = new GameClient();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        setField(client, "serverAddress", new InetSocketAddress(loopback, 14726));
        Method expectedServer = GameClient.class.getDeclaredMethod("isExpectedServer", DatagramPacket.class);
        expectedServer.setAccessible(true);

        DatagramPacket valid = new DatagramPacket(new byte[1], 1, loopback, 14726);
        DatagramPacket wrongPort = new DatagramPacket(new byte[1], 1, loopback, 14727);
        DatagramPacket wrongAddress = new DatagramPacket(new byte[1], 1,
                InetAddress.getByName("127.0.0.2"), 14726);

        assertTrue((boolean) expectedServer.invoke(client, valid));
        assertFalse((boolean) expectedServer.invoke(client, wrongPort));
        assertFalse((boolean) expectedServer.invoke(client, wrongAddress));
    }

    @Test
    void playerUpdatesPublishANewJsonSnapshot() throws Exception {
        GameClient client = new GameClient();
        Class<?> playerClass = Class.forName("cs2d.client.GameClient$ClientPlayer");
        Constructor<?> constructor = playerClass.getDeclaredConstructor(JsonObject.class, GameClient.class);
        constructor.setAccessible(true);
        JsonObject full = new JsonObject();
        full.addProperty("id", "p1");
        full.addProperty("x", 10);
        full.addProperty("y", 20);
        full.addProperty("vx", 1);
        full.addProperty("vy", 2);
        full.addProperty("health", 100);
        full.addProperty("angle", 0);
        Object player = constructor.newInstance(full, client);

        Field dataField = playerClass.getDeclaredField("data");
        dataField.setAccessible(true);
        JsonObject before = (JsonObject) dataField.get(player);
        Method update = playerClass.getDeclaredMethod("updateDynamic", JsonObject.class);
        update.setAccessible(true);
        JsonObject small = new JsonObject();
        small.addProperty("vx", 5);
        update.invoke(player, small);
        JsonObject after = (JsonObject) dataField.get(player);

        assertNotSame(before, after);
        assertEquals(1, before.get("vx").getAsInt());
        assertEquals(5, after.get("vx").getAsInt());
    }

    @Test
    void smallStateKeepsFullStateContextWithoutOverwritingNewValues() {
        JsonObject full = new JsonObject();
        full.addProperty("mode", "DEMOLITION");
        full.addProperty("roundPhase", "IN_PROGRESS");
        full.addProperty("bombTimer", 30_000);

        JsonObject small = new JsonObject();
        small.addProperty("bombTimer", 29_500);
        JsonObject merged = GameClient.mergeMissingStateFields(full, small);

        assertEquals("DEMOLITION", merged.get("mode").getAsString());
        assertEquals("IN_PROGRESS", merged.get("roundPhase").getAsString());
        assertEquals(29_500, merged.get("bombTimer").getAsInt());
    }

    private static JsonObject state(String sessionId, long sequence) {
        JsonObject state = new JsonObject();
        state.addProperty("protocolVersion", 2);
        state.addProperty("sessionId", sessionId);
        state.addProperty("sequence", sequence);
        return state;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
