package cs2d.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameClientProtocolTest {
    @Test
    void droppedItemsArePublishedAsOneCompleteImmutableSnapshot() {
        JsonArray incoming = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("id", "weapon-1");
        first.addProperty("name", "AK47");
        incoming.add(first);

        Map<String, JsonObject> snapshot = GameClient.createDroppedItemSnapshot(incoming);
        JsonObject second = new JsonObject();
        second.addProperty("id", "weapon-2");
        incoming.add(second);

        assertEquals(1, snapshot.size());
        assertEquals("AK47", snapshot.get("weapon-1").get("name").getAsString());
        assertFalse(snapshot.containsKey("weapon-2"));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void fovOptimizationPreservesRayPrecisionAndIntersectionGeometry() {
        assertEquals(1696, GameClient.FOV_RAY_COUNT);

        double distance = GameClient.raySegmentIntersectionDistance(
                0, 0, 1, 0, 100,
                10, -5, 10, 5);
        assertEquals(10.0, distance, 1.0e-9);
        assertTrue(Double.isInfinite(GameClient.raySegmentIntersectionDistance(
                0, 0, 1, 0, 100,
                -10, -5, -10, 5)));

        List<Point2D[]> edges = Collections.singletonList(new Point2D[] {
                new Point2D(1, 2), new Point2D(3, 4)
        });
        GameClient.StaticObstacle obstacle = new GameClient.StaticObstacle(
                new JsonObject(), new Rectangle2D(1, 2, 2, 2), edges);
        assertEquals(4, obstacle.edgeCoordinates.length);
        assertEquals(1.0, obstacle.edgeCoordinates[0]);
        assertEquals(4.0, obstacle.edgeCoordinates[3]);
    }

    @Test
    void conservativeFovFilterRejectsOnlyImpossibleObstacles() {
        double halfFov = Math.toRadians(53);
        assertTrue(GameClient.obstacleMayIntersectFov(
                new Rectangle2D(100, -10, 20, 20), 0, 0, 0, halfFov, 8000));
        assertFalse(GameClient.obstacleMayIntersectFov(
                new Rectangle2D(-120, -10, 20, 20), 0, 0, 0, halfFov, 8000));
        assertTrue(GameClient.obstacleMayIntersectFov(
                new Rectangle2D(-5, -5, 10, 10), 0, 0, 0, halfFov, 8000));
        assertFalse(GameClient.obstacleMayIntersectFov(
                new Rectangle2D(9000, -10, 20, 20), 0, 0, 0, halfFov, 8000));

        double step = Math.toRadians(106.0) / (GameClient.FOV_RAY_COUNT - 1);
        long centeredRange = GameClient.fovRayIndexRange(
                new Rectangle2D(100, -10, 20, 20), 0, 0, 0,
                halfFov, step, GameClient.FOV_RAY_COUNT, 8000);
        assertTrue(centeredRange >= 0);
        assertTrue((int) (centeredRange >>> 32) <= GameClient.FOV_RAY_COUNT / 2);
        assertTrue((int) centeredRange >= GameClient.FOV_RAY_COUNT / 2);
        assertEquals(-1L, GameClient.fovRayIndexRange(
                new Rectangle2D(-120, -10, 20, 20), 0, 0, 0,
                halfFov, step, GameClient.FOV_RAY_COUNT, 8000));

        long surroundingRange = GameClient.fovRayIndexRange(
                new Rectangle2D(-5, -5, 10, 10), 0, 0, 0,
                halfFov, step, GameClient.FOV_RAY_COUNT, 8000);
        assertEquals(0, (int) (surroundingRange >>> 32));
        assertEquals(GameClient.FOV_RAY_COUNT - 1, (int) surroundingRange);
    }

    @Test
    void precomputedFovDirectionsMatchDirectTrigonometry() {
        double sourceAngle = 1.2345;
        double[] directionsX = new double[GameClient.FOV_RAY_COUNT];
        double[] directionsY = new double[GameClient.FOV_RAY_COUNT];
        GameClient.populateFovRayDirections(sourceAngle, directionsX, directionsY);

        double fov = Math.toRadians(106.0);
        double step = fov / (GameClient.FOV_RAY_COUNT - 1);
        for (int i = 0; i < GameClient.FOV_RAY_COUNT; i += 127) {
            double directAngle = sourceAngle - fov * 0.5 + i * step;
            assertEquals(Math.cos(directAngle), directionsX[i], 1.0e-12);
            assertEquals(Math.sin(directAngle), directionsY[i], 1.0e-12);
        }
    }

    @Test
    void primitiveFovSimplificationMatchesLegacyPointAlgorithm() {
        int rayCount = GameClient.FOV_RAY_COUNT;
        Point2D source = new Point2D(321.25, 654.75);
        double[] directionsX = new double[rayCount];
        double[] directionsY = new double[rayCount];
        double[] distances = new double[rayCount];
        GameClient.populateFovRayDirections(-0.731, directionsX, directionsY);
        List<Point2D> raw = new java.util.ArrayList<>(rayCount + 1);
        raw.add(source);
        for (int i = 0; i < rayCount; i++) {
            distances[i] = 600 + (i / 37 % 5) * 140 + Math.sin(i * 0.17) * 3;
            raw.add(new Point2D(source.getX() + directionsX[i] * distances[i],
                    source.getY() + directionsY[i] * distances[i]));
        }

        double tolerance = Math.toRadians(1.0);
        List<Point2D> expected = legacySimplify(raw, tolerance);
        List<Point2D> actual = GameClient.simplifyFovRays(
                source, directionsX, directionsY, distances, tolerance);

        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getX(), actual.get(i).getX(), 1.0e-9);
            assertEquals(expected.get(i).getY(), actual.get(i).getY(), 1.0e-9);
        }
    }

    @Test
    void equipmentHudSignatureChangesOnlyWhenVisibleEquipmentStateChanges() {
        JsonObject player = new JsonObject();
        player.addProperty("team", "CT");
        player.addProperty("currentSlot", 1);
        player.addProperty("hasKevlar", true);
        player.addProperty("hasHelmet", false);
        player.addProperty("hasDefuseKit", true);
        JsonArray equipment = new JsonArray();
        JsonObject flashbang = new JsonObject();
        flashbang.addProperty("name", "FLASHBANG");
        flashbang.addProperty("count", 2);
        equipment.add(flashbang);
        player.add("equipment", equipment);

        String initial = GameClient.createEquipmentHudSignature(player);
        JsonObject unrelatedChange = player.deepCopy();
        unrelatedChange.addProperty("health", 37);
        assertEquals(initial, GameClient.createEquipmentHudSignature(unrelatedChange));

        JsonObject selectedItemChange = player.deepCopy();
        selectedItemChange.addProperty("currentSlot", 6);
        assertFalse(initial.equals(GameClient.createEquipmentHudSignature(selectedItemChange)));

        JsonObject countChange = player.deepCopy();
        countChange.getAsJsonArray("equipment").get(0).getAsJsonObject().addProperty("count", 1);
        assertFalse(initial.equals(GameClient.createEquipmentHudSignature(countChange)));
    }

    @Test
    void residentStaticMapTransformMatchesCanvasCameraTransform() {
        double cameraOrigin = 125.5;
        double scale = 0.75;
        double offset = 42.0;
        double worldCoordinate = 900.0;

        double nodeScreenCoordinate = worldCoordinate * scale
                + GameClient.staticMapLayerTranslation(cameraOrigin, scale, offset);
        double canvasScreenCoordinate = (worldCoordinate - cameraOrigin) * scale + offset;

        assertEquals(canvasScreenCoordinate, nodeScreenCoordinate, 0.000_000_1);
    }

    @Test
    void residentStaticMapCullingKeepsOnlyIntersectingTiles() {
        assertTrue(GameClient.boundsIntersect(0, 0, 1024, 1024,
                900, 500, 2500, 1400));
        assertTrue(GameClient.boundsIntersect(0, 0, 1024, 1024,
                1024, 1024, 1600, 1400));
        assertFalse(GameClient.boundsIntersect(0, 0, 1024, 1024,
                1025, 0, 1600, 900));
        assertFalse(GameClient.boundsIntersect(0, 0, 1024, 1024,
                0, 1025, 900, 1600));
    }

    @Test
    void dropWeaponIsSentOnlyOnFirstPressInSupportedModes() {
        assertTrue(GameClient.shouldSendDropWeapon("DEMOLITION", true));
        assertTrue(GameClient.shouldSendDropWeapon("TEAM_DEATHMATCH", true));
        assertFalse(GameClient.shouldSendDropWeapon("TEAM_DEATHMATCH", false));
        assertFalse(GameClient.shouldSendDropWeapon("DEATHMATCH", true));
        assertFalse(GameClient.shouldSendDropWeapon(null, true));
    }

    private static List<Point2D> legacySimplify(List<Point2D> points, double angleTolerance) {
        List<Point2D> simplified = new java.util.ArrayList<>();
        simplified.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            Point2D previous = simplified.get(simplified.size() - 1);
            Point2D current = points.get(i);
            Point2D next = points.get(i + 1);
            double dx1 = current.getX() - previous.getX();
            double dy1 = current.getY() - previous.getY();
            if (Math.abs(dx1) < 0.1 && Math.abs(dy1) < 0.1)
                continue;
            double angle1 = Math.atan2(dy1, dx1);
            double angle2 = Math.atan2(next.getY() - current.getY(), next.getX() - current.getX());
            double difference = Math.abs(angle1 - angle2);
            if (difference > Math.PI)
                difference = Math.PI * 2.0 - difference;
            if (difference > angleTolerance)
                simplified.add(current);
        }
        simplified.add(points.get(points.size() - 1));
        return simplified;
    }

    @Test
    void duplicateMapPayloadsHaveTheSameSignature() {
        JsonObject map = new JsonObject();
        map.addProperty("sessionId", "session-a");
        map.addProperty("width", 4392);
        map.addProperty("height", 3840);
        JsonArray obstacles = new JsonArray();
        JsonObject obstacle = new JsonObject();
        obstacle.addProperty("type", "RECTANGLE");
        obstacle.addProperty("x", 10);
        obstacle.addProperty("y", 20);
        obstacle.addProperty("w", 30);
        obstacle.addProperty("h", 40);
        obstacles.add(obstacle);
        map.add("obstacles", obstacles);

        JsonObject changed = map.deepCopy();
        changed.getAsJsonArray("obstacles").get(0).getAsJsonObject().addProperty("x", 11);

        assertEquals(GameClient.createMapSignature(map), GameClient.createMapSignature(map.deepCopy()));
        assertFalse(GameClient.createMapSignature(map).equals(GameClient.createMapSignature(changed)));
        assertTrue(GameClient.OBSTACLE_CACHE_TILE_SIZE <= 4096);
        assertTrue(GameClient.OBSTACLE_OVERVIEW_MAX_SIZE <= 4096);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void gameOverLocksTheSharedScoreboardInFinalMode() throws Exception {
        Class<? extends Enum> stateClass = (Class<? extends Enum>) Class
                .forName("cs2d.client.GameClient$ClientState");
        Method finalState = GameClient.class.getDeclaredMethod("isFinalScoreboardState", stateClass);
        finalState.setAccessible(true);

        Object playing = Enum.valueOf(stateClass, "PLAYING");
        Object gameOver = Enum.valueOf(stateClass, "GAME_OVER");
        assertFalse((boolean) finalState.invoke(null, playing));
        assertTrue((boolean) finalState.invoke(null, gameOver));
    }

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

    @Test
    void controlBotRequestOmitsBlankSpectatorTarget() throws Exception {
        GameClient client = new GameClient();
        Method request = GameClient.class.getDeclaredMethod("createControlBotRequest", String.class);
        request.setAccessible(true);
        Gson gson = new Gson();

        JsonObject blank = gson.fromJson((String) request.invoke(client, ""), JsonObject.class);
        JsonObject explicit = gson.fromJson((String) request.invoke(client, "bot-7"), JsonObject.class);

        assertEquals("requestControlBot", blank.get("type").getAsString());
        assertFalse(blank.has("targetId"));
        assertEquals("bot-7", explicit.get("targetId").getAsString());
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
