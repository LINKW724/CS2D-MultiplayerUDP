package cs2d.server.rl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import cs2d.server.GameState;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A lightweight HTTP server that acts as a bridge between the Python RLlib
 * trainer
 * and the Java backend. Implements the CTDE MARL pattern communication.
 */
public class RLBridgeService {
    private final GameState gameState;
    private HttpServer server;
    private ExecutorService executor;
    private final Gson gson = new GsonBuilder().create();
    private final String bindHost;
    private final String authToken;
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    // Centralized mailbox for incoming neural network decisions
    private final ConcurrentHashMap<String, RLMacroCommand> rlActionMailbox;

    public RLBridgeService(GameState gameState, ConcurrentHashMap<String, RLMacroCommand> rlActionMailbox,
            String bindHost, String authToken) {
        this.gameState = gameState;
        this.rlActionMailbox = rlActionMailbox;
        this.bindHost = bindHost;
        this.authToken = authToken == null || authToken.isBlank() ? null : authToken;
    }

    public void start(int port) {
        try {
            InetAddress bindAddress = InetAddress.getByName(bindHost);
            if (!bindAddress.isLoopbackAddress() && authToken == null) {
                throw new IllegalStateException("远程 RL 监听必须配置 cs2d.rl.token 或 CS2D_RL_TOKEN");
            }
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            server.createContext("/step", new StepHandler());
            // [新增] 用于提供地图尺寸边界信息
            server.createContext("/map_info", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    if (!authorize(exchange))
                        return;
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(405, -1);
                        exchange.close();
                        return;
                    }
                    // GameState的宽高在初始化大地图时确定 (如果没有public访问，可以在GameState里加getter或直接包内访问)
                    String response = String.format("{ \"width\": %d, \"height\": %d }",
                            gameState.width, gameState.height);
                    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(responseBytes);
                    os.close();
                }
            });
            // Fast executor for HTTP requests
            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            server.start();
            System.out.println("[RLBridge] Started RL Python CTDE Bridge on " + bindHost + ":" + port);
        } catch (IOException | IllegalStateException e) {
            System.err.println("[RLBridge] Failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null)
            executor.shutdownNow();
    }

    public RLMacroCommand getCommandForAgent(String agentId) {
        return rlActionMailbox.get(agentId);
    }

    /**
     * Endpoint called uniformly by Python agent.
     * Expects a JSON dictionary of Agent Actions, returns a JSON dictionary of
     * Environment Observations.
     */
    class StepHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!authorize(exchange))
                return;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    String requestBody = readLimitedBody(exchange.getRequestBody());
                    Type type = new TypeToken<Map<String, RLMacroCommand>>() {
                    }.getType();
                    Map<String, RLMacroCommand> incomingActions = gson.fromJson(requestBody, type);

                    if (incomingActions != null) {
                        rlActionMailbox.putAll(incomingActions);
                    }

                    // Build RLEnvironmentState for all alive AI agents
                    Map<String, RLEnvironmentState> observations = new java.util.HashMap<>();

                    // Extract Bomb coordinates and status for the entire snapshot
                    String roundWinnerStr = gameState.getRoundPhase() == GameState.RoundPhase.ROUND_OVER
                            && gameState.getRoundWinner() != null ? gameState.getRoundWinner().name() : "NULL";

                    double bsaX = -1, bsaY = -1, bsbX = -1, bsbY = -1;
                    if (gameState.getBombSiteA() != null) {
                        bsaX = gameState.getBombSiteA().getCenterX();
                        bsaY = gameState.getBombSiteA().getCenterY();
                    }
                    if (gameState.getBombSiteB() != null) {
                        bsbX = gameState.getBombSiteB().getCenterX();
                        bsbY = gameState.getBombSiteB().getCenterY();
                    }

                    // Determine C4 State
                    String c4State = "NONE";
                    double c4X = -1, c4Y = -1;

                    if (gameState.isBombPlanted()) {
                        c4State = "PLANTED";
                        if (gameState.getBombPosition() != null) {
                            c4X = gameState.getBombPosition().x;
                            c4Y = gameState.getBombPosition().y;
                        }
                    } else if (gameState.getDroppedBomb() != null) {
                        c4State = "DROPPED";
                        c4X = gameState.getDroppedBomb().position.x;
                        c4Y = gameState.getDroppedBomb().position.y;
                    } else {
                        // Find if anyone is carrying it
                        for (cs2d.playerAndAi.Player pCarrying : gameState.getAllCharacters()) {
                            if (pCarrying != null && pCarrying.hasBomb && pCarrying.isAlive()) {
                                c4State = "CARRIED";
                                c4X = pCarrying.position.x;
                                c4Y = pCarrying.position.y;
                                break;
                            }
                        }
                    }

                    for (cs2d.playerAndAi.Player p : gameState.getAllCharacters()) {
                        if (p != null && p.isAI) {
                            // Basic stats
                            String weaponId = p.getCurrentWeapon() != null ? p.getCurrentWeapon().name() : "NONE";

                            // Mocking the perception and radio comms for now, to be strictly wired in
                            // AIService
                            java.util.List<cs2d.server.AIService.PerceivedPlayer> perceived = new java.util.ArrayList<>();
                            java.util.List<RLEnvironmentState.RadioSignal> comms = new java.util.ArrayList<>();

                            RLEnvironmentState state = new RLEnvironmentState(
                                    p.money, // team money mock
                                    0, // loss bonus mock
                                    p.health,
                                    p.armorValue,
                                    p.hasHelmet,
                                    weaponId,
                                    p.kills,
                                    p.deaths,
                                    p.damageDealt,
                                    p.isAlive(),
                                    p.score,
                                    p.team.name(),
                                    p.position.x, p.position.y,
                                    bsaX, bsaY,
                                    bsbX, bsbY,
                                    c4X, c4Y,
                                    c4State,
                                    p.hasBomb,
                                    roundWinnerStr,
                                    perceived,
                                    comms);

                            observations.put(p.id, state);
                        }
                    }

                    String response = gson.toJson(observations);

                    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);

                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }
                } catch (RequestTooLargeException e) {
                    exchange.sendResponseHeaders(413, -1);
                    exchange.close();
                } catch (Exception e) {
                    System.err.println("[RLBridge] Malformed JSON from RL agent.");
                    exchange.sendResponseHeaders(400, 0);
                    exchange.getResponseBody().close();
                }
            } else {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            }
        }
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        if (authToken == null)
            return true;
        String supplied = exchange.getRequestHeaders().getFirst("X-CS2D-RL-Token");
        if (supplied == null) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith("Bearer "))
                supplied = authorization.substring(7);
        }
        boolean valid = supplied != null && MessageDigest.isEqual(authToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        }
        return valid;
    }

    private static String readLimitedBody(InputStream input) throws IOException, RequestTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_REQUEST_BYTES)
                throw new RequestTooLargeException();
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class RequestTooLargeException extends Exception {
    }
}
