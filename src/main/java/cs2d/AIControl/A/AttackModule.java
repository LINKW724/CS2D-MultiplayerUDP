// 文件: cs2d/AIControl/A/AttackModule.java
package cs2d.AIControl.A; // <--- 包路径已更改以匹配文件位置？

import cs2d.playerAndAi.Player;
import cs2d.playerAndAi.Weapon; // 确保导入 Weapon
import cs2d.server.AIService.AIInput;
import cs2d.server.GameState;
import cs2d.server.AIDifficulty;

// 穿透检查所需的导入
import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D; // 确保导入 Rectangle2D
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

// 确保导入 PathfindingModule 以访问 Pathfinder
import cs2d.AIControl.A.PathfindingModule;

/**
 * AI 攻击控制器 (AttackModule)。
 * 包含"急停" (Counter-Strafing) 逻辑。
 * 负责处理 AI 的瞄准（转头）和射击逻辑。
 * 当需要开火时，它会优先尝试停止移动，并返回用于急停的按键。
 */
public class AttackModule {

    // --- 核心引用 ---
    private final Player owner; // 此模块的所有者 (AI 玩家)
    private final AIDifficulty difficulty; // AI 难度设置
    private final Random rand = new Random(); // 随机数生成器，移到此处初始化

    // --- 内部状态 ---
    // private final Random rand = new Random(); // 移除重复的定义
    private double targetAngle; // 期望瞄准的角度
    private double currentAngle; // 当前平滑过渡中的角度
    private String lastTargetId = null; // 上一个目标的 ID
    private long targetAcquiredTime = 0; // 锁定当前目标的时间戳
    private long burstCooldownUntil = 0; // 连射冷却结束的时间戳
    private int shotsFiredAtCurrentTarget = 0; // 对当前目标已射击的次数
    private long lastCountedShotTime;

    // --- 狙击枪 & 机枪专项状态 ---
    private boolean sniperNeedsRetreat = false; // 狙击枪开火后是否需要撤退
    private double lmgSweepOffset = 0; // 机枪扫射偏移
    private boolean lmgSweepDirection = true; // 扫射方向
    private long lastLmgHitTime = 0; // 上次穿墙击中时间
    private Point2D.Double lastLmgHitPos = null; // 上次穿墙击中位置

    // --- 战斗身法 (Strafe) 状态 ---
    private long lastStrafeSwitchTime = 0; // 上次身法切换时间
    private boolean isStrafingLeft = false; // 当前是否在往左横移
    private long currentStrafeDuration = 0; // 当前身法的持续时间

    // --- 闪光弹状态 ---
    private long lastFlashTime = 0; // 上次被闪光的时间戳
    private long flashDuration = 0; // 闪光持续时间

    // --- 转向速度和精确度常量 ---
    private static final double BASE_TURN_SPEED_DEGREES = 4.5; // 基础转向速度 (度/帧) [由 1.5 增加到 4.5]

    public boolean isFlashed(long currentTime) {
        return currentTime < lastFlashTime + flashDuration;
    }

    private static final int SHOOT_TOTAL_NEED_NUM = 3; // (似乎与瞄准逻辑相关，保留)
    private static final double MOVEMENT_THRESHOLD = 0.1; // 判定为“正在移动”的速度阈值
    private static final double ACCURACY_STOP_THRESHOLD_SQ = 0.5 * 0.5; // 允许在低速移动时开火，提高响应速度
    private double currentAimErrorRad = Math.PI; // 默认为最大误差

    /**
     * 构造函数
     */
    public AttackModule(Player owner, AIDifficulty difficulty) {
        this.owner = Objects.requireNonNull(owner, "AttackModule 需要一个非空的 owner。");
        this.difficulty = Objects.requireNonNull(difficulty, "AttackModule 需要一个非空的 difficulty。");
        // 初始化角度为玩家当前角度
        this.currentAngle = owner.angle;
        this.targetAngle = owner.angle;
    }

    /**
     * 更新攻击模块的逻辑。
     */
    public AIInput update(Player primaryTarget, Point2D.Double lastKnownPosition, long currentTime) {
        return update(primaryTarget, lastKnownPosition, currentTime, AttackExecutionPolicy.STANDARD);
    }

