//// =================================================================================
//// 文件: TeamCommander.java
//// 描述: 爆破模式的团队战术指挥官，拥有全局视野，为AI团队制定和下达战术指令。
//// =================================================================================
//package cs2d.playerAndAi;
//
//import cs2d.playerAndAi.AIController;
//import cs2d.playerAndAi.Player;
//import cs2d.server.GameState;
//
//import java.awt.*;
//import java.awt.geom.Line2D;
//import java.awt.geom.Point2D;
//import java.util.*;
//import java.util.List;
//import java.util.function.Consumer;
//import java.util.stream.Collectors;
//
//import static cs2d.server.GameState.DEMO_MAX_ROUNDS;
//import static cs2d.server.GameState.DEMO_WIN_SCORE;
//
//
//// =================================================================================
//// 文件: TeamCommander.java
//// 描述: 爆破模式的团队战术指挥官，拥有全局视野，为AI团队制定和下达战术指令。
//// =================================================================================
//
///**
// * 核心修改理念：让AI先清除障碍 (ROAD_CLEARER/DISTRACTION)，再执行任务 (BOMB_CARRIER/DEFUSER)。
// */
//public class TeamCommander {
//
//    // 指挥官负责的队伍
//    private final Player.Team team;
//    // 对游戏主状态的引用，用于获取全局信息
//    private final GameState gameState;
//    // 日志记录器
//    private final Consumer<String> logger;
//    // 随机数生成器
//    private final Random rand = new Random();
//    // 寻路器，用于分析地图路径
//    private final AIController.Pathfinder pathfinder;
//
//    // 当前执行的战术
//    private Tactic currentTactic = Tactic.IDLE;
//    // 上次改变战术的时间，防止过于频繁地变更指令
//    private long lastTacticChangeTime = 0;
//    //  用于T方，一旦有队友进入包点，就锁定该包点为唯一进攻目标
//    private Site lockedAttackSite = null;
//    // 战术冷却时间（毫秒）
//    private static final long TACTIC_COOLDOWN_MS = 10000; // 10秒
//    //  用于记录当前指定的拆弹手的ID
//    private String designatedDefuserId = null;
//    private enum RetakePhase {
//        IDLE,
//        CLEARING_SITE,        // 阶段一：清空包点
//        SECURING_FOR_DEFUSE   // 阶段二：掩护拆弹
//    }
//
//    public enum EconomicStrategy {
//        FULL_BUY,     // 全甲全枪，常规购买
//        FORCE_BUY,    // 强起局 (甲+便宜的枪)
//        HERO_RIFLE,   // 英雄枪局 (保一个最有钱的人起长枪，其他人辅助)
//        ECO           // 纯经济局/保枪 (几乎不买)
//    }
//
//    private EconomicStrategy currentEcoStrategy = EconomicStrategy.FULL_BUY;
//
//    private RetakePhase currentRetakePhase = RetakePhase.IDLE;
//    private Site bombSiteLocation = null; // 记录炸弹在哪一个点
//    /**
//     * 定义所有可用的战术
//     */
//    public enum Tactic {
//        IDLE,               // 无战术，自由行动
//        // T方战术
//        RUSH_A,             // 一波流A
//        RUSH_B,             // 一波流B
//        SPLIT_3A_2B,        // 3A 2B 分推
//        SPLIT_2A_3B,        // 2A 3B 分推
//        DEFEND_PLANTED_SITE, //守包
//        PINCER_A,           // 夹击A
//        PINCER_B,           // 夹击B
//        RETRIEVE_BOMB,      // C4掉落，去捡包
//        // CT方战术
//        DEFEND_BALANCED,    // 均衡防守 (例如 2A 3B)
//        DEFEND_STACK_A,     // 重防A
//        DEFEND_STACK_B,     // 重防B
//
//        DEFEND_AGGRESSIVE_PUSH,  //  前压战术
//        DEFEND_SITE_CROSSFIRE, // 包点交叉火力
//        DEFEND_MID_CONTROL,    // 分散控图
//        ROTATE_TO_A,        // 全员回防A
//        ROTATE_TO_B,        // 全员回防B
//        SAVE_ROUND,          // 保枪经济局
//        EXECUTE_RETAKE, // 执行回防拆包战术 (分工合作)
//        GUARD_DROPPED_BOMB, //掉落守包
//        FLEEING_BOMB    // 正在从即将爆炸的C4处撤离
//    }
//
//    /**
//     * 定义包点
//     */
//    private enum Site { A, B }
//
//    public TeamCommander(Player.Team team, GameState gameState, Consumer<String> logger) {
//        this.team = team;
//        this.gameState = gameState;
//        this.logger = logger;
//        // 指挥官也需要一个寻路器来理解地图结构
//        this.pathfinder = new AIController.Pathfinder(gameState, (int) Player.SIZE);
//    }
//
//    /**
//     * 根据团队平均经济，决定本回合的经济策略。
//     */
//    private EconomicStrategy decideEconomicStrategy() {
//        // --- 1. 最高优先级：检查是否为“决胜局/最后一搏”局 ---
//
//        // 获取双方得分
//        int myScore = (team == Player.Team.T) ? gameState.getTScore() : gameState.getCTScore();
//        int enemyScore = (team == Player.Team.T) ? gameState.getCTScore() : gameState.getTScore();
//
//        // 定义决胜局条件
//        boolean isLastRoundOfFirstHalf = (gameState.currentRound == 12);
//        boolean isLastRoundOfGame = (gameState.currentRound == DEMO_MAX_ROUNDS);
//        boolean isEnemyOnMatchPoint = (enemyScore == DEMO_WIN_SCORE - 1);
//        boolean isMyTeamOnMatchPoint = (myScore == DEMO_WIN_SCORE - 1);
//
//        // 如果满足任何一个“最后一搏”的条件，则强制全买！
//        if (isLastRoundOfFirstHalf || isLastRoundOfGame || isEnemyOnMatchPoint || isMyTeamOnMatchPoint) {
//            return EconomicStrategy.FULL_BUY;
//        }
//
//        // --- 2. 如果不是决胜局，则执行常规的经济策略判断 ---
//
//        double avgMoney = analyzeTeamEconomy();
//
//        if (avgMoney >= 4200) {
//            return EconomicStrategy.FULL_BUY;
//        } else if (avgMoney >= 2500) {
//            return rand.nextDouble() < 0.6 ? EconomicStrategy.FORCE_BUY : EconomicStrategy.HERO_RIFLE;
//        } else {
//            return EconomicStrategy.ECO;
//        }
//    }
//
//    /**
//     * 新回合开始时调用的重置方法
//     */
//
//    public void onNewRound() {
//        currentTactic = Tactic.IDLE;
//        lastTacticChangeTime = 0;
//        this.designatedDefuserId = null;
//        this.currentRetakePhase = RetakePhase.IDLE; //  重置回防阶段
//        this.bombSiteLocation = null;               // 清空炸弹位置记录
//        this.lockedAttackSite = null;
//        // 分析我方经济状况，并决定本回合的总体购买策略
//        this.currentEcoStrategy = decideEconomicStrategy();
////        logger.accept("[COMMANDER " + team + "] New round. Resetting tactics.");
//    }
//
//    // TeamCommander.java
//    /**
//     * 找到本队中最有钱的那个存活玩家。
//     * 用于“英雄枪”战术，决定由谁来起主战武器。
//     * @return 最富有的Player对象，如果没有存活队员则返回null。
//     */
//    public Player getRichestPlayer() {
//        return gameState.getPlayers().stream()
//                .filter(p -> p.team == this.team && p.isAlive())
//                .max(Comparator.comparingInt(p -> p.money))
//                .orElse(null);
//    }
//
//    // 允许外部获取当前经济策略
//    public EconomicStrategy getCurrentEcoStrategy() {
//        return this.currentEcoStrategy;
//    }
//
//
//    private Tactic potentialNewTactic = Tactic.IDLE; // 潜在的新战术
//    private long potentialTacticStartTime = 0;      // 这个潜在战术开始被考虑的时间
//    private static final long TACTIC_CONFIRM_DELAY_MS = 3000; // 需要持续3秒才确认切换
//    /**
//     * 指挥官的核心更新方法，每帧（或每秒）由GameState调用
//     */
//    /**
//     * [已升级] 指挥官的核心更新方法。
//     * 此版本能够无视冷却时间，对“炸弹安放”等关键事件做出即时反应。
//     */
//    public void update() {
//        long currentTime = System.currentTimeMillis();
//
//        // 1. 回合状态检查 (最高优先级)
//        if (gameState.getRoundPhase() != GameState.RoundPhase.IN_PROGRESS) {
//            if (currentTactic != Tactic.IDLE) {
//                logger.accept("[" + team + " Commander] Round ended/frozen. Forcing IDLE tactic.");
//                resetAllBotObjectives();
//                currentTactic = Tactic.IDLE;
//            }
//            executeIdle(getAliveTeammates());
//            return;
//        }
//
//        // --- 2. 常规决策流程 (只决策一次) ---
//        boolean shouldCheckForNewTactic = true;
//
//        // 检查战术冷却期 (允许紧急事件覆盖)
//        if (lastTacticChangeTime != 0 && currentTime - lastTacticChangeTime < TACTIC_COOLDOWN_MS) {
//            boolean urgentBombEvent = (team == Player.Team.CT && gameState.isBombPlanted()) || (gameState.getDroppedBomb() != null);
//            if (!urgentBombEvent) {
//                shouldCheckForNewTactic = false; // 在冷却期内，不应该尝试新的战术决策
//            } else {
//                logger.accept("[" + team + " Commander] Update: Overriding cooldown due to urgent bomb event!");
//            }
//        }
//
//        // 只有在可以决策或战术仍是 IDLE 时才决策 (以获取初始任务)
//        if (shouldCheckForNewTactic || currentTactic == Tactic.IDLE) {
//            Map<Site, Double> siteThreats = analyzeSiteThreats();
//            double teamEconomy = analyzeTeamEconomy();
//            Tactic newTactic = decideNextTactic(siteThreats, teamEconomy); // 仅调用一次决策
//
//            // 3. 检查并执行战术切换
//            if (newTactic != currentTactic) {
//                // [日志]
////                logger.accept("[COMMANDER " + team + "] !!! TACTIC CHANGE !!! From " + currentTactic + " to " + newTactic + ". Resetting objectives NOW.");
//                resetAllBotObjectives();
//
//                // 确保更新所有状态变量
//                currentTactic = newTactic;
//                lastTacticChangeTime = currentTime;
//                potentialNewTactic = newTactic; // 更新潜在战术
//                potentialTacticStartTime = currentTime;
////                logger.accept("[COMMANDER " + team + "] >>> NEW TACTIC ISSUED: " + currentTactic + " <<<");
//
//                // 立即执行新战术，确保 AI 收到指令
//                executeCurrentTactic();
//                return; // 立即返回，防止执行两次 executeCurrentTactic
//            }
//        }
//
//        // 4. 无论战术是否改变，都执行当前的战术
//        executeCurrentTactic();
//    }
//
//    /**
//     * 获取当前队伍所有存活的AI队友
//     * @return 一个包含存活AI Player对象的列表
//     */
//    private List<Player> getAliveTeammates() {
//        // [健壮性检查] 确保 gameState 和 getPlayers() 返回的列表不为 null
//        if (gameState == null || gameState.getPlayers() == null) {
//            logger.accept("[" + team + " Commander] Error in getAliveTeammates: gameState or player list is null.");
//            return new ArrayList<>(); // 返回空列表避免后续错误
//        }
//
//        return gameState.getPlayers().stream()
//                .filter(p -> p != null && // 增加对 Player 对象本身的 null 检查
//                        p.team == this.team &&
//                        p.isAI &&
//                        p.isAlive() &&
//                        p.aiController != null) // 确保 AI 控制器也存在
//                .collect(Collectors.toList());
//    }
//
//    // =================================================================================
//    // 战术决策 (The Brain)
//    // =================================================================================
//
//    private Tactic decideNextTactic(Map<Site, Double> siteThreats, double teamEconomy) {
//
//        // --- [修改] 步骤 1：优先处理团队特定逻辑中的紧急事件 ---
//        Tactic teamSpecificTactic;
//        if (team == Player.Team.T) {
//            teamSpecificTactic = decideTactic_T(); // 获取T方决策
//        } else { // CT
//            teamSpecificTactic = decideTactic_CT(siteThreats); // 获取CT方决策
//        }
//
//        // 检查团队特定决策是否是必须立即执行的紧急事件
//        boolean isUrgent = (teamSpecificTactic == Tactic.RETRIEVE_BOMB || // T捡包
//                teamSpecificTactic == Tactic.DEFEND_PLANTED_SITE || // T守包
//                teamSpecificTactic == Tactic.EXECUTE_RETAKE || // CT回防
//                teamSpecificTactic == Tactic.GUARD_DROPPED_BOMB); // CT守掉落包
//
//        if (isUrgent) {
//            // 如果是紧急事件，直接返回，忽略保枪判断
//            return teamSpecificTactic;
//        }
//
//        // --- 步骤 2：如果不是紧急事件，再进行经济保枪判断 ---
//        if (shouldCallSave(teamEconomy)) {
//            return Tactic.SAVE_ROUND;
//        }
//
//        // --- 步骤 3：如果既非紧急事件，也不需要保枪，则返回之前计算的团队特定战术 ---
//        return teamSpecificTactic;
//    }
//    /**
//     * [最终完整版] CT指挥官的核心决策大脑。
//     * 该方法会根据战场的实时情况，决定整个队伍在下一阶段应该执行的宏观战术。
//     * 决策优先级如下：
//     * 1. 最高优先级：响应C4相关的紧急事件（掉落、安放）。
//     * 2. 次高优先级：在回合开局阶段，从多种预设战术中随机选择一种来执行。
//     * 3. 常规优先级：在回合中期，根据敌人威胁动态调整防守（例如，从B点回防A点）。
//     * 4. 默认：如果没有任何特殊情况，则维持当前战术不变。
//     * @return 计算出的最佳战术 (Tactic枚举)。
//     */
//    private Tactic decideTactic_T() {
//        // --- 1. 最高优先级：C4事件 ---
//        if (gameState.getDroppedBomb() != null) return Tactic.RETRIEVE_BOMB;
//        if (gameState.isBombPlanted()) return Tactic.DEFEND_PLANTED_SITE;
//
//        // --- 2. 次高优先级：包点锁定机制 ---
//        if (lockedAttackSite != null) {
//            return (lockedAttackSite == Site.A) ? Tactic.RUSH_A : Tactic.RUSH_B;
//        }
//        Site reachedSite = findTeammateOnSite();
//        if (reachedSite != null) {
//            this.lockedAttackSite = reachedSite; // 锁定目标点！
//            return (reachedSite == Site.A) ? Tactic.RUSH_A : Tactic.RUSH_B; // 返回总攻指令
//        }
//
//        // --- 3. 中期决策 -> 根据伤亡情况调整战术 ---
//        long initialTeammates = gameState.getPlayers().stream().filter(p -> p.team == this.team).count();
//        long aliveTeammates = gameState.getPlayers().stream().filter(p -> p.team == this.team && p.isAlive()).count();
//
//        if (initialTeammates > 0) {
//            double lossPercentage = (double)(initialTeammates - aliveTeammates) / initialTeammates;
//
//            if (lossPercentage >= 0.6) {
//                if (currentTactic != Tactic.RUSH_A && currentTactic != Tactic.RUSH_B) {
//                    Map<Site, Double> siteThreats = analyzeSiteThreats();
//                    double threatA = siteThreats.getOrDefault(Site.A, 0.0);
//                    double threatB = siteThreats.getOrDefault(Site.B, 0.0);
//
//                    if (threatA <= threatB) {
//                        return Tactic.RUSH_A;
//                    } else {
//                        return Tactic.RUSH_B;
//                    }
//                }
//            }
//        }
//
//        // --- 4. 常规优先级：开局“锁定”战术 ---
//        boolean isOpeningOfRound = (System.currentTimeMillis() - gameState.roundStartTime < 5000) && currentTactic == Tactic.IDLE;
//
//        if (isOpeningOfRound) {
////            logger.accept("[COMMANDER T | OPENING] Deciding opening strategy...");
//            double roll = rand.nextDouble();
//            if (roll < 0.35) {
//                return rand.nextBoolean() ? Tactic.RUSH_A : Tactic.RUSH_B;
//            } else if (roll < 0.70) {
//                return rand.nextBoolean() ? Tactic.SPLIT_3A_2B : Tactic.SPLIT_2A_3B;
//            } else {
//                return rand.nextBoolean() ? Tactic.PINCER_A : Tactic.PINCER_B;
//            }
//        }
//
//        // --- 5. 默认情况 ---
//        if (currentTactic == Tactic.IDLE) {
//            return Tactic.RUSH_A;
//        }
//        return currentTactic;
//    }
//
//    /**
//     * 辅助方法：检查是否有任何一个存活的T方队友正站在A或B包点内。
//     */
//    private Site findTeammateOnSite() {
//        List<Player> aliveTeammates = gameState.getPlayers().stream()
//                .filter(p -> p.team == this.team && p.isAlive())
//                .collect(Collectors.toList());
//
//        Rectangle siteA = gameState.getBombSiteA();
//        Rectangle siteB = gameState.getBombSiteB();
//
//        for (Player teammate : aliveTeammates) {
//            if (siteA != null && teammate.getBounds().intersects(siteA)) {
//                return Site.A;
//            }
//            if (siteB != null && teammate.getBounds().intersects(siteB)) {
//                return Site.B;
//            }
//        }
//        return null; // 没有人在任何一个包点上
//    }
//
//    /**
//     * CT指挥官的核心决策大脑。
//     */
//    private Tactic decideTactic_CT(Map<Site, Double> siteThreats) {
//        // --- 1. 最高优先级：响应C4事件 ---
//        if (gameState.getDroppedBomb() != null) {
//            return Tactic.GUARD_DROPPED_BOMB;
//        }
//        if (gameState.isBombPlanted()) {
//            if (currentTactic != Tactic.EXECUTE_RETAKE) {
//                currentRetakePhase = RetakePhase.CLEARING_SITE;
//            }
//            return Tactic.EXECUTE_RETAKE;
//        } else {
//            if (currentTactic == Tactic.EXECUTE_RETAKE) {
//                currentRetakePhase = RetakePhase.IDLE;
//                designatedDefuserId = null;
//            }
//        }
//
//        // --- 2. 次高优先级：多元化开局决策 ---
//        boolean isOpeningOfRound = (System.currentTimeMillis() - gameState.roundStartTime < 5000) &&
//                (currentTactic == Tactic.IDLE || currentTactic == Tactic.DEFEND_BALANCED);
//
//        if (isOpeningOfRound) {
//            double strategyRoll = rand.nextDouble();
//
//            if (strategyRoll < 0.25) {
////                logger.accept("[COMMANDER CT | OPENING] Strategy Roll: AGGRESSIVE PUSH!");
//                return Tactic.DEFEND_AGGRESSIVE_PUSH;
//            } else if (strategyRoll < 0.55) {
////                logger.accept("[COMMANDER CT | OPENING] Strategy Roll: SITE CROSSFIRE!");
//                return Tactic.DEFEND_SITE_CROSSFIRE;
//            } else if (strategyRoll < 0.75) {
////                logger.accept("[COMMANDER CT | OPENING] Strategy Roll: MID CONTROL!");
//                return Tactic.DEFEND_MID_CONTROL;
//            } else {
////                logger.accept("[COMMANDER CT | OPENING] Strategy Roll: STANDARD BALANCED DEFENSE.");
//                return Tactic.DEFEND_BALANCED;
//            }
//        }
//
//        // --- 3. 常规优先级：回合中期的动态调整 ---
//        long defendersAtA = getDefendersAtSite(Site.A);
//        long defendersAtB = getDefendersAtSite(Site.B);
//
//        double threatA = siteThreats.getOrDefault(Site.A, 0.0);
//        double threatB = siteThreats.getOrDefault(Site.B, 0.0);
//
//        if (threatA > threatB * 2.5 && defendersAtA < 2) {
//            return Tactic.ROTATE_TO_A;
//        }
//        if (threatB > threatA * 2.5 && defendersAtB < 2) {
//            return Tactic.ROTATE_TO_B;
//        }
//
//        if (defendersAtA > defendersAtB + 2 || defendersAtB > defendersAtA + 2) {
//            return Tactic.DEFEND_BALANCED;
//        }
//
//        // --- 4. 默认情况 ---
//        if (currentTactic == Tactic.IDLE) {
//            return Tactic.DEFEND_BALANCED;
//        }
//        return currentTactic;
//    }
//
//    // =================================================================================
//    // 战术执行 (The Hands)
//    // =================================================================================
//
//    private void executeCurrentTactic() {
//        List<Player> teammates = getAliveTeammates();
//        if (teammates.isEmpty()) return;
//
//        if (currentTactic == Tactic.IDLE) {
//            executeIdle(teammates);
//            return;
//        }
//
//        Collections.shuffle(teammates);
//
//        switch (currentTactic) {
//            case RUSH_A -> executeRush(teammates, Site.A);
//            case RUSH_B -> executeRush(teammates, Site.B);
//            case SPLIT_3A_2B -> executeSplit(teammates, Site.A, 3);
//            case SPLIT_2A_3B -> executeSplit(teammates, Site.A, 2);
//            case PINCER_A -> executePincer(teammates, Site.A);
//            case PINCER_B -> executePincer(teammates, Site.B);
//            case RETRIEVE_BOMB -> executeRetrieveBomb(teammates);
//            case DEFEND_PLANTED_SITE -> executeDefendPlantedSite(teammates);
//            case DEFEND_BALANCED -> executeBalancedDefense(teammates);
//            case ROTATE_TO_A -> executeRotate(teammates, Site.A);
//            case ROTATE_TO_B -> executeRotate(teammates, Site.B);
//            case SAVE_ROUND -> executeSave(teammates);
//            case EXECUTE_RETAKE -> executeRetake(teammates);
//            case GUARD_DROPPED_BOMB -> executeGuardDroppedBomb(teammates);
//            case DEFEND_AGGRESSIVE_PUSH -> executeAggressivePush(teammates);
//            case DEFEND_SITE_CROSSFIRE -> executeSiteCrossfire(teammates);
//            case DEFEND_MID_CONTROL -> executeMidControl(teammates);
//
//            default -> {
//                logger.accept(String.format("[COMMANDER %s] FATAL ERROR: currentTactic is %s (UNKNOWN VALUE). Executing IDLE.", team, currentTactic));
//                executeIdle(teammates);
//            }
//        }
//
//        switch (currentEcoStrategy) {
//            case HERO_RIFLE:
//                executeHeroRifle(teammates);
//                break;
//        }
//    }
//
//    // =================================================================================
//    // 战术执行 - 关键修改部分
//    // =================================================================================
//
//    /**
//     * T方战术：一波流总攻。
//     * 1. 分配 2/3 的人作为清道夫 (ROAD_CLEARER) 或吸引火力 (DISTRACTION)。
//     * 2. 剩下的人（包括带包者）作为支援 (SUPPORT) 或带包者 (BOMB_CARRIER)。
//     */
//    private void executeRush(List<Player> teammates, Site site) {
//        Point2D.Double targetSite = getSiteCenter(site);
//        Player bombCarrier = teammates.stream().filter(p -> p.hasBomb).findFirst().orElse(null);
//
//        int clearerCount = teammates.size() / 3 * 2; // 2/3 的人作为清道夫，至少1个
//        clearerCount = Math.max(1, clearerCount);
//
//        // 确保带包者不在清道夫队伍中
//        if (bombCarrier != null && teammates.indexOf(bombCarrier) < clearerCount) {
//            clearerCount = Math.min(clearerCount, teammates.size() - 1); // 保证带包者不是清道夫
//        }
//
//
//        for (int i = 0; i < teammates.size(); i++) {
//            Player bot = teammates.get(i);
//            if (bot == bombCarrier) {
//                // 带包者：最高任务优先级
//                bot.aiController.setTacticalObjective(targetSite, AIController.TacticRole.BOMB_CARRIER);
//            } else if (i < clearerCount) {
//                // 清道夫/火力吸引者：攻击性最强，优先交火，击杀障碍
//                bot.aiController.setTacticalObjective(targetSite, AIController.TacticRole.DISTRACTION);
//            } else {
//                // 支援者：跟随在清道夫后方，协助击杀
//                bot.aiController.setTacticalObjective(targetSite, AIController.TacticRole.SUPPORT);
//            }
//        }
//    }
//
//    /**
//     * T方战术：侧翼包抄 (钳形攻势)。
//     * 1. 正面组：作为火力吸引者 (DISTRACTION)，牵制敌人。
//     * 2. 侧翼组：作为清道夫 (ENTRY/FLANK)，绕后清除主要障碍。
//     */
//    private void executePincer(List<Player> teammates, Site site) {
//        Point2D.Double targetSite = getSiteCenter(site);
//        Point2D.Double spawnCenter = getSiteCenter(getSpawnAreasForTeam(this.team));
//        Point2D.Double flankPoint = pathfinder.calculateFlankPoint(spawnCenter, targetSite);
//
//        int flankGroupSize = teammates.size() / 2; // 侧翼组和正面组人数对半
//
//        Player bombCarrier = teammates.stream().filter(p -> p.hasBomb).findFirst().orElse(null);
//        // 确保C4携带者在正面组，不参与侧翼绕后
//        if (bombCarrier != null && teammates.indexOf(bombCarrier) < flankGroupSize) {
//            Collections.swap(teammates, teammates.indexOf(bombCarrier), flankGroupSize);
//        }
//
//        for (int i = 0; i < teammates.size(); i++) {
//            Player bot = teammates.get(i);
//            if (i < flankGroupSize) {
//                // 侧翼组：绕后清除障碍
//                bot.aiController.setTacticalObjective(flankPoint, AIController.TacticRole.FLANK);
//            } else {
//                // 正面组：吸引火力、牵制
//                if (bot == bombCarrier) {
//                    bot.aiController.setTacticalObjective(targetSite, AIController.TacticRole.BOMB_CARRIER);
//                } else {
//                    bot.aiController.setTacticalObjective(targetSite, AIController.TacticRole.DISTRACTION);
//                }
//            }
//        }
//    }
//
//    /**
//     * T方战术：分推。
//     * 1. 人多的主攻路线：分配 ENTRY 和 BOMB_CARRIER。
//     * 2. 人少的佯攻路线：分配 DISTRACTION，其任务是清除路径上的敌人。
//     */
//    private void executeSplit(List<Player> teammates, Site primarySite, int primaryGroupSize) {
//        Site secondarySite = (primarySite == Site.A) ? Site.B : Site.A;
//        Point2D.Double primaryTarget = getSiteCenter(primarySite);
//        Point2D.Double secondaryTarget = getSiteCenter(secondarySite);
//
//        Player bombCarrier = teammates.stream().filter(p -> p.hasBomb).findFirst().orElse(null);
//
//        // 确保C4在人多的一路 (主攻路线)
//        if (bombCarrier != null && teammates.indexOf(bombCarrier) >= primaryGroupSize) {
//            Collections.swap(teammates, teammates.indexOf(bombCarrier), 0); // 将带包者移到主攻队的第一位
//        }
//
//        for (int i = 0; i < teammates.size(); i++) {
//            Player bot = teammates.get(i);
//            if (i < primaryGroupSize) {
//                // 主攻队：执行任务
//                if (bot == bombCarrier) {
//                    bot.aiController.setTacticalObjective(primaryTarget, AIController.TacticRole.BOMB_CARRIER);
//                } else {
//                    bot.aiController.setTacticalObjective(primaryTarget, AIController.TacticRole.ENTRY);
//                }
//            } else {
//                // 佯攻队：清除敌人 (ROAD_CLEARER/DISTRACTION)
//                bot.aiController.setTacticalObjective(secondaryTarget, AIController.TacticRole.DISTRACTION);
//            }
//        }
//    }
//
//
//    /**
//     * CT方战术：回防拆包。
//     * 1. 阶段一 (CLEARING_SITE)：所有人都被分配 ENTRY 角色（清道夫/突击手），优先交火。
//     * 2. 阶段二 (SECURING_FOR_DEFUSE)：
//     * - 拆弹手 (DEFUSER)：最高任务优先级，尽快拆包。
//     * - 护卫 (DEFENDER)：原地坚守，清除周围敌人（阻碍）。
//     */
//    private void executeRetake(List<Player> teammates) {
//        if (!gameState.isBombPlanted()) {
//            if (currentTactic == Tactic.EXECUTE_RETAKE) {
//                currentTactic = Tactic.IDLE;
//            }
//            return;
//        }
//
//        if (this.bombSiteLocation == null) {
//            this.bombSiteLocation = findBombSiteLocation();
//            if (this.bombSiteLocation == null) {
//                logger.accept("[COMMANDER CT] 错误: 炸弹已安放但无法定位A/B点。");
//                return;
//            }
//        }
//
//        if (!isSiteConsideredClear(this.bombSiteLocation)) {
//            // 阶段一：清道夫全员突击
//            executePhase_ClearSite(teammates, this.bombSiteLocation);
//        }
//        else {
//            // 阶段二：掩护拆包
//            executePhase_SecureAndDefuse(teammates);
//        }
//    }
//
//    /**
//     * 阶段一：清空包点。命令所有AI强攻，作为清道夫。
//     */
//    private void executePhase_ClearSite(List<Player> teammates, Site plantedSite) {
//        Point2D.Double targetSiteCenter = getSiteCenter(plantedSite);
//
//        // [核心修改] 所有人都被赋予 ENTRY 角色，其首要目标就是击杀！
//        for (Player bot : teammates) {
//            bot.aiController.setTacticalObjective(targetSiteCenter, AIController.TacticRole.ENTRY);
//        }
//
//        if (isSiteConsideredClear(plantedSite)) {
////            logger.accept("[COMMANDER CT] Site " + plantedSite + " appears clear. Transitioning to Phase 2: Secure and Defuse.");
//            this.currentRetakePhase = RetakePhase.SECURING_FOR_DEFUSE;
//            executePhase_SecureAndDefuse(teammates);
//        }
//    }
//
//    /**
//     * 阶段二：掩护与拆弹。
//     */
//    private void executePhase_SecureAndDefuse(List<Player> teammates) {
//        if (!isSiteConsideredClear(bombSiteLocation)) {
//            logger.accept("[COMMANDER CT] ENEMY PRESENCE DETECTED! Reverting to Phase 1: Clearing Site.");
//            currentRetakePhase = RetakePhase.CLEARING_SITE;
//            designatedDefuserId = null;
//            executePhase_ClearSite(teammates, this.bombSiteLocation);
//            return;
//        }
//
//        Point2D.Double bombPosition = gameState.getBombPosition();
//        Player currentDefuser = (designatedDefuserId != null) ? gameState.getPlayerById(designatedDefuserId) : null;
//
//        if (currentDefuser == null || !currentDefuser.isAlive()) {
//            Player newDefuser = teammates.stream()
//                    .min(Comparator.comparing((Player p) -> p.hasDefuseKit ? 0 : 1)
//                            .thenComparingDouble(p -> p.position.distanceSq(bombPosition)))
//                    .orElse(null);
//
//            if (newDefuser != null) {
//                designatedDefuserId = newDefuser.id;
//                currentDefuser = newDefuser;
////                logger.accept("[COMMANDER CT - Phase 2] " + currentDefuser.name + " is now the DEFUSER.");
//            } else {
//                designatedDefuserId = null;
//                return;
//            }
//        }
//
//        // 拆弹手：最高任务优先级
//        currentDefuser.aiController.setTacticalObjective(bombPosition, AIController.TacticRole.DEFUSER);
//
//        for (Player guardian : teammates) {
//            if (guardian == currentDefuser) continue;
//
//            // 护卫：清除敌人（阻碍）优先级
//            if (guardian.aiController.getTacticRole() != AIController.TacticRole.DEFENDER) {
//                // 让护卫去 C4 周围找掩体站桩
//                Point2D.Double defensePos = guardian.aiController.findCover(bombPosition);
//
//                if (defensePos != null) {
//                    // 让护卫前往最好的掩体位置
//                    guardian.aiController.setTacticalObjective(defensePos, AIController.TacticRole.DEFENDER);
//                } else {
//                    // 如果找不到掩体，就去 C4 旁边站着
//                    guardian.aiController.setTacticalObjective(bombPosition, AIController.TacticRole.DEFENDER);
//                }
//            }
//        }
//    }
//
//    // =================================================================================
//    // 原有辅助方法（保持不变）
//    // =================================================================================
//
//
//    /**
//     * [新增] 寻找地图上离所有已知敌人最远的角落。
//     * 用于给保枪的AI提供一个明确、安全的逃跑目标点。
//     * @return 地图四个角中最安全的一个点的坐标。
//     */
//    private Point2D.Double findSafestCorner() {
//        List<Player> enemies = gameState.getPlayers().stream()
//                .filter(p -> p.team != this.team && p.isAlive())
//                .collect(Collectors.toList());
//
//        if (enemies.isEmpty()) {
//            // 如果看不到任何敌人，就随便找个离自己出生点最远的角落
//            Point2D.Double spawn = getSiteCenter(getSpawnAreasForTeam(this.team));
//            return (spawn.distance(0, 0) > spawn.distance(gameState.width, gameState.height)) ?
//                    new Point2D.Double(50, 50) : new Point2D.Double(gameState.width - 50, gameState.height - 50);
//        }
//
//        Point2D.Double[] corners = {
//                new Point2D.Double(50, 50), // 左上
//                new Point2D.Double(gameState.width - 50, 50), // 右上
//                new Point2D.Double(50, gameState.height - 50), // 左下
//                new Point2D.Double(gameState.width - 50, gameState.height - 50) // 右下
//        };
//
//        Point2D.Double safestCorner = corners[0];
//        double maxMinDistance = 0;
//
//        // 遍历每个角落，计算它与“最近的那个敌人”的距离。我们想要找到这个“最小距离”最大的那个角落。
//        for (Point2D.Double corner : corners) {
//            double minDistanceToEnemy = Double.MAX_VALUE;
//            for (Player enemy : enemies) {
//                minDistanceToEnemy = Math.min(minDistanceToEnemy, corner.distance(enemy.position));
//            }
//
//            if (minDistanceToEnemy > maxMinDistance) {
//                maxMinDistance = minDistanceToEnemy;
//                safestCorner = corner;
//            }
//        }
//        return safestCorner;
//    }
//    /**
//     * 根据团队平均经济，决定本回合的经济策略。
//     */
//
//
//
//
//
//
//
//    /**
//     * [最终完整版] T方指挥官的核心决策大脑。
//     * 实现了“包点锁定”机制，让进攻更具决定性。
//     * 决策优先级如下：
//     * 1. 最高优先级：响应C4相关的紧急事件（掉落、安放）。
//     * 2. 次高优先级：检查是否有队友已踏入包点，一旦发现，立即锁定该点为总攻目标，不再更改。
//     * 3. 中期决策：当队伍损失惨重时，分析伤亡情况，攻击薄弱点。
//     * 4. 开局决策：在回合开局阶段，随机选择一种初始进攻战术。
//     * 5. 默认：坚决执行当前已选定的战术。
//     */
//
//
//    /**
//     * [新增] 辅助方法：检查是否有任何一个存活的T方队友正站在A或B包点内。
//     * @return 如果有，返回对应的包点 (Site.A 或 Site.B)，否则返回 null。
//     */
//
//
//
//
//    /**
//     * [新增] 执行“英雄枪”战术。
//     * 指定一个最有钱的玩家为英雄，并命令其他所有队友作为护卫跟随他。
//     * @param teammates 所有存活的本方AI队友
//     */
//    private void executeHeroRifle(List<Player> teammates) {
//        // 1. 选举出本回合的“英雄”
//        Player hero = getRichestPlayer();
//
//        // 如果找不到英雄（比如都死了），则中止此战术
//        if (hero == null ||  hero.aiController == null) {
//            return;
//        }
//
//        // 2. 决定英雄的进攻目标（例如，随机一个包点）
//        // 我们需要确保英雄有一个明确的进攻方向，否则护卫们也不知道该往哪跟
//        if ( hero.aiController.getTacticRole() != AIController.TacticRole.ENTRY) {
//            Site heroObjectiveSite = rand.nextBoolean() ? Site.A : Site.B;
//            Point2D.Double heroObjectivePoint = getSiteCenter(heroObjectiveSite);
//            hero.aiController.setTacticalObjective(heroObjectivePoint, AIController.TacticRole.ENTRY);
////            logger.accept("[HERO RIFLE] " + hero.name + " is the HERO, objective is Site " + heroObjectiveSite);
//        }
//
//        // 3. 为所有其他“小弟”分配护卫任务
//        for (Player bot : teammates) {
//            if (bot == hero) {
//                continue; // 英雄本人不需要护卫任务
//            }
//            // 命令小弟：你的角色是护卫，你的目标是跟随大哥，大哥要去哪你就跟到哪
//            bot.aiController.setTacticalObjective(hero.aiController.getTacticalObjective(), hero, AIController.TacticRole.BODYGUARD);
//        }
//    }
//    /**
//     * 战术 [2]: 前压 (肾上腺素模式)
//     * 命令所有AI主动出击，目标是T的出生点或必经之路，寻找早期击杀。
//     */
//    private void executeAggressivePush(List<Player> teammates) {
//        // 目标就是T的老家
//        Point2D.Double enemySpawn = getSiteCenter(getSpawnAreasForTeam(Player.Team.T));
//        if (enemySpawn == null) return;
//
//        logger.accept("[COMMANDER CT] Executing AGGRESSIVE PUSH towards enemy spawn.");
//        for (Player bot : teammates) {
//            // 所有人都被赋予“突击手”(ENTRY)角色，会变得极具攻击性
//            bot.aiController.setTacticalObjective(enemySpawn, AIController.TacticRole.ENTRY);
//        }
//    }
//
//    /**
//     *  战术 [3]: 包点交叉火力
//     * 在一个包点周围寻找多个互相掩护的防守点，形成交叉火力网。
//     */
//    private void executeSiteCrossfire(List<Player> teammates) {
//        // 随机选择一个包点进行重点布防
//        Site targetSite = rand.nextBoolean() ? Site.A : Site.B;
//        Point2D.Double siteCenter = getSiteCenter(targetSite);
//        if (siteCenter == null) return;
//
//        logger.accept("[COMMANDER CT] Executing SITE CROSSFIRE setup at Site " + targetSite);
//
//        List<Point2D.Double> defensePositions = findGoodDefensivePositions(siteCenter, teammates.size());
//
//        if (defensePositions.isEmpty()) {
//            // 如果找不到好的架枪点，就退回常规防守
//            executeBalancedDefense(teammates);
//            return;
//        }
//
//        // 将每个AI分配到一个计算出的防守点
//        for (int i = 0; i < teammates.size(); i++) {
//            Player bot = teammates.get(i);
//            // 使用 % 运算符确保即使点位不够，也能循环分配
//            Point2D.Double position = defensePositions.get(i % defensePositions.size());
//            bot.aiController.setTacticalObjective(position, AIController.TacticRole.DEFENDER);
//        }
//    }
//
//    /**
//     * 战术 [4]: 分散控图
//     * 在A、B点以及中路关键位置都布置防守力量，以获取信息和控制地图。
//     */
//    private void executeMidControl(List<Player> teammates) {
//        if (teammates.size() < 3) {
//            // 人太少，无法执行此战术，退回常规防守
//            executeBalancedDefense(teammates);
//            return;
//        }
//
//        logger.accept("[COMMANDER CT] Executing MID CONTROL defense.");
//
//        Point2D.Double siteA = getSiteCenter(Site.A);
//        Point2D.Double siteB = getSiteCenter(Site.B);
//        if (siteA == null || siteB == null) return;
//
//        // 1. 分配A点和B点的“锚点”防守员
//        teammates.get(0).aiController.setTacticalObjective(siteA, AIController.TacticRole.DEFENDER);
//        teammates.get(1).aiController.setTacticalObjective(siteB, AIController.TacticRole.DEFENDER);
//
//        // 2. 将剩余的AI均匀分配在A点和B点的连线上
//        List<Player> midPlayers = teammates.subList(2, teammates.size());
//        for (int i = 0; i < midPlayers.size(); i++) {
//            Player bot = midPlayers.get(i);
//            // 计算插值点的位置，例如3个人就分别在1/4, 2/4, 3/4处
//            double factor = (double)(i + 1) / (midPlayers.size() + 1);
//            double midX = siteA.x + factor * (siteB.x - siteA.x);
//            double midY = siteA.y + factor * (siteB.y - siteA.y);
//            Point2D.Double midPoint = new Point2D.Double(midX, midY);
//
//            // 【关键】检查这个点是否在墙里，如果在，就找旁边能走的地方
//            if (!pathfinder.isWalkable(midPoint)) {
//                // 尝试在周围小范围搜索一个可走的位置
//                for (int attempt = 0; attempt < 10; attempt++) {
//                    Point2D.Double tempPoint = new Point2D.Double(
//                            midPoint.x + (rand.nextDouble() - 0.5) * 100,
//                            midPoint.y + (rand.nextDouble() - 0.5) * 100
//                    );
//                    if (pathfinder.isWalkable(tempPoint)) {
//                        midPoint = tempPoint;
//                        break;
//                    }
//                }
//            }
//            bot.aiController.setTacticalObjective(midPoint, AIController.TacticRole.DEFENDER);
//        }
//    }
//
//    /**
//     * 辅助方法：在一个点周围寻找多个好的防守位置
//     */
//    private List<Point2D.Double> findGoodDefensivePositions(Point2D.Double center, int count) {
//        List<Point2D.Double> positions = new ArrayList<>();
//        int attempts = 0;
//        while (positions.size() < count && attempts < 50) {
//            attempts++;
//            // 在一个环形区域内随机生成一个候选点
//            double angle = rand.nextDouble() * 2 * Math.PI;
//            double radius = 100 + rand.nextDouble() * 200; // 距离中心100到300像素
//            Point2D.Double candidate = new Point2D.Double(center.x + Math.cos(angle) * radius, center.y + Math.sin(angle) * radius);
//
//            // 检查点是否有效：可走、有掩护、并且离已经找到的点不太近
//            if (pathfinder.isWalkable(candidate) && hasCoverFromCommonApproach(candidate) && isSpatiallySeparated(candidate, positions)) {
//                positions.add(candidate);
//            }
//        }
//        return positions;
//    }
//
//    /**
//     * 辅助方法：检查一个点是否与列表中的其他点保持了足够的距离
//     */
//    private boolean isSpatiallySeparated(Point2D.Double point, List<Point2D.Double> others) {
//        final double MIN_SEPARATION = Player.SIZE * 3; // 至少相隔3个身位
//        for (Point2D.Double other : others) {
//            if (point.distance(other) < MIN_SEPARATION) {
//                return false;
//            }
//        }
//        return true;
//    }
//
//    /**
//     * 辅助方法：检查一个位置是否有掩体可以抵挡来自T方常见进攻路线的火力
//     */
//    public boolean hasCoverFromCommonApproach(Point2D.Double position) {
//        List<Rectangle> enemySpawns = getSpawnAreasForTeam(Player.Team.T);
//        if (position == null || enemySpawns.isEmpty()) return false;
//
//        Point2D.Double enemySpawnCenter = getSiteCenter(enemySpawns);
//
//        // 检查从敌人出生点到该防守位置的视线是否被阻挡
//        Line2D.Double lineOfSight = new Line2D.Double(enemySpawnCenter, position);
//        for (Shape obs : gameState.getObstacles()) {
//            if (obs.intersects(lineOfSight.getBounds2D()) && GameState.getLineShapeIntersections(lineOfSight, obs) != null) {
//                return true; // 视线被挡住了，说明有掩体
//            }
//        }
//        return false;
//    }
//    /**
//     * 执行防守掉落C4的战术。
//     * 命令所有存活的CT单位前往C4掉落的位置进行防守。
//     * @param teammates 所有存活的CT方AI队友
//     */
//    private void executeGuardDroppedBomb(List<Player> teammates) {
//        GameState.DroppedItem bomb = gameState.getDroppedBomb();
//
//        // 安全检查：如果在执行这个战术的瞬间，C4被T捡走了，
//        // 我们需要立即中止，并让指挥官切换回常规的均衡防守战术。
//        if (bomb == null) {
//            logger.accept("[COMMANDER CT] Bomb was picked up. Reverting to DEFEND_BALANCED.");
//            currentTactic = Tactic.DEFEND_BALANCED;
//            executeBalancedDefense(teammates); // 立即执行一次新战术
//            return;
//        }
//
////        logger.accept("[COMMANDER CT] Ordering all units to guard dropped bomb at (" + (int)bomb.position.x + ", " + (int)bomb.position.y + ").");
//
//        // 向所有存活的AI下达新命令：以C4掉落点为目标，执行防守任务。
//        for (Player bot : teammates) {
//            bot.aiController.setTacticalObjective(bomb.position, AIController.TacticRole.DEFENDER);
//        }
//    }
//    /**
//     * 判断炸弹被安放在哪个点位。
//     */
//    private Site findBombSiteLocation() {
//        Point2D.Double bombPos = gameState.getBombPosition();
//        if (bombPos == null) return null;
//
//        Rectangle siteARect = gameState.getBombSiteA();
//        Rectangle siteBRect = gameState.getBombSiteB();
//
//        // 定义一个容忍距离，例如 150 像素 (足以覆盖包点周边区域)
//        final double TOLERANCE = 450.0;
//
//        // 1. 检查 A 点：如果炸弹的精确坐标在 A 区域内，或离 A 区域中心很近
//        if (siteARect != null) {
//            // 如果严格包含，立即返回
//            if (siteARect.contains(bombPos)) {
//                return Site.A;
//            }
//            // 否则，检查炸弹是否离 A 点的中心足够近
//            Point2D.Double siteACenter = getSiteCenter(Site.A);
//            if (siteACenter != null && bombPos.distance(siteACenter) < TOLERANCE) {
//                return Site.A;
//            }
//        }
//
//        // 2. 检查 B 点 (逻辑同上)
//        if (siteBRect != null) {
//            if (siteBRect.contains(bombPos)) {
//                return Site.B;
//            }
//            Point2D.Double siteBCenter = getSiteCenter(Site.B);
//            if (siteBCenter != null && bombPos.distance(siteBCenter) < TOLERANCE) {
//                return Site.B;
//            }
//        }
//
//        // 如果找不到，尝试检查最近的掉落物品（如果 C4 曾被丢弃）
//        GameState.DroppedItem droppedBomb = gameState.getDroppedBomb();
//        if (droppedBomb != null && droppedBomb.isBomb) {
//            // 如果丢下的C4在A点附近
//            if (siteARect != null && droppedBomb.position.distance(getSiteCenter(Site.A)) < TOLERANCE) {
//                // 这是一个猜测，但可能是最接近的区域
//                return Site.A;
//            }
//            // 如果丢下的C4在B点附近
//            if (siteBRect != null && droppedBomb.position.distance(getSiteCenter(Site.B)) < TOLERANCE) {
//                return Site.B;
//            }
//        }
//
//        // 仍然找不到，返回 null
//        return null;
//    }
//
//    /**
//     * [新增] 检查一个包点是否可以被认为是“安全的”，以便开始拆弹。
//     * @param site 要检查的点位
//     * @return 如果点位附近没有已知敌人，则返回 true
//     */
//    private boolean isSiteConsideredClear(Site site) {
//        Point2D.Double siteCenter = getSiteCenter(site);
//        if (siteCenter == null) return false;
//
//        double secureRadius = 600.0; // 定义一个较大的“安全半径”
//
//        // 检查在这个半径内，是否还有任何存活的、已知的敌方玩家
//        boolean enemyNearby = gameState.getPlayers().stream()
//                .anyMatch(p -> p.team != this.team && p.isAlive() && p.position.distanceSq(siteCenter) < secureRadius * secureRadius);
//
//        return !enemyNearby; // 如果没有敌人，则返回true（安全）
//    }
//
//    private void executeRetrieveBomb(List<Player> teammates) {
//        GameState.DroppedItem bomb = gameState.getDroppedBomb();
//        if (bomb != null) {
//            for (Player bot : teammates) {
//                bot.aiController.setTacticalObjective(bomb.position, AIController.TacticRole.SUPPORT);
//            }
//        }
//    }
//
//    /**
//     *  重置所有AI的战术指令。
//     * 在发布新的全局战术前调用，以确保所有单位都能接收新指令，而不是被旧指令困住。
//     */
//    private void resetAllBotObjectives() {
//        // [日志 3.1] 记录调用重置的上下文
////        logger.accept("[COMMANDER " + team + "] !!! Resetting ALL bot objectives and roles to IDLE !!!");
//
//        List<Player> teammates = getAliveTeammates();
//        for (Player bot : teammates) {
//            if (bot.aiController != null) {
//                // 在清除前记录AI的当前状态 (这个日志会被 AIController 打印)
//                bot.aiController.clearTacticalObjective();
//            }
//        }
//
//        // [日志 3.2] 追踪 currentTactic 是否被非法重置
////        logger.accept(String.format("[COMMANDER %s] WARNING: Objectives Reset Complete. CURRENT TACTIC IS NOW: %s",
////                team,
////                currentTactic) // 注意：这里 currentTactic 应该还是旧值 (RUSH_A)
////        );
//    }
//
//    private void executeBalancedDefense(List<Player> teammates) {
//        Point2D.Double siteACenter = getSiteCenter(Site.A);
//        Point2D.Double siteBCenter = getSiteCenter(Site.B);
//
//        // ======================= [核心修复：增加目标点存在性检查] =======================
//        // 解释：在分配任务前，先检查包点是否存在。
//
//        // 情况1: 两个包点都不存在
//        if (siteACenter == null && siteBCenter == null) {
//            logger.accept("[COMMANDER " + team + "] Error: No valid bomb sites found on this map! Bots will patrol.");
//            // 既然没地方可守，就让所有AI自由巡逻
//            executeIdle(teammates);
//            return;
//        }
//
//        // 情况2: 只有一个包点存在 (例如，只有A点)
//        if (siteACenter != null && siteBCenter == null) {
//            logger.accept("[COMMANDER " + team + "] Warning: Only Site A found. All units will defend Site A.");
//            for (Player bot : teammates) {
//                bot.aiController.setTacticalObjective(siteACenter, AIController.TacticRole.DEFENDER);
//            }
//            return;
//        }
//        // 同理，如果只有B点
//        if (siteBCenter != null && siteACenter == null) {
//            logger.accept("[COMMANDER " + team + "] Warning: Only Site B found. All units will defend Site B.");
//            for (Player bot : teammates) {
//                bot.aiController.setTacticalObjective(siteBCenter, AIController.TacticRole.DEFENDER);
//            }
//            return;
//        }
//        // ======================================================================================
//
//
//        // 情况3: 两个包点都存在，执行原有的均衡防守逻辑
//        long currentDefendersA = teammates.stream()
//                .filter(p -> p.aiController.getTacticRole() == AIController.TacticRole.DEFENDER && siteACenter.equals(p.aiController.getTacticalObjective()))
//                .count();
//        long currentDefendersB = teammates.stream()
//                .filter(p -> p.aiController.getTacticRole() == AIController.TacticRole.DEFENDER && siteBCenter.equals(p.aiController.getTacticalObjective()))
//                .count();
//
//        for (Player bot : teammates) {
//            Point2D.Double currentObjective = bot.aiController.getTacticalObjective();
//            AIController.TacticRole currentRole = bot.aiController.getTacticRole();
//
//            if (currentRole == AIController.TacticRole.DEFENDER && (siteACenter.equals(currentObjective) || siteBCenter.equals(currentObjective))) {
//                continue;
//            }
//
//            if (currentDefendersA <= currentDefendersB) {
//                bot.aiController.setTacticalObjective(siteACenter, AIController.TacticRole.DEFENDER);
//                currentDefendersA++;
//            } else {
//                bot.aiController.setTacticalObjective(siteBCenter, AIController.TacticRole.DEFENDER);
//                currentDefendersB++;
//            }
//        }
//    }
//
//    private void executeRotate(List<Player> teammates, Site targetSite) {
//        for (Player bot : teammates) {
//            bot.aiController.setTacticalObjective(getSiteCenter(targetSite), AIController.TacticRole.ROTATOR);
//        }
//    }
//
//    private void executeSave(List<Player> teammates) {
//        Point2D.Double safeZone = findSafestCorner();
//        for (Player bot : teammates) {
//            // [关键修复!] 只有在AI当前不是SAVER时，才下达新的保枪指令
//            if (bot.aiController != null && bot.aiController.getTacticRole() != AIController.TacticRole.SAVER) {
//                bot.aiController.setTacticalObjective(safeZone, AIController.TacticRole.SAVER);
//            }
//            // 如果AI已经是SAVER了，就让它继续执行之前的保枪路径，不要打断它！
//        }
//    }
//    /**
//     * 执行 Idle
//     */
//    private void executeIdle(List<Player> teammates) {
//        // [日志 4] 记录 executeIdle 被调用 (放在循环外，只打印一次)
////        logger.accept(String.format("[COMMANDER %s] !!! EXECUTING IDLE TACTIC !!! Clearing objectives for %d bots.", team, teammates.size()));
//
//        for (Player bot : teammates) {
//            // 只对角色不是 IDLE 的 AI 调用 clear，避免不必要的日志刷屏
//            // 只有当 AI 的角色不是 IDLE 时，才需要清除（并触发 AIController 内部的日志）
//            if (bot.aiController != null && bot.aiController.getTacticRole() != AIController.TacticRole.IDLE) {
//                // logger.accept("   -> Clearing objective for " + bot.name + " (Role was " + bot.aiController.getTacticRole() + ")"); // 这个详细日志可以注释掉
//                bot.aiController.clearTacticalObjective();
//            }
//        }
//    }
//
//    // =================================================================================
//    // 情报分析 (The Eyes & Ears)
//    // =================================================================================
//
//    private Map<Site, Double> analyzeSiteThreats() {
//        Map<Site, Double> threats = new HashMap<>();
//        threats.put(Site.A, 0.0);
//        threats.put(Site.B, 0.0);
//
//        Point2D.Double siteA = getSiteCenter(Site.A);
//        Point2D.Double siteB = getSiteCenter(Site.B);
//        if (siteA == null || siteB == null) return threats;
//
//        long currentTime = System.currentTimeMillis();
//        double threatRange = 400.0;
//
//        // 分析最近的死亡事件
//        gameState.getRecentDeaths(5000).forEach(death -> {
//            if (death.position.distance(siteA) < threatRange) {
//                threats.compute(Site.A, (k, v) -> v + (death.team == this.team ? 1.5 : 1.0)); // 队友死亡威胁更大
//            }
//            if (death.position.distance(siteB) < threatRange) {
//                threats.compute(Site.B, (k, v) -> v + (death.team == this.team ? 1.5 : 1.0));
//            }
//        });
//
//        // 分析枪声 (这是一个简化实现)
//        gameState.getPlayers().forEach(p -> {
//            if (p.isAlive() && p.isShooting && p.team != this.team) {
//                if (p.position.distance(siteA) < threatRange) threats.compute(Site.A, (k, v) -> v + 0.5);
//                if (p.position.distance(siteB) < threatRange) threats.compute(Site.B, (k, v) -> v + 0.5);
//            }
//        });
//
//        return threats;
//    }
//
//    private double analyzeTeamEconomy() {
//        List<Player> teammates = gameState.getPlayers().stream()
//                .filter(p -> p.team == this.team && p.isAI)
//                .collect(Collectors.toList());
//        if (teammates.isEmpty()) return 5000;
//
//        return teammates.stream().mapToDouble(p -> p.money).average().orElse(0);
//    }
//
//
//    /**
//     *  检查是否应该下达保枪指令。
//     * 触发条件：
//     * 1. 存活队友数量远少于敌人（例如 1v3, 1v4...）。
//     * 2. CT方在炸弹安放后，处于巨大的人数劣势，并且时间所剩无几，回防无望。
//     */
//    private boolean shouldCallSave(double avgMoney) {
//
//        // 手枪局或经济非常差的回合，依然保留经济保枪的判断
//        if (gameState.currentRound <= 3 || (gameState.currentRound >= 13 && gameState.currentRound < 16) || avgMoney > 1500) {}
//        else {
//            // 如果全队一把长枪都没有，就应该保枪
//            long rifles = gameState.getPlayers().stream()
//                    .filter(p -> p.team == this.team && p.isAlive() && p.primaryWeapon != null && p.primaryWeapon.getWeaponType().isLongRange())
//                    .count();
//            if (rifles == 0) {
////                logger.accept("[COMMANDER " + team + "] Economy critical. Calling SAVE.");
//                return true;
//            }
//        }
//
//        // 核心：基于人数劣势的战术保枪
//        long aliveTeammates = gameState.getPlayers().stream().filter(p -> p.team == this.team && p.isAlive()).count();
//        long aliveEnemies = gameState.getPlayers().stream().filter(p -> p.team != this.team && p.isAlive()).count();
//
//        // 如果我方只剩1人，而敌人还有3个或更多，则必须保枪
//        if (aliveTeammates == 1 && aliveEnemies >= 3) {
////            logger.accept("[COMMANDER " + team + "] Overwhelming odds (1 vs " + aliveEnemies + "). Calling SAVE.");
//            return true;
//        }
//
//        // 针对CT方的特殊情况：炸弹已安放，回防希望渺茫
//        if (team == Player.Team.CT && gameState.isBombPlanted()) {
//            long timeRemaining = gameState.getRoundTimeRemaining();
//            // 如果人数处于劣势(例如2v4)，并且时间不多了(少于20秒)，回防风险太高，不如保枪
//            if (aliveTeammates < aliveEnemies && timeRemaining < 20000) {
////                logger.accept("[COMMANDER CT] Post-plant situation unwinnable. Calling SAVE.");
//                return true;
//            }
//        }
//
//        return false;
//    }
//
//
//    // =================================================================================
//    // 辅助方法
//    // =================================================================================
//
//    private Point2D.Double getSiteCenter(Site site) {
//        Rectangle rect = (site == Site.A) ? gameState.getBombSiteA() : gameState.getBombSiteB();
//        return (rect != null) ? new Point2D.Double(rect.getCenterX(), rect.getCenterY()) : null;
//    }
//
//    private Point2D.Double getSiteCenter(List<Rectangle> areas) {
//        if (areas == null || areas.isEmpty()) return new Point2D.Double(gameState.width/2.0, gameState.height/2.0);
//        double totalX = 0, totalY = 0;
//        for(Rectangle r : areas) {
//            totalX += r.getCenterX();
//            totalY += r.getCenterY();
//        }
//        return new Point2D.Double(totalX / areas.size(), totalY / areas.size());
//    }
//
//    private List<Rectangle> getSpawnAreasForTeam(Player.Team team) {
//        return team == Player.Team.T ? gameState.getTSpawnAreas() : gameState.getCtSpawnAreas();
//    }
//
//    private long getDefendersAtSite(Site site) {
//        Point2D.Double siteCenter = getSiteCenter(site);
//        if (siteCenter == null) return 0;
//        return gameState.getPlayers().stream()
//                .filter(p -> p.isAI && p.team == Player.Team.CT && p.isAlive() && p.aiController != null
//                        && p.aiController.getTacticalObjective() != null
//                        && p.aiController.getTacticalObjective().equals(siteCenter))
//                .count();
//    }
//
//    /**
//     * [已升级V2] 执行分阶段的防守已安放C4战术。
//     * 包含时间判断，能在最后时刻命令大部分队员撤退，并留下断后人员。
//     * @param teammates 所有存活的T方AI队友
//     */
//    private void executeDefendPlantedSite(List<Player> teammates) {
//        if (teammates.isEmpty() || !gameState.isBombPlanted()) {
//            return;
//        }
//
//        Point2D.Double bombPosition = gameState.getBombPosition();
//        if (bombPosition == null) return;
//
//        // 1. **获取关键信息：炸弹剩余时间**
//        long remainingTime = gameState.getRoundTimeRemaining();
//        final long RETREAT_TIME_MS = 10000; // 10秒
//
//        // 2. **根据时间进入不同战术阶段**
//        if (remainingTime > RETREAT_TIME_MS) {
//            // --- 阶段一：坚守阵地 ---
////            logger.accept("[COMMANDER T | HOLDING] Bomb timer > 10s. All units defending positions.");
//
//            for (Player bot : teammates) {
//                // 如果AI没有防守任务或位置不佳，为其分配一个新的防守点
//                if (bot.aiController.getTacticRole() != AIController.TacticRole.DEFENDER ||
//                        bot.aiController.getTacticalObjective() == null ||
//                        bot.position.distance(bombPosition) > 300) { // 离包太远了，拉回来
//
//                    double angle = rand.nextDouble() * 2 * Math.PI;
//                    double defenseDistance = 100 + rand.nextDouble() * 150;
//                    Point2D.Double defensePosition = new Point2D.Double(
//                            bombPosition.x + Math.cos(angle) * defenseDistance,
//                            bombPosition.y + Math.sin(angle) * defenseDistance
//                    );
//                    bot.aiController.setTacticalObjective(defensePosition, AIController.TacticRole.DEFENDER);
//                }
//            }
//        } else {
//            // --- 阶段二：战术撤退 ---
//            logger.accept("[COMMANDER T | RETREATING] Bomb timer < 10s! Initiating tactical retreat.");
//
//            // 3. **决定留下多少人断后**
//            // 队伍人数大于3人时留2个，否则只留1个。确保至少有1人留下。
//            int rearguardCount = (teammates.size() > 3) ? 2 : 1;
//
//            // 为了方便，我们简单地将列表中的前 rearguardCount 个作为断后人员
//            // （更高级的可以根据血量、武器等来选择）
//
//            // 4. **分配撤退和断后任务**
//            Point2D.Double safeZone = getSiteCenter(getSpawnAreasForTeam(this.team)); // 撤退到出生点
//
//            for (int i = 0; i < teammates.size(); i++) {
//                Player bot = teammates.get(i);
//                if (i < rearguardCount) {
//                    // 这是断后人员
//                    if (bot.aiController.getTacticRole() != AIController.TacticRole.DEFENDER) {
//                        bot.aiController.setTacticalObjective(bombPosition, AIController.TacticRole.DEFENDER);
//                        logger.accept("[COMMANDER T | REARGUARD] " + bot.name + " is holding the site.");
//                    }
//                } else {
//                    // 这是需要撤退的人员
//                    if (bot.aiController.getTacticRole() != AIController.TacticRole.FLEEING_BOMB) {
//                        bot.aiController.setTacticalObjective(safeZone, AIController.TacticRole.FLEEING_BOMB);
//                        logger.accept("[COMMANDER T | RETREATING] " + bot.name + " is retreating to safety.");
//                    }
//                }
//            }
//        }
//    }
//
//
//}