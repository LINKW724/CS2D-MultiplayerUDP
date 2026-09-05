// 文件: cs2d/AIControl/B/PerceptionModule.java
package cs2d.AIControl.B;

import cs2d.AIControl.A.PathfindingModule;
import cs2d.playerAndAi.Player;
import cs2d.server.AIService.AIWorldView;
import cs2d.server.AIService.PerceivedPlayer;
import cs2d.server.GameMode;
import cs2d.server.GameState;
import cs2d.server.SoundEvent; // 仍然需要 SoundEvent 类定义
import cs2d.server.AIDifficulty;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI 感知模块。
 * 主动从 GameState 获取所有声音事件并在内部进行筛选，模仿客户端声音暴露逻辑。
 */
public class PerceptionModule {
    // ... (所有字段和常量，包括 logger, TIMESTAMP_FORMATTER, 声音暴露常量等，保持不变) ...
    private final Player owner;
    private final GameState gameState;
    private final AIDifficulty difficulty;
    private final Map<String, PerceptionInfo> perceptionMap = new ConcurrentHashMap<>();
    private final Consumer<String> logger;

    public static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long VISUAL_PERCEPTION_TIMEOUT_MS = 7000L;
    private static final long SOUND_MEMORY_TIMEOUT_MS = 3000L;
    private static final double FOOTSTEP_REVEAL_RANGE = 1050.0;
    private static final double GUNSHOT_REVEAL_RANGE = FOOTSTEP_REVEAL_RANGE * 5.0;
    private static final double FOOTSTEP_REVEAL_RANGE_SQ = FOOTSTEP_REVEAL_RANGE * FOOTSTEP_REVEAL_RANGE;
    private static final double GUNSHOT_REVEAL_RANGE_SQ = GUNSHOT_REVEAL_RANGE * GUNSHOT_REVEAL_RANGE;
    private static final long SOUND_REVEAL_DURATION_MS = 300L;

    // PerceptionInfo record 保持不变
    public record PerceptionInfo(/* ... 字段不变 ... */
            String enemyId, PerceptionType type, Point2D.Double lastKnownPosition,
            long timestamp, boolean isCurrentlyVisible, long soundRevealExpireTime) {
    }

    // 构造函数 (不变)
    public PerceptionModule(Player owner, GameState gameState, AIDifficulty difficulty, Consumer<String> logger) {
        if (owner == null || gameState == null) {
            throw new IllegalArgumentException("PerceptionModule 需要非空的 owner 和 gameState。");
        }
        this.owner = owner;
        this.gameState = gameState;
        this.difficulty = difficulty;
        this.logger = logger;
    }

    /**
     * 更新 AI 的感知信息。
     * 主动查询 GameState 的 AI 专用缓冲区获取声音事件。
     * 
     * @param worldView   当前世界状态快照 (用于视觉)。
     * @param currentTime 当前时间戳 (毫秒)。
     */
    public void update(AIWorldView worldView, /* 移除 List<SoundEvent> 参数 */ long currentTime) {
        // 安全检查：如果核心组件（世界视图、自身、位置、游戏状态）缺失，则跳过
        if (worldView == null || owner == null || owner.position == null || gameState == null) {
            // 可以添加日志记录跳过的原因
            // if (logger != null && owner != null)
            // logger.accept("["+LocalDateTime.now().format(TIMESTAMP_FORMATTER) + "] AI
            // ["+owner.name+"] PerceptionModule update skipped (null components).");
            return;
        }

        // --- 1. 获取 *AI 缓冲区* 的声音事件 ---
        // 从 AIService 提供的公用历史中读取
        List<SoundEvent> allSoundEvents = gameState.getAiSoundHistory();
        // --- 声音获取结束 ---

        // --- 2. 处理视觉信息 (逻辑不变) ---
        // processVisuals 会更新 perceptionMap 中 SIGHT 类型的信息，并返回本轮看到的所有敌人 ID
        Set<String> perceivedThisTick = processVisuals(worldView, currentTime);

        // --- 3. 处理声音信息 (传入的是刚从 AI 缓冲区获取的事件) ---
        // processSounds 内部会进行距离筛选、暴露检查和信息更新
        processSounds(allSoundEvents, currentTime, perceivedThisTick);

        // --- 4. 处理信息老化和可见性更新 (逻辑不变) ---
        applyAging(currentTime, perceivedThisTick);
    }

    // -------------------------------------------------------------------------
    // 依赖的 processSounds 方法 (为完整性提供)
    // -------------------------------------------------------------------------