    public AIInput update(Player primaryTarget, Point2D.Double lastKnownPosition, long currentTime,
            AttackExecutionPolicy policy) {
        return update(primaryTarget, lastKnownPosition, currentTime, policy, null);
    }

    /** An engagement key lets a mode retain reaction/recoil state while switching targets in one group. */
    public AIInput update(Player primaryTarget, Point2D.Double lastKnownPosition, long currentTime,
            AttackExecutionPolicy policy, String engagementKey) {
        if (policy == null) policy = AttackExecutionPolicy.STANDARD;
        if (!policy.acceptsTarget(owner, primaryTarget)) {
            primaryTarget = null;
            lastKnownPosition = null;
        }

        // --- 1. 处理闪光弹 ---
        if (currentTime < lastFlashTime + flashDuration) {
            // 被闪光时随机晃动准星
            this.targetAngle += (rand.nextDouble() - 0.5) * 1.5;
            this.shotsFiredAtCurrentTarget = 0; // 重置射击计数
            updateAimAngle(); // 更新角度
            // 返回随机射击的指令
            return new AIInput(new ArrayList<>(), this.currentAngle, policy.allowsBlindFire() && rand.nextDouble() < 0.1, false, false);
        }

        // --- 2. 处理目标切换 ---
        String currentTargetId = primaryTarget == null ? null
                : engagementKey == null ? primaryTarget.id : engagementKey;
        // 如果目标 ID 发生变化
        if (!Objects.equals(currentTargetId, lastTargetId)) {
            this.targetAcquiredTime = currentTime; // 重置反应计时器
            this.shotsFiredAtCurrentTarget = 0; // 重置射击计数
            this.burstCooldownUntil = 0; // 清除连射冷却
            if (this.owner != null)
                this.owner.shootTimeIndex = 0; // 添加 null 检查，重置压枪模式
            this.lastTargetId = currentTargetId; // 更新最后目标 ID
            this.lmgSweepOffset = 0; // 重置扫射
            this.lastCountedShotTime = owner.lastShotTime;
        }
        if (policy == AttackExecutionPolicy.ZOMBIE_SURVIVOR && owner.lastShotTime > lastCountedShotTime) {
            shotsFiredAtCurrentTarget++;
            lastCountedShotTime = owner.lastShotTime;
        }

        // --- 3. 武器决策与切换 (狙击手近战保护) ---
        Weapon wep = (owner != null) ? owner.getCurrentWeapon() : null;
        if (wep != null && wep.getWeaponType() == Weapon.WeaponType.SNIPER && primaryTarget != null) {
            double distToTarget = owner.position.distance(primaryTarget.position);
            if (distToTarget < 100) { // 敌人太近
                if (owner.secondaryWeapon != null && owner.currentSlot != 2) {
                    owner.switchToSlot(2); // 紧急切手枪
                }
            }
        }

        // --- 4. 攻击决策逻辑 ---
        boolean wantsToShoot = false; // AI 的“开火意图”
        boolean finalShouldShoot = false; // AI 最终“是否开火”
        List<String> counterKeys = new ArrayList<>(); // 用于急停的反向按键

        // --- 4a. 瞄准 & 开火意图决策 ---
        if (primaryTarget != null && primaryTarget.isAlive()) {
            aimAt(primaryTarget); // 瞄准目标

            // [真实难度优化] 如果 AI 刚刚被打，大幅缩短反应延迟
            long effectiveReactionMs = difficulty.reactionTimeMs;
            if (difficulty == AIDifficulty.REALISTIC && (currentTime - owner.lastDamageSourcePositionTime < 2000)) {
                effectiveReactionMs = 30; // 几乎瞬间
            }

            if (currentTime - targetAcquiredTime >= effectiveReactionMs) {
                // 即使有 primaryTarget，也可能只是被声音“透视”出来的。必须检查真实物理视线！
                PathfindingModule pm = owner.getPathfindingModule();
                if (pm != null && pm.hasLineOfSight(primaryTarget.position)) {
                    // 真有视线，直接开火
                    wantsToShoot = decideShooting(primaryTarget, currentTime, lastKnownPosition);
                } else {
                    // 没有视线（隔着墙），走穿透射击判定
                    wantsToShoot = policy.allowsBlindFire() && decideBlindFire(primaryTarget.position, currentTime);
                }
            }
        } else if (lastKnownPosition != null) { // 机枪盲射扫射逻辑
            if (wep != null && wep.getWeaponType() == Weapon.WeaponType.LMG) {
                updateLmgSweep(currentTime);
                aimAtWithOffset(lastKnownPosition, lmgSweepOffset);
            } else {
                aimAt(lastKnownPosition);
            }
            wantsToShoot = policy.allowsBlindFire() && decideBlindFire(lastKnownPosition, currentTime);
        }

        // --- 4b. 更新瞄准角度 ---
        updateAimAngle();

        // --- 4c. 射击精度和急停检查 ---
        double maxError = Math.toRadians(22.0); // 默认 22 度
        if (wep != null && wep.getWeaponType() == Weapon.WeaponType.SNIPER) {
            maxError = Math.toRadians(1.5); // 狙击枪必须极其精准 (1.5度)
        }

        // 是否需要强制急停 (长枪/手枪/机枪)
        // 如果是盲射(lkp != null && wantsToShoot)，无论什么武器都必须急停以保证穿透点的准确性
        boolean directSight = primaryTarget != null && owner.getPathfindingModule() != null
                && owner.getPathfindingModule().hasLineOfSight(primaryTarget.position);
        boolean isAttemptingBlindFire = lastKnownPosition != null && wantsToShoot
                && (policy == AttackExecutionPolicy.STANDARD || !directSight);
        boolean needsAccuracy = policy.requiresStop(wep == null ? null : wep.getWeaponType(),
                lastKnownPosition != null, wantsToShoot, directSight);

        // --- 4d. 战斗移动决策 (急停或身法) ---
        // 只有在“真正交火”时才执行身法。
        // “真正交火”指：看到敌人(hasLoS) 或者 正在尝试穿墙射击(isAttemptingBlindFire)。
        // 如果只是在搜寻目标 (仅有 lastKnownPosition 但没开火)，则不执行身法，防止在出生点或走廊里左右摩擦看起来像 BUG。
        PathfindingModule pm = owner.getPathfindingModule();
        boolean hasLoS = (primaryTarget != null && pm != null && pm.hasLineOfSight(primaryTarget.position));
        boolean isInCombat = hasLoS || isAttemptingBlindFire;

        if (isInCombat) {
            // 战斗身法状态机：在横移和停顿(射击)之间切换
            if (currentTime > lastStrafeSwitchTime + currentStrafeDuration) {
                isStrafingLeft = !isStrafingLeft;
                lastStrafeSwitchTime = currentTime;

                // 根据武器类型调整身法频率
                if (wep != null && wep.getWeaponType().isLongRange()) {
                    // 长枪：更长周期的拉扯 (600-1200ms)
                    currentStrafeDuration = 600 + rand.nextInt(601);
                } else {
                    // 冲锋枪/短枪：快速左右晃动 (200-500ms)
                    currentStrafeDuration = 200 + rand.nextInt(301);
                }
            }

            if (needsAccuracy) {
                // 步枪/手枪：执行“停-打-走”拉扯
                double currentSpeedSq = owner.vx * owner.vx + owner.vy * owner.vy;

                // 如果当前由于瞄准差或刚切换方向需要停稳
                // 让 AI 在大角度转向时就开始提前减速急停，缩短准备时间
                boolean shouldStopToShoot = (wantsToShoot && currentAimErrorRad < Math.toRadians(30.0));

                if (shouldStopToShoot) {
                    // 执行急停 (Counter-Strafe)
                    if (owner.vy < -MOVEMENT_THRESHOLD)
                        counterKeys.add("S");
                    else if (owner.vy > MOVEMENT_THRESHOLD)
                        counterKeys.add("W");
                    if (owner.vx < -MOVEMENT_THRESHOLD)
                        counterKeys.add("D");
                    else if (owner.vx > MOVEMENT_THRESHOLD)
                        counterKeys.add("A");
                } else {
                    // 不射击时执行横移
                    double strafeAngle = targetAngle + (isStrafingLeft ? Math.PI / 2 : -Math.PI / 2);
                    applyStrafeKeys(strafeAngle, counterKeys);
                }
            } else {
                // 冲锋枪/霰弹 shotgun：持续左右横移身法
                // 根据朝向目标的角度，计算出正交的物理按键方向 (WASD)
                double strafeAngle = targetAngle + (isStrafingLeft ? Math.PI / 2 : -Math.PI / 2);
                applyStrafeKeys(strafeAngle, counterKeys);
            }
        }

        // --- 4e. 最终射击决策 ---
        if (wantsToShoot) {
            boolean isAimAccurate = (this.currentAimErrorRad <= maxError);
            if (needsAccuracy) {
                double currentSpeedSq = owner.vx * owner.vx + owner.vy * owner.vy;
                if (currentSpeedSq <= ACCURACY_STOP_THRESHOLD_SQ) {
                    finalShouldShoot = isAimAccurate;
                    if (finalShouldShoot) {
                        applyBurstLogic(currentTime, wep, policy); // 只有真正开火才处理连射
                        if (wep.getWeaponType() == Weapon.WeaponType.SNIPER) {
                            this.sniperNeedsRetreat = true;
                        }
                    }
                } else {
                    finalShouldShoot = false; // 还没停稳，不能开火
                }
            } else {
                finalShouldShoot = isAimAccurate;
                if (finalShouldShoot) {
                    applyBurstLogic(currentTime, wep, policy); // 只有真正开火才处理连射
                }
            }
        }

        return new AIInput(counterKeys, this.currentAngle, finalShouldShoot, false, false);
    }

