package cs2d.server.rl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import cs2d.server.GameState;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A lightweight HTTP server that acts as a bridge between the Python RLlib
 * trainer
 * and the Java backend. Implements the CTDE MARL pattern communication.
 */
public class RLBridgeService {
    private final GameState gameState;
    private HttpServer server;
    private final Gson gson = new GsonBuilder().create();

    // Centralized mailbox for incoming neural network decisions
    private final ConcurrentHashMap<String, RLMacroCommand> rlActionMailbox;

    public RLBridgeService(GameState gameState, ConcurrentHashMap<String, RLMacroCommand> rlActionMailbox) {
        this.gameState = gameState;
        this.rlActionMailbox = rlActionMailbox;
    }

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/step", new StepHandler());
            // [新增] 用于提供地图尺寸边界信息
            server.createContext("/map_info", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    // GameState的宽高在初始化大地图时确定 (如果没有public访问，可以在GameState里加getter或直接包内访问)
                    String response = String.format("{ \"width\": %d, \"height\": %d }",
                            gameState.width, gameState.height);
                    exchange.sendResponseHeaders(200, response.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                }
            });
            // Fast executor for HTTP requests
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            System.out.println("[RLBridge] Started RL Python CTDE Bridge on port " + port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
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
            if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody())) {
                    Type type = new TypeToken<Map<String, RLMacroCommand>>() {
                    }.getType();
                    Map<String, RLMacroCommand> incomingActions = gson.fromJson(reader, type);

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

                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.getBytes().length);

                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
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
}
