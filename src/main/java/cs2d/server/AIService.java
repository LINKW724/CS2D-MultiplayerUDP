// 文件: cs2d/server/AIService.java
package cs2d.server;

import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.BG.TEAM_DEATHMATCHcontrol;
import cs2d.AIControl.BG.ZOMBIEcontrol;
import cs2d.playerAndAi.Player;

import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 一个独立的AI服务，以"客户端"模式运行AI逻辑。
 */
public class AIService implements Runnable {
    public enum PerceptionReason {
        SIGHT, SOUND, GUNSHOT, PAIN, MEMORY
    }

    public record PerceivedPlayer(Player.PlayerSnapshot snapshot, PerceptionReason reason) {
    }

    public record AIWorldView(List<PerceivedPlayer> players) {
    }

    public record AIInput(List<String> keys, double angle, boolean shooting, boolean isRequestingInteraction,
            boolean walking) {
    }

    private final GameState gameState;
    private final ExecutorService aiThreadPool;
    private final ConcurrentHashMap<String, AIInput> aiInputMailbox;
    private final ConcurrentHashMap<String, cs2d.server.rl.RLMacroCommand> rlMacroMailbox;
    private volatile boolean running = false;
    private boolean freezeCleanupApplied = false;
    private final int aiTps;

    private final Consumer<String> logger;

    // ID -> (TargetID -> Snapshot)
    private final ConcurrentHashMap<String, Map<String, PerceivedPlayer>> aiShortTermMemory = new ConcurrentHashMap<>();
    // ID -> (TargetID -> LastSeenTime)
    private final ConcurrentHashMap<String, Map<String, Long>> aiPerceptionTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastDropRequestTimes = new ConcurrentHashMap<>();
    private static final long MEMORY_EXPIRY_MS = 5000;

    public AIService(GameState gameState, ConcurrentHashMap<String, AIInput> aiInputMailbox,
            ConcurrentHashMap<String, cs2d.server.rl.RLMacroCommand> rlMacroMailbox, int threadCount, int aiTps,
            Consumer<String> logger) {
        this.gameState = gameState;
        this.aiThreadPool = Executors.newFixedThreadPool(threadCount);
        this.aiInputMailbox = aiInputMailbox;
        this.rlMacroMailbox = rlMacroMailbox;
        this.aiTps = aiTps;
        this.logger = logger;
    }

    public void start() {
        this.running = true;
        // [新增] 清空残留状态，确保重启后干净
        aiShortTermMemory.clear();
        aiPerceptionTimestamps.clear();
        new Thread(this).start();
    }

    public void stop() {
        this.running = false;
        this.aiThreadPool.shutdownNow();
    }