    /**
     * 只有在真正执行射击时，才更新射击计数器并计算连射冷却。
     */
    private void applyBurstLogic(long currentTime, Weapon wep, AttackExecutionPolicy policy) {
        if (wep == null)
            return;

        // Automatic survivor weapons remain held until ammo, visibility or target validity stops fire.
        if (policy.continuousFire(wep.getWeaponType())) return;

        // --- 连射限制 ---
        int maxBurst = difficulty.maxBurstShots;

        // [真实难度特化] 职业选手不点射，直接扫射压枪到死
        if (difficulty == AIDifficulty.REALISTIC) {
            maxBurst = 100; // 基本上就是按住不放直到目标消失
        } else if (wep.getWeaponType() == Weapon.WeaponType.RIFLE
                && difficulty.ordinal() >= AIDifficulty.VERY_HARD.ordinal()) {
            maxBurst = 4 + rand.nextInt(4); // 4-7 发短扫射
        } else if (wep.getWeaponType() == Weapon.WeaponType.LMG) {
            maxBurst = 25 + rand.nextInt(31);
        } else if (wep.getWeaponType() == Weapon.WeaponType.SNIPER) {
            maxBurst = 1;
        }

        if (shotsFiredAtCurrentTarget + 1 >= maxBurst) {
            this.shotsFiredAtCurrentTarget = 0;
            if (this.owner != null)
                this.owner.shootTimeIndex = 0;

            long cooldown = 300 + rand.nextInt(400);
            if (difficulty.ordinal() >= AIDifficulty.VERY_HARD.ordinal()) {
                cooldown = 150 + rand.nextInt(150);
            }
            // [真实难度特化] 极快的重置
            if (difficulty == AIDifficulty.REALISTIC)
                cooldown = 50;

            if (wep.getWeaponType() == Weapon.WeaponType.LMG)
                cooldown = 800 + rand.nextInt(500);
            this.burstCooldownUntil = currentTime + cooldown;
        } else {
            shotsFiredAtCurrentTarget++;
        }
    }

