package cs2d.server;

/**
 * 定义AI的难度等级及其相关参数。
 * 管理不同难度AI的行为差异，方便调整和扩展。
 * [拓展]</> recoilControlFactor(压枪能力) 和 maxBurstShots(连射上限) 属性。
 */
public enum AIDifficulty {

    // [拓展]增加了两个新的数值：压枪补偿系数 和 最大连射数
    EASY("简单", 2.0, 500, 0.1, 0.95, 0.1, 0.18, 4),    // 压枪能力35%，最多连射4发
    NORMAL("普通", 1.2, 250, 0.3, 0.65, 0.3, 0.40, 7),  // 压枪能力60%，最多连射7发
    HARD("困难", 0.8, 100, 0.5, 0.4, 0.6, 0.64, 12), // 压枪能力85%，最多连射12发
    VERY_HARD("非常困难", 0.6, 50, 0.7, 0.2, 0.8, 0.81, 18), // 压枪能力95%，最多连射18发
    HELL("地狱", 0.5, 0, 0.7, 0.0, 0.95, 0.95, 25), // 压枪能力99%(接近完美)，最多连射25发
    REALISTIC("真实", 0.5, 50, 0.5, 0.0, 0.98, 0.98, 100); // [顶级玩家] 完美反应, 无故意失误, 极致压枪


    // --- 枚举成员变量 ---

    /**
     * 在UI中显示的难度名称， "简单", "普通"等。
     */
    public final String displayName;

    /**
     * 瞄准误差倍率。数值越高，AI的射击越不精准。
     */
    public final double aimErrorMultiplier;

    /**
     * AI发现敌人后的反应时间，单位：毫秒(ms)。
     */
    public final int reactionTimeMs;

    /**
     * AI在追击时选择侧翼包抄的几率。
     */
    public final double flankChance;

    /**
     * AI前几发开火时故意打偏的几率。
     */
    public final double missChance;

    /**
     * AI尝试进行穿透射击的几率。
     */
    public final double penetrationChance;

    /**
     * AI的压枪补偿系数。
     * 1.0 代表100%完美补偿后坐力，0.0 代表完全不压枪。
     * 这个值将直接决定AI枪法的“稳”度。
     */
    public final double recoilControlFactor;

    /**
     * AI一次最多连续射击的子弹数。
     * 达到这个数量后，AI会强制停火一小段时间，然后再开火。
     */
    public final int maxBurstShots;
    // =================================================================


    /**
     * AI发现射击需要的子弹数(打人需要的，现在废弃)
     */
    public static byte shootTotalNeedNum = 10;


    /**
     * AIDifficulty 的构造函数。
     * @param displayName 显示名称
     * @param aimErrorMultiplier 瞄准误差倍率
     * @param reactionTimeMs 反应时间
     * @param flankChance 侧翼攻击几率
     * @param missChance 故意失手几率
     * @param penetrationChance 穿透射击几率
     * @param recoilControlFactor 压枪补偿系数
     * @param maxBurstShots 最大连射数
     */
    AIDifficulty(String displayName, double aimErrorMultiplier, int reactionTimeMs, double flankChance, double missChance, double penetrationChance, double recoilControlFactor, int maxBurstShots) {
        this.displayName = displayName;
        this.aimErrorMultiplier = aimErrorMultiplier;
        this.reactionTimeMs = reactionTimeMs;
        this.flankChance = flankChance;
        this.missChance = missChance;
        this.penetrationChance = penetrationChance;
        this.recoilControlFactor = recoilControlFactor;
        this.maxBurstShots = maxBurstShots;
    }

    /**
     * 重写 toString() 方法，使得在 ServerControlPanel的UI组件中能直接显示 displayName。
     * @return 难度的显示名称
     */
    @Override
    public String toString() {
        return this.displayName;
    }
}