    /**
     * 处理声音事件。在内部进行距离筛选。
     */
    private void processSounds(List<SoundEvent> allSoundEvents, long currentTime, Set<String> perceivedThisTick) {
        // 基本检查
        if (allSoundEvents == null || owner == null || owner.position == null)
            return;

        // 获取 AI 的听力范围平方值，用于筛选
        double hearingRangeSq = calculateHearingRangeSq();
        if (hearingRangeSq <= 0)
            return; // 听力为 0，跳过

        Point2D.Double ownerPos = owner.position; // 获取 AI 当前位置

        // 1. 在循环外获取当前游戏模式
        GameMode currentMode = gameState.getGameMode();

        // 遍历 *所有* 声音事件 (allSoundEvents 列表)
        for (SoundEvent sound : allSoundEvents) {
            // 基本声音过滤 (自己, 确保事件有效)
            if (sound == null || sound.sourcePlayerId() == null || sound.sourcePlayerId().equals(owner.id))
                continue;
            Point2D.Double soundPos = new Point2D.Double(sound.x(), sound.y());

            // *** 距离筛选 ***
            if (ownerPos.distanceSq(soundPos) > hearingRangeSq) {
                continue; // 超出听力范围，跳过
            }

            // 检查来源是否为敌人
            Player sourcePlayer = (gameState != null) ? gameState.getPlayerById(sound.sourcePlayerId()) : null;
            if (sourcePlayer == null)
                continue; // 来源玩家已消失

            // 2. 动态判断 "isEnemy"
            boolean isEnemy;
            if (currentMode == GameMode.DEATHMATCH) {
                isEnemy = true; // 死斗模式，所有人都是敌人
            } else {
                isEnemy = sourcePlayer.team != owner.team; // 其他模式，按队伍
            }

            // 3. 如果不是敌人，则跳过
            if (!isEnemy)
                continue;

            // --- 剩下的逻辑是原有的 ---

            // [判断声音类型]
            PerceptionType soundType;
            boolean isGunshot = sound.type() == SoundEvent.SoundType.FIRE;
            boolean isFootstep = sound.type() == SoundEvent.SoundType.FOOTSTEP;
            boolean isReload = "RELOAD".equals(sound.type().name());

            if (isGunshot) {
                soundType = PerceptionType.GUNSHOT;
            } else if (isFootstep || isReload) {
                soundType = PerceptionType.FOOTSTEP;
            } else {
                continue; // 忽略其他类型
            }

            String enemyId = sound.sourcePlayerId();
            PerceptionInfo currentInfo = perceptionMap.get(enemyId);

            // --- 声音暴露检测 (逻辑不变) ---
            boolean hasDirectLoS = hasLineOfSight(soundPos);
            // ... (原有的 hasDirectLoS, revealConditionMet, revealExpireTime 逻辑) ...
            boolean revealConditionMet = false;
            long revealExpireTime = 0;
            if (!hasDirectLoS) {
                double distSq = ownerPos.distanceSq(soundPos);
                if (isGunshot && distSq < GUNSHOT_REVEAL_RANGE_SQ) {
                    revealConditionMet = true;
                } else if (isFootstep && distSq < FOOTSTEP_REVEAL_RANGE_SQ) {
                    if (currentInfo == null || currentInfo.type() != PerceptionType.SIGHT
                            || (currentTime - currentInfo.timestamp()) > 500L) {
                        revealConditionMet = true;
                    }
                }
                if (revealConditionMet) {
                    revealExpireTime = currentTime + SOUND_REVEAL_DURATION_MS;
                }
            }

            // --- 更新感知表 (逻辑不变) ---
            boolean shouldUpdateWithSound = false;
            if (currentInfo == null) {
                shouldUpdateWithSound = true;
            } else if (currentTime > currentInfo.timestamp()) {
                shouldUpdateWithSound = true;
            }

            if (shouldUpdateWithSound) {
                // ... (原有的 isVisibleNow, expiryTimeToUse, logger, 和 perceptionMap.put 逻辑) ...
                boolean isVisibleNow = revealConditionMet;
                long expiryTimeToUse = revealExpireTime;

                if (perceivedThisTick.contains(enemyId)) { // 视觉优先
                    isVisibleNow = true;
                    expiryTimeToUse = 0;
                } else if (currentInfo != null && currentInfo.isCurrentlyVisible()
                        && currentInfo.soundRevealExpireTime() > currentTime && !revealConditionMet) {
                    isVisibleNow = true;
                    expiryTimeToUse = currentInfo.soundRevealExpireTime();
                }

                if (logger != null) {
                    String soundTypeName = isGunshot ? "枪声" : "脚步声";
                    LocalDateTime now = LocalDateTime.now();
                    String timestampStr = now.format(TIMESTAMP_FORMATTER);
                    double distance = owner.position.distance(soundPos);
                    String revealStatus = isVisibleNow ? "(触发暴露)" : "";
                }

                PerceptionInfo updatedInfo = new PerceptionInfo(
                        enemyId, soundType, soundPos, currentTime, isVisibleNow, expiryTimeToUse);

                perceptionMap.put(enemyId, updatedInfo);
                perceivedThisTick.add(enemyId);
            }
        }
    }