    /**
     * 将目标横移角度转换为 WASD 按键。
     */
    private void applyStrafeKeys(double strafeAngle, List<String> keys) {
        if (Math.sin(strafeAngle) < -0.5)
            keys.add("W");
        else if (Math.sin(strafeAngle) > 0.5)
            keys.add("S");
        if (Math.cos(strafeAngle) < -0.5)
            keys.add("A");
        else if (Math.cos(strafeAngle) > 0.5)
            keys.add("D");
    }

    /**
     * 机枪扫射逻辑更新
     */
    private void updateLmgSweep(long currentTime) {
        double sweepRange = Math.toRadians(15.0); // 默认扫射 15 度范围
        // 如果最近击中了敌人，缩小扫射范围到 3 度 (集中火力)
        if (currentTime - lastLmgHitTime < 500) {
            sweepRange = Math.toRadians(3.0);
        }

        double sweepSpeed = 0.05; // 扫射速度
        if (lmgSweepDirection) {
            lmgSweepOffset += sweepSpeed;
            if (lmgSweepOffset > sweepRange)
                lmgSweepDirection = false;
        } else {
            lmgSweepOffset -= sweepSpeed;
            if (lmgSweepOffset < -sweepRange)
                lmgSweepDirection = true;
        }
    }

    private void aimAtWithOffset(Point2D.Double pos, double offset) {
        if (pos == null || owner == null || owner.position == null)
            return;
        double baseAngle = Math.atan2(pos.y - owner.position.y, pos.x - owner.position.x);
        this.targetAngle = baseAngle + offset;
    }