    @Override
    public void run() {
        long lastTime = System.nanoTime();
        double nsPerTick = 1_000_000_000.0 / aiTps;
        double delta = 0;

        while (running) {
            long now = System.nanoTime();
            delta += (now - lastTime) / nsPerTick;
            lastTime = now;

            if (delta >= 1) {
                updateAIs();
                delta--;
            }

            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private void updateAIs() {
        if (gameState == null)
            return;
        long currentTime = System.currentTimeMillis();
        GameMode currentMode = gameState.getGameMode();

        if (gameState.shouldFreezeAi()) {
            List<Player> allAIs = gameState.getAllCharacters().stream()
                    .filter(p -> p != null && p.isAI && !p.isControlledByPlayer())
                    .collect(Collectors.toList());
            if (!freezeCleanupApplied) {
                for (Player ai : allAIs) {
                    TEAM_DEATHMATCHcontrol tdmController = ai.getTdmController();
                    if (tdmController != null)
                        tdmController.cancelPendingActions();
                    ZOMBIEcontrol zombieController = ai.getZombieController();
                    if (zombieController != null)
                        zombieController.cancelPendingActions();
                }
                aiShortTermMemory.clear();
                aiPerceptionTimestamps.clear();
                freezeCleanupApplied = true;
            }
            for (Player ai : allAIs) {
                aiInputMailbox.put(ai.id, neutralInput(ai));
            }
            return;
        }
        freezeCleanupApplied = false;

        // --- 1. 获取并刷新本 Tick 的声音信息 (所有 AI 共用) ---
        List<SoundEvent> sounds = gameState.getAndClearAiSoundEvents();
        List<SoundEvent> history = gameState.getAiSoundHistory();
        history.clear();
        history.addAll(sounds);

        // [新增] 核心修复：清理所有已死亡 AI 的残留记忆
        // 必须在处理活跃 AI 之前进行，确保它们复活瞬间记忆是空的
        Set<String> aliveAiIds = gameState.getAllCharacters().stream()
                .filter(p -> p != null && p.isAI && p.isAlive())
                .map(p -> p.id)
                .collect(Collectors.toSet());

        // 遍历 memory 中的所有 ID，如果它不在活着且是 AI 的集合中，则彻底移除
        aiShortTermMemory.keySet().removeIf(id -> !aliveAiIds.contains(id));
        aiPerceptionTimestamps.keySet().removeIf(id -> !aliveAiIds.contains(id));

        // 基础权威快照
        List<Player.PlayerSnapshot> authoritativeSnapshots = gameState.getAllCharacters().stream()
                .filter(Objects::nonNull)
                .map(Player::createSnapshot)
                .collect(Collectors.toList());

        List<Player> independentAIs = gameState.getAllCharacters().stream()
                .filter(p -> p != null && p.isAI && p.isAlive() && !p.isControlledByPlayer())
                .collect(Collectors.toList());

        for (Player ai : independentAIs) {
            if (aiThreadPool.isShutdown())
                break;

            // [新增] 如果 AI 已经死亡，清空其短期记忆，防止复活后立即攻击“记忆中”的敌人
            if (!ai.isAlive()) {
                aiShortTermMemory.remove(ai.id);
                aiPerceptionTimestamps.remove(ai.id);
                continue;
            }

            aiThreadPool.submit(() -> {
                try {
                    if (gameState.shouldFreezeAi()) {
                        aiInputMailbox.put(ai.id, neutralInput(ai));
                        return;
                    }
                    AIWorldView perception;
                    if (ai.getDifficulty() == AIDifficulty.REALISTIC) {
                        perception = processRealisticPerception(ai, authoritativeSnapshots, currentTime);
                    } else {
                        // 非真实难度直接看全图
                        List<PerceivedPlayer> all = authoritativeSnapshots.stream()
                                .map(s -> new PerceivedPlayer(s, PerceptionReason.SIGHT))
                                .collect(Collectors.toList());
                        perception = new AIWorldView(all);
                    }

                    AIInput finalInput = null;

                    if (currentMode == GameMode.TEAM_DEATHMATCH || currentMode == GameMode.DEATHMATCH) {
                        TEAM_DEATHMATCHcontrol tdmController = ai.getTdmController();
                        if (tdmController != null) {
                            finalInput = tdmController.update(perception, currentTime);
                        }
                    } else if (currentMode == GameMode.ZOMBIE_MODE) {
                        ZOMBIEcontrol zombieController = ai.getZombieController();
                        if (zombieController != null) {
                            finalInput = zombieController.update(perception, currentTime);
                        }
                    } else if (currentMode == GameMode.DEMOLITION) {
                        // [NEW] Basic fallback for Demolition mode managed by RL
                    }

                    // --- 🧠 介入点: RL Python 脑机接口强行接管 ---
                    cs2d.server.rl.RLMacroCommand macro = rlMacroMailbox.get(ai.id);
                    if (macro != null) {
                        PathfindingModule pathModule = ai.getPathfindingModule();
                        if (pathModule != null) {
                            if (macro.navTarget() != null && (macro.navTarget().x >= 0 && macro.navTarget().y >= 0)) {
                                boolean commitNewTarget = false;

                                // Event 1: 1秒周期承诺到期 (Frame Commitment Cooldown)
                                if (currentTime - ai.lastMissionBufferApplyTime > 1000) {
                                    commitNewTarget = true;
                                }
                                // Event 2: 感知到敌人 (Sight/Sound)
                                else if (!perception.players().isEmpty()) {
                                    for (PerceivedPlayer pp : perception.players()) {
                                        Player target = gameState.getPlayerById(pp.snapshot().id());
                                        if (target != null && target.isAlive() && target.team != ai.team) {
                                            commitNewTarget = true;
                                            break;
                                        }
                                    }
                                }
                                // Event 3: 受到伤害惊心 (Damage Event)
                                else if (currentTime
                                        - (ai.lastDamageSource != null ? ai.lastDamageSourcePositionTime : 0) < 2000) {
                                    commitNewTarget = true;
                                }

                                if (commitNewTarget) {
                                    pathModule.setTarget(macro.navTarget());
                                    ai.lastMissionBufferApplyTime = currentTime;
                                }
                            }
                            if (pathModule.isActive()) {
                                finalInput = pathModule.update(perception);
                            } else {
                                finalInput = new AIInput(new ArrayList<>(), ai.angle, false, false, false);
                            }
                        } else {
                            finalInput = new AIInput(new ArrayList<>(), ai.angle, false, false, false);
                        }

                        // [Fix] Fetch shooting decision from native AttackModule so the AI can shoot
                        // enemies automatically!
                        boolean shouldShoot = false;
                        cs2d.AIControl.A.AttackModule attackModule = ai.getAttackModule();
                        AIInput attackInput = null;
                        if (attackModule != null) {
                            // Find closest visible enemy
                            Player closestEnemy = null;
                            double minTargetDistSq = Double.MAX_VALUE;
                            for (PerceivedPlayer pp : perception.players()) {
                                Player target = gameState.getPlayerById(pp.snapshot().id());
                                if (target != null && target.isAlive() && target.team != ai.team) {
                                    double distSq = ai.position.distanceSq(target.position);
                                    if (distSq < minTargetDistSq) {
                                        minTargetDistSq = distSq;
                                        closestEnemy = target;
                                    }
                                }
                            }

                            java.awt.geom.Point2D.Double lkp = closestEnemy != null ? closestEnemy.position : null;
                            attackInput = attackModule.update(closestEnemy, lkp, currentTime);
                            if (attackInput != null) {
                                shouldShoot = attackInput.shooting();
                            }
                        }

                        // [拦截与覆盖]: 使用 RL 下发的高阶指令，覆盖 FSM 算出来的微操
                        boolean isWalking = macro.stealthMode();
                        boolean interact = macro.interact();

                        // 若指定了死锁视角 (Hold Angle)，则强行修改 angle
                        double newAngle = finalInput.angle();
                        if (macro.holdAngleTarget() != null && macro.holdAngleTarget().x >= 0) {
                            newAngle = Math.atan2(macro.holdAngleTarget().y - ai.position.y,
                                    macro.holdAngleTarget().x - ai.position.x);
                        }

                        // [执行 G 键战术] 只有在冻结时间才允许 RL 扔枪，防止在比赛中瞎丢
                        if (macro.dropItem()
                                && gameState.getRoundPhase() == cs2d.server.GameState.RoundPhase.FREEZE_TIME) {
                            // 这里可以发送给 GameState 让其丢弃。用一个小 hack 防止每帧连丢：
                            long now = System.currentTimeMillis();
                            long lastDrop = lastDropRequestTimes.getOrDefault(ai.id, 0L);
                            if (now - lastDrop > 1500) {
                                gameState.requestAiDropWeapon(ai.id);
                                lastDropRequestTimes.put(ai.id, now);
                            }
                        }

                        List<String> finalKeys = finalInput.keys();
                        if (attackInput != null && attackInput.keys() != null && !attackInput.keys().isEmpty()) {
                            // 如果交火状态下，让 FSM 接管走位以触发急停，否则会因为 RL 的持续移动指令导致精度不够永远无法开火
                            finalKeys = attackInput.keys();
                        }

                        // [核心修复] 如果 AttackModule 捕捉到敌人并准备射击，必须把瞄准角度 (Angle) 还给它！
                        // 否则 AI 会因为 RL 给的 navTarget 或 holdAngle 一直转圈，无法瞄准敌人！
                        if (attackInput != null && shouldShoot) {
                            newAngle = attackInput.angle();
                        }

                        finalInput = new AIInput(finalKeys, newAngle, shouldShoot, interact,
                                isWalking);
                    }

                    if (finalInput == null) {
                        PathfindingModule pathModule = ai.getPathfindingModule();
                        if (pathModule != null && pathModule.isActive()) {
                            finalInput = pathModule.update(perception);
                        } else {
                            finalInput = new AIInput(new ArrayList<>(), ai.angle, false, false, false);
                        }
                    }

                    // 任务提交后比赛可能已经结束；旧决策绝不能覆盖冻结输入。
                    if (gameState.shouldFreezeAi()) {
                        aiInputMailbox.put(ai.id, neutralInput(ai));
                    } else if (ai.getDifficulty() == AIDifficulty.REALISTIC) {
                        injectRealisticInput(ai, finalInput);
                    } else {
                        aiInputMailbox.put(ai.id, finalInput);
                    }

                } catch (Exception e) {
                    if (!gameState.shouldFreezeAi()) {
                        System.err.println("AI Service Error [" + ai.name + "]: " + e.getMessage());
                        e.printStackTrace();
                    }
                    aiInputMailbox.put(ai.id, neutralInput(ai));
                }
            });
        }

    }

    private AIInput neutralInput(Player ai) {
        return new AIInput(new ArrayList<>(), ai.angle, false, false, false);
    }

    /**
     * [核心] 处理真实感知逻辑
     */
    private AIWorldView processRealisticPerception(Player ai, List<Player.PlayerSnapshot> globalSnapshots,
            long currentTime) {
        List<PerceivedPlayer> currentlyPerceived = new ArrayList<>();

        // --- 1. 获取声音信息 (使用专门为 AI 准备的 100ms 缓存) ---
        List<SoundEvent> sounds = gameState.getAiSoundHistory();
        // ID -> Type (枪声优先)
        Map<String, PerceptionReason> noisyPlayers = new HashMap<>();
        for (SoundEvent s : sounds) {
            if (s.sourcePlayerId() != null && !s.sourcePlayerId().equals(ai.id)) {
                double distSq = ai.position.distanceSq(s.x(), s.y());
                if (s.type() == SoundEvent.SoundType.FIRE && distSq < 3000 * 3000) {
                    noisyPlayers.put(s.sourcePlayerId(), PerceptionReason.GUNSHOT);
                } else if (distSq < 1050 * 1050) {
                    noisyPlayers.putIfAbsent(s.sourcePlayerId(), PerceptionReason.SOUND);
                }
            }
        }

        // --- 2. 受击感知 ---
        boolean isAlertedByDamage = (currentTime - ai.lastDamageSourcePositionTime < 2000);

        Map<String, PerceivedPlayer> memory = aiShortTermMemory.computeIfAbsent(ai.id,
                k -> new java.util.concurrent.ConcurrentHashMap<>());
        Map<String, Long> timestamps = aiPerceptionTimestamps.computeIfAbsent(ai.id,
                k -> new java.util.concurrent.ConcurrentHashMap<>());

        for (Player.PlayerSnapshot p : globalSnapshots) {
            if (p.id().equals(ai.id)) {
                currentlyPerceived.add(new PerceivedPlayer(p, PerceptionReason.SIGHT));
                continue;
            }

            if (p.team() == ai.team) {
                currentlyPerceived.add(new PerceivedPlayer(p, PerceptionReason.SIGHT));
                continue;
            }

            PerceptionReason reason = null;

            // A. 视觉
            if (isVisibleToInternal(ai, p, isAlertedByDamage)) {
                reason = PerceptionReason.SIGHT;
            }
            // B. 枪声 (绝对锁定)
            else if (noisyPlayers.containsKey(p.id())) {
                reason = noisyPlayers.get(p.id());
            }
            // C. 痛觉
            else if (isAlertedByDamage && ai.lastDamageSourcePosition != null
                    && p.position().distanceSq(ai.lastDamageSourcePosition) < 300 * 300) {
                reason = PerceptionReason.PAIN;
            }

            if (reason != null) {
                currentlyPerceived.add(new PerceivedPlayer(p, reason));
                timestamps.put(p.id(), currentTime);

                // 审计日志
                if (!memory.containsKey(p.id()) && !p.isAI()) {
                    PerceptionReason detectionReason = reason;
                    AiDiagnostics.trace("perception", logger,
                            () -> String.format("[Perception Audit] %s detected %s via %s",
                                    ai.name, p.name(), detectionReason));
                }
            }
        }

        // 3. 记忆合并
        Map<String, PerceivedPlayer> finalViewMap = new HashMap<>();
        for (PerceivedPlayer pp : currentlyPerceived) {
            memory.put(pp.snapshot().id(), pp);
            finalViewMap.put(pp.snapshot().id(), pp);
        }

        Iterator<Map.Entry<String, PerceivedPlayer>> it = memory.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PerceivedPlayer> entry = it.next();
            String id = entry.getKey();
            if (finalViewMap.containsKey(id))
                continue;

            Long lastSeen = timestamps.get(id);
            if (lastSeen == null || (currentTime - lastSeen > MEMORY_EXPIRY_MS)) {
                it.remove();
                timestamps.remove(id);
            } else {
                // 标记为记忆
                finalViewMap.put(id, new PerceivedPlayer(entry.getValue().snapshot(), PerceptionReason.MEMORY));
            }
        }

        return new AIWorldView(new ArrayList<>(finalViewMap.values()));
    }

    /**
     * [重构版] AI视线检测 - 四叉树加速 + 无硬编码视距限制
     *
     * 原版使用 getObstacles().stream() 遍历所有 Shape，并以 1800 单位作为性能逃生阀。
     * 新版通过 QuadtreeNode.queryRay() 将候选障碍物从"全图"缩减到"射线路径上"，
     * 使得硬限制不再必要，AI 视距改为动态地图对角线长度（几乎等于全图）。
     */
    private boolean isVisibleToInternal(Player ai, Player.PlayerSnapshot target, boolean isAlerted) {
        if (ai.getFlashDuration() > 0)
            return false;

        Point2D.Double targetPos = target.position();

        // [原] if (distSq > 1800 * 1800) return false;
        // [新] 使用地图对角线做软上限，防止跨服务器实例的极端场景，但实际上几乎不会触发
        double mapDiagSq = (double) gameState.getMapWidth() * gameState.getMapWidth()
                + (double) gameState.getMapHeight() * gameState.getMapHeight();
        double distSq = ai.position.distanceSq(targetPos);
        if (distSq > mapDiagSq)
            return false;

        Line2D.Double ray = new Line2D.Double(ai.position, targetPos);

        // [新] 四叉树加速：只获取射线路径上的候选障碍物 ShapeWrapper
        QuadtreeNode qtRoot = gameState.getQuadtreeRootNode();
        boolean blockedByWall;
        if (qtRoot != null) {
            List<MapData.ShapeWrapper> candidates = new ArrayList<>();
            qtRoot.queryRay(candidates,
                    new Point2D.Double(ai.position.x, ai.position.y),
                    new Point2D.Double(targetPos.x, targetPos.y));
            blockedByWall = candidates.stream()
                    .map(wrapper -> gameState.getShapeFromWrapper(wrapper))
                    .filter(obs -> obs != null)
                    .anyMatch(obs -> obs.intersects(ray.getBounds2D())
                            && GameState.getLineShapeIntersections(ray, obs) != null);
        } else {
            // 四叉树未初始化时降级为原始全量扫描（理论上只在随机地图时发生）
            blockedByWall = gameState.getObstacles().stream()
                    .anyMatch(obs -> obs.intersects(ray.getBounds2D())
                            && GameState.getLineShapeIntersections(ray, obs) != null);
        }

        if (blockedByWall)
            return false;

        boolean blockedBySmoke = gameState.getSmokePuffs().stream()
                .anyMatch(smoke -> ray.ptSegDist(smoke.position) < 70);
        if (blockedBySmoke)
            return false;

        if (isAlerted)
            return true;

        double angleToTarget = Math.atan2(targetPos.y - ai.position.y, targetPos.x - ai.position.x);
        double angleDiff = Math.abs(normalizeAngle(angleToTarget - ai.angle));
        return (angleDiff <= Math.toRadians(75));
    }

    private double normalizeAngle(double angle) {
        while (angle <= -Math.PI)
            angle += 2 * Math.PI;
        while (angle > Math.PI)
            angle -= 2 * Math.PI;
        return angle;
    }

    private void injectRealisticInput(Player ai, AIInput decision) {
        aiInputMailbox.put(ai.id, decision);
    }
}