    // 在 PerceptionModule.java 中
    private Set<String> processVisuals(AIWorldView worldView, long currentTime) {
        Set<String> newlyVisible = new HashSet<>();
        if (worldView.players() == null)
            return newlyVisible;

        // 1. 在循环外获取当前游戏模式
        GameMode currentMode = gameState.getGameMode();

        for (PerceivedPlayer pp : worldView.players()) {
            Player.PlayerSnapshot snapshot = pp.snapshot();
            // 2. 基本过滤：跳过自己、死亡单位、或无效数据
            if (snapshot == null || snapshot.id() == null || snapshot.id().equals(owner.id) || snapshot.health() <= 0
                    || snapshot.position() == null)
                continue;

            // 3. 动态判断 "isEnemy"
            boolean isEnemy;
            if (currentMode == GameMode.DEATHMATCH) {
                isEnemy = true; // 在死斗模式，所有人都是敌人 (已经排除了自己)
            } else {
                isEnemy = snapshot.team() != owner.team; // 在其他模式，按队伍
            }

            // 4. 如果不是敌人，则跳过
            if (!isEnemy)
                continue;

            // --- 剩下的逻辑是原有的 ---
            Point2D.Double enemyPos = snapshot.position();

            boolean hasDirectLoS = hasLineOfSight(enemyPos);

            if (hasDirectLoS) {
                PerceptionInfo updatedInfo = new PerceptionInfo(
                        snapshot.id(), PerceptionType.SIGHT, enemyPos, currentTime, true, 0);
                perceptionMap.put(snapshot.id(), updatedInfo);
                newlyVisible.add(snapshot.id());
            }
        }
        return newlyVisible;
    }

    // applyAging 方法 (逻辑不变)
    private void applyAging(long currentTime, Set<String> perceivedThisTick) {
        synchronized (perceptionMap) {
            Iterator<Map.Entry<String, PerceptionInfo>> iterator = perceptionMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, PerceptionInfo> entry = iterator.next();
                String enemyId = entry.getKey();
                PerceptionInfo info = entry.getValue();
                if (info == null) {
                    iterator.remove();
                    continue;
                }

                // 1. 移除死亡敌人
                Player enemyPlayer = (gameState != null) ? gameState.getPlayerById(enemyId) : null;
                if (enemyPlayer == null || !enemyPlayer.isAlive()) {
                    iterator.remove();
                    continue;
                }

                // 2. 处理可见性状态变化
                boolean visibilityChanged = false;
                boolean currentVisibility = info.isCurrentlyVisible();
                long currentExpiryTime = info.soundRevealExpireTime();

                if (currentVisibility && currentExpiryTime > 0 && currentTime > currentExpiryTime) { // 声音暴露过期
                    currentVisibility = false;
                    currentExpiryTime = 0;
                    visibilityChanged = true;
                } else if (!perceivedThisTick.contains(enemyId) && currentVisibility && !visibilityChanged) { // 视觉丢失
                    currentVisibility = false;
                    visibilityChanged = true;
                }

                // 3. 更新 Map (如果可见性改变)
                if (visibilityChanged) {
                    PerceptionInfo updatedInfo = new PerceptionInfo(
                            info.enemyId(), info.type(), info.lastKnownPosition(), info.timestamp(),
                            currentVisibility, currentExpiryTime);
                    perceptionMap.put(enemyId, updatedInfo);
                    info = updatedInfo;
                }

                // 4. 检查记忆超时
                long timeout = (info.type() == PerceptionType.SIGHT) ? VISUAL_PERCEPTION_TIMEOUT_MS
                        : SOUND_MEMORY_TIMEOUT_MS;
                if (currentTime - info.timestamp() > timeout) {
                    iterator.remove();
                }
            }
        }
    }

    // hasLineOfSight 方法 (逻辑不变)
    private boolean hasLineOfSight(Point2D.Double targetPos) {
        if (owner == null)
            return false;
        PathfindingModule pathModule = owner.getPathfindingModule();
        if (pathModule != null) {
            if (targetPos == null)
                return false;
            return pathModule.hasLineOfSight(targetPos);
        } else {
            System.err.println("警告: PerceptionModule 无法访问 PathfindingModule 进行 LoS 检查！");
            return true;
        }
    }

    // calculateHearingRangeSq 方法 (逻辑不变)
    private double calculateHearingRangeSq() {
        if (difficulty == null)
            return 0.0;
        double hearingRange;
        switch (this.difficulty) {
            case HARD:
                hearingRange = 200.0;
                break;
            case VERY_HARD:
                hearingRange = 450.0;
                break;
            case HELL:
                hearingRange = 700.0;
                break;
            case REALISTIC:
                hearingRange = 6000.0;
                break; // 真实模式可以听到很远的枪声
            default:
                hearingRange = 1050.0; // 默认听力范围
        }
        return hearingRange * hearingRange;
    }

    // --- 公共接口 (逻辑不变) ---
    public PerceptionInfo getPerceptionInfo(String enemyId) {
        return perceptionMap.get(Objects.requireNonNullElse(enemyId, ""));
    }

    public Map<String, PerceptionInfo> getAllPerceivedEnemies() {
        synchronized (perceptionMap) {
            return new HashMap<>(perceptionMap);
        }
    }

    public void clearPerceptions() {
        synchronized (perceptionMap) {
            perceptionMap.clear();
        }
    }
} // PerceptionModule 类结束