    /**
     * 被 GameState 调用，当 AI 穿墙击中敌人时通知此模块。
     */
    public void notifyPenetrationHit(Point2D.Double hitPos) {
        this.lastLmgHitTime = System.currentTimeMillis();
        this.lastLmgHitPos = hitPos;
        // 如果是机枪，扫射偏移会向击中点收缩
        this.lmgSweepOffset *= 0.5;
    }

    public boolean getSniperNeedsRetreat() {
        boolean retreat = sniperNeedsRetreat;
        sniperNeedsRetreat = false; // 获取后重置
        return retreat;
    }

    /**
     * 辅助方法：判断射击是否可行 (检查武器状态)。
     * 不在此处增加射击计数，以免 AI 在急停过程中“空耗”连射数。
     */
    private boolean decideShooting(Player target, long currentTime, Point2D.Double lkp) {
        if (owner == null)
            return false;

        Weapon wep = owner.getCurrentWeapon();
        if (wep == null) {
            if (owner.primaryWeapon != null)
                owner.switchToSlot(1);
            else if (owner.secondaryWeapon != null)
                owner.switchToSlot(2);
            return false;
        }

        // --- 新增：短枪有效射程限制 ---
        double effectiveRange = Double.MAX_VALUE;
        if (wep.getWeaponType() == Weapon.WeaponType.SHOTGUN) {
            effectiveRange = 400.0;
        } else if (wep.getWeaponType() == Weapon.WeaponType.SMG) {
            effectiveRange = 650.0;
        }

        Point2D.Double targetPos = (target != null) ? target.position : lkp;
        if (targetPos != null && owner.position.distance(targetPos) > effectiveRange) {
            return false; // 超出短枪有效射程，不开火
        }
        // --- 结束新增 ---

        if (owner.isReloading)
            return false;
        if (currentTime < burstCooldownUntil)
            return false;

        if (owner.currentAmmo <= 0) {
            if (owner.reserveAmmo > 0)
                owner.startReload();
            return false;
        }

        if (currentTime - owner.lastShotTime < wep.fireRateMillis) {
            return false;
        }

        return true;
    }

    /**
     * 辅助方法：决定是否进行盲射 (机枪会持续盲射更久)。
     */
    private boolean decideBlindFire(Point2D.Double lkp, long currentTime) {
        if (owner == null)
            return false;

        Weapon wep = owner.getCurrentWeapon();
        if (wep == null)
            return false;

        double chance = difficulty.penetrationChance;
        if (wep.getWeaponType() == Weapon.WeaponType.LMG) {
            chance = Math.min(1.0, chance * 2.0); // 机枪盲射意愿翻倍
        }

        if (chance > rand.nextDouble()) {
            PathfindingModule pm = owner.getPathfindingModule();
            if (pm == null || pm.pathfinder == null)
                return false;

            double totalThickness = calculateWallThickness(pm.pathfinder, owner.position, lkp);

            // 应用 2.0 倍穿透系数
            final double PENETRATION_MULTIPLIER = 2.0;
            double initialPower = wep.penetrationPower * PENETRATION_MULTIPLIER;
            double remainingPower = initialPower - (totalThickness * wep.getPenetrationCostPerPixel());
            double powerRatio = remainingPower / initialPower;

            // 放宽盲射的穿透力阈值。玩家会用步枪穿厚墙（即使伤害只剩 10% 也能造成伤害）
            // 只要能穿透（剩余穿透力比例 > 5%），真实难度的 AI 就应该予以还击或压制
            if (powerRatio > 0.05) {
                return decideShooting(null, currentTime, lkp);
            }
        }
        return false;
    }

    /**
     * 计算两点之间墙壁的总厚度。
     * 从旧的 AIController.attemptPenetrationShot 移植而来。
     * 
     * @param pathfinder 包含障碍物数据的 Pathfinder 实例。
     * @param start      射线的起点。
     * @param end        射线的终点。
     * @return 总厚度，如果输入无效则返回 Double.POSITIVE_INFINITY。
     */
    private double calculateWallThickness(PathfindingModule.Pathfinder pathfinder, Point2D.Double start,
            Point2D.Double end) {
        // 输入验证
        if (start == null || end == null || pathfinder == null) {
            System.err.println("AttackModule: calculateWallThickness 的输入无效。");
            return Double.POSITIVE_INFINITY; // 返回一个极大值表示不可能
        }

        Line2D.Double ray = new Line2D.Double(start, end); // 创建射线
        double totalThickness = 0; // 初始化总厚度
        List<Shape> intersectingWalls = new ArrayList<>(); // 存储相交的墙壁

        // 通过传入的 Pathfinder 实例访问障碍物
        List<Shape> obstacles = pathfinder.getObstacles(); // 假设 Pathfinder 有 getObstacles() 方法
        if (obstacles == null) {
            System.err.println("AttackModule: Pathfinder 中的障碍物列表为 null，无法计算墙壁厚度。");
            return Double.POSITIVE_INFINITY; // 没有障碍物信息无法计算
        }

        // 查找所有与射线相交的墙壁
        for (Shape obs : obstacles) {
            if (obs == null)
                continue; // 安全检查，跳过列表中的 null 形状
            // 使用 GameState 的静态方法进行精确相交检查
            if (obs.intersects(ray.getBounds2D()) // 快速包围盒检查
                    && GameState.getLineShapeIntersections(ray, obs) != null) // 精确几何相交检查
            {
                intersectingWalls.add(obs); // 添加到相交列表
            }
        }

        // 按距离排序墙壁 (可选但符合逻辑)
        intersectingWalls.sort(Comparator.comparingDouble(s -> {
            // 安全地获取中心点用于排序，处理潜在的 null 边界
            Rectangle2D bounds = s.getBounds2D();
            return (bounds != null) ? start.distance(bounds.getCenterX(), bounds.getCenterY())
                    : Double.POSITIVE_INFINITY;
        }));

        // 计算总厚度：累加每个相交墙壁的穿透距离
        for (Shape wall : intersectingWalls) {
            Point2D.Double[] intersections = GameState.getLineShapeIntersections(ray, wall); // 获取精确交点
            // 如果有两个交点 (射线穿过墙壁)
            if (intersections != null && intersections.length >= 2) {
                // 在计算距离前检查交点是否为 null
                if (intersections[0] != null && intersections[1] != null) {
                    totalThickness += intersections[0].distance(intersections[1]); // 累加穿透距离
                } else {
                    // 记录发现 null 交点的错误
                    System.err.println("AttackModule: 在厚度计算中发现 null 交点。");
                }

            }
            // 如果只有一个交点 (例如起点/终点在墙内，或相切)
            else if (intersections != null && intersections.length == 1) {
                // 处理相切或起点/终点在墙内的复杂情况 - 简化处理，忽略厚度贡献或给一个很小的值
                System.err.println("AttackModule: 发现单个交点 - 复杂情况，厚度贡献被忽略/简化。");
            }
            // 如果 intersects() 为 true 但 getLineShapeIntersections 为 null (不寻常情况)
            else if (intersections == null && wall.intersects(ray.getBounds2D())) { // 使用 'wall'
                // 记录潜在的几何计算问题
                System.err.println("AttackModule: intersects() 为 true 但 getLineShapeIntersections 为 null - 可能的几何问题？");
            }
        }

        return totalThickness; // 返回计算出的总厚度
    }

    // --- 瞄准逻辑 (aimAt 方法保持不变) ---
    /**
     * 核心瞄准逻辑：计算瞄准玩家的最终角度。
     */
    private void aimAt(Player targetPlayer) {
        // 添加 owner null 检查
        if (targetPlayer == null || owner == null)
            return;
        Weapon wep = owner.getCurrentWeapon();
        if (wep == null)
            return;

        Point2D.Double aimPosition;
        // 根据难度决定瞄准精度
        if (difficulty.ordinal() <= AIDifficulty.HARD.ordinal()) {
            // 低难度：在目标周围随机偏移
            aimPosition = new Point2D.Double(
                    targetPlayer.position.x + (rand.nextDouble() - 0.5) * Player.SIZE * 0.7,
                    targetPlayer.position.y + (rand.nextDouble() - 0.5) * Player.SIZE * 0.7);
        } else {
            // 高难度：精确瞄准
            aimPosition = targetPlayer.position;
        }

        // 在使用 owner.position 之前检查它是否为 null
        if (owner.position == null || aimPosition == null) {
            // 记录位置为 null 的错误
            System.err.println("AttackModule: 在 aimAt(Player) 中检测到 null 位置。 Owner: " + (owner.position == null)
                    + ", Aim: " + (aimPosition == null));
            return; // 无法计算角度，提前返回
        }

        // 计算基础瞄准角度
        double baseAngle = Math.atan2(aimPosition.y - owner.position.y, aimPosition.x - owner.position.x);

        // 应用压枪补偿 (如果武器有固定后坐力模式)
        if (owner.weaponFixedRecoil != null) {
            int nextRecoilIndex = owner.shootTimeIndex % owner.weaponFixedRecoil.length;
            double recoilToExpect = owner.weaponFixedRecoil[nextRecoilIndex];
            // 根据难度调整补偿程度
            double compensationAngle = recoilToExpect * difficulty.recoilControlFactor;
            baseAngle -= compensationAngle; // 向下调整角度以抵消后坐力
        }

        // 应用武器扩散和难度带来的瞄准误差
        double finalAngle = baseAngle
                + ((rand.nextDouble() - 0.5) * owner.currentSpread * difficulty.aimErrorMultiplier);

        // 低难度 AI 的“故意失误”逻辑
        boolean wasIntentionalMiss = false;
        if (difficulty.ordinal() <= AIDifficulty.HARD.ordinal()) {
            // 第一枪故意打偏
            if (shotsFiredAtCurrentTarget == 0) {
                double missOffset = 0.035 + rand.nextDouble() * 0.052; // 偏移量
                finalAngle += (rand.nextBoolean() ? 1 : -1) * missOffset; // 随机左右偏移
                wasIntentionalMiss = true;
            }
            // 前几枪增加额外误差 (模拟瞄准调整过程)
            else if (shotsFiredAtCurrentTarget <= SHOOT_TOTAL_NEED_NUM) {
                double dialingInError = (rand.nextDouble() - 0.5) * owner.currentSpread
                        * (difficulty.aimErrorMultiplier * 2.0);
                finalAngle += dialingInError;
            }
        }

        // 应用随机失误概率
        if (!wasIntentionalMiss && rand.nextDouble() < difficulty.missChance) {
            finalAngle += (rand.nextBoolean() ? 1 : -1) * (0.5 + rand.nextDouble()); // 较大的随机偏移
        }

        // 设置最终的目标角度
        this.targetAngle = finalAngle;
    }

    /**
     * 核心瞄准逻辑：计算瞄准坐标点的最终角度 (用于盲射)。
     */
    private void aimAt(Point2D.Double aimPosition) {
        // 添加 owner 和 owner.position 的检查
        if (aimPosition == null || owner == null || owner.position == null) {
            // 记录位置为 null 的错误
            System.err.println("AttackModule: 在 aimAt(Point) 中检测到 null 位置。 Owner: "
                    + (owner == null || owner.position == null) + ", Aim: " + (aimPosition == null));
            return; // 无法计算角度，提前返回
        }

        // 计算基础瞄准角度
        double baseAngle = Math.atan2(aimPosition.y - owner.position.y, aimPosition.x - owner.position.x);
        // 应用武器扩散和难度带来的瞄准误差
        double aimError = (rand.nextDouble() - 0.5) * owner.currentSpread * difficulty.aimErrorMultiplier;
        // 设置最终的目标角度
        this.targetAngle = baseAngle + aimError;
    }

    // --- 工具方法 (updateAimAngle, getFlashed, reset 保持不变) ---
    /**
     * 平滑地更新 AI 的当前朝向角度，使其趋向目标角度。
     */
    private void updateAimAngle() {
        if (owner == null)
            return; // 安全检查
        // 在访问 difficulty 属性前确保它不为 null
        if (difficulty == null) {
            System.err.println("AttackModule: AIDifficulty 在 updateAimAngle 中为 null。");
            return; // 或者使用默认因子
        }

        // 确保 aimErrorMultiplier 不为零以防止除零错误
        double aimErrorMultiplier = difficulty.aimErrorMultiplier;
        if (aimErrorMultiplier == 0) {
            System.err.println("AttackModule: aimErrorMultiplier 为零，使用默认因子。");
            aimErrorMultiplier = 1.0; // 使用默认值
        }

        // 计算转向速度因子
        double turnSpeedDegrees = BASE_TURN_SPEED_DEGREES;

        if (difficulty == AIDifficulty.REALISTIC) {
            // [真实难度特化] 顶级拉枪速度 15度/帧
            turnSpeedDegrees = 15.0;
        } else {
            final double difficultyFactor = 1.0 / aimErrorMultiplier;
            final double MIN_FACTOR = 0.5;
            final double finalFactor = Math.max(MIN_FACTOR, difficultyFactor);
            turnSpeedDegrees = BASE_TURN_SPEED_DEGREES * finalFactor;
        }

        final double TURN_SPEED = Math.toRadians(turnSpeedDegrees);

        // 规范化角度到 [-PI, PI] 区间
        while (targetAngle <= -Math.PI)
            targetAngle += 2 * Math.PI;
        while (targetAngle > Math.PI)
            targetAngle -= 2 * Math.PI;
        while (currentAngle <= -Math.PI)
            currentAngle += 2 * Math.PI;
        while (currentAngle > Math.PI)
            currentAngle -= 2 * Math.PI;

        // 计算最短角度差
        double angleDifference = targetAngle - currentAngle;
        if (angleDifference > Math.PI)
            angleDifference -= 2 * Math.PI; // 走另一边更快
        if (angleDifference < -Math.PI)
            angleDifference += 2 * Math.PI; // 走另一边更快

        // 存储当前的瞄准误差，供开火决策使用
        this.currentAimErrorRad = Math.abs(angleDifference);

        // 应用转向速度限制
        double turnAmount = Math.max(-TURN_SPEED, Math.min(TURN_SPEED, angleDifference));
        // 更新当前角度
        this.currentAngle += turnAmount;

        // 仅在 currentAngle 有效时更新 owner.angle (现在由 update 返回值处理)
        if (!Double.isNaN(this.currentAngle) && !Double.isInfinite(this.currentAngle)) {
            // owner.angle = this.currentAngle; // 这现在应该由 update() 的返回值处理
        } else {
            // 记录无效角度的错误
            System.err.println("AttackModule: 在 updateAimAngle 中计算出无效的 currentAngle。");
            // 重置角度或进行错误处理
            // 如果 owner 和 owner.angle 有效，则重置为 owner 的角度，否则重置为 0
            this.currentAngle = (owner != null && !Double.isNaN(owner.angle)) ? owner.angle : 0.0;
        }
    }

    /**
     * 外部调用的方法，用于应用闪光弹效果。
     */
    public void getFlashed(long duration) {
        this.lastFlashTime = System.currentTimeMillis(); // 记录闪光开始时间
        this.flashDuration = duration; // 设置闪光持续时间
    }

    /**
     * 重置 AttackModule 的状态 (例如在重生时)。
     */
    public void reset() {
        // 清除目标和冷却状态
        this.lastTargetId = null;
        this.targetAcquiredTime = 0;
        this.burstCooldownUntil = 0;
        this.shotsFiredAtCurrentTarget = 0;
        // 重置闪光状态
        this.lastFlashTime = 0;
        this.flashDuration = 0;

        // 在访问 owner.angle 之前确保 owner 不为 null
        if (owner != null) {
            // 重置角度为玩家当前角度
            this.currentAngle = owner.angle;
            this.targetAngle = owner.angle;
        } else {
            // 处理 owner 为 null 的情况，可以设置默认角度
            this.currentAngle = 0.0;
            this.targetAngle = 0.0;
            // 记录 owner 为 null 的错误
            System.err.println("AttackModule: 在 reset 期间 Owner 为 null。");
        }
    }
} // AttackModule 类结束
