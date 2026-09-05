package cs2d.playerAndAi;

/**
 * 定义了游戏中所有枪械的属性。
 * 这是一个枚举类型，集中管理了所有武器的数据，方便进行平衡性调整和在游戏逻辑中调用。
 * 每一把武器都是这个枚举的一个实例。
 */
public enum Weapon {
    /**
     * 武器数据格式:
     * 枚举名("显示名称", 单发伤害, 射速(ms/发), 穿透力, 弹匣容量, 备用弹匣数, 是否逐发装填, 换弹时间(ms), 是否全自动,
     * 基础散射度, 每发后坐力(用于旧版随机后坐力), 最大散射度, 散射恢复速度, 移动散射惩罚, 弹丸数量, 持枪移速倍率, 价格, 击杀奖励,
     * 护甲穿透率)
     */
    // --- 步枪 (Rifles) ---
    AK47("AK-47", 36, 100, 50, 30, 4, false, 2500, true, 0.001, 0.04, 0.4, 0.011, 0.2, 1, 0.75, 2700, 300, 0.775),
    M4A4("M4A4", 33, 90, 50, 30, 5, false, 2700, true, 0.001, 0.03, 0.35, 0.009, 0.2, 1, 0.75, 3100, 300, 0.70),
    M4A1S("M4A1-S", 38, 100, 45, 20, 4, false, 2600, true, 0.001, 0.025, 0.3, 0.007, 0.19, 1, 0.75, 2900, 300, 0.70),
    FAMAS("FAMAS", 30, 88, 40, 25, 5, false, 3300, true, 0.004, 0.022, 0.3, 0.006, 0.18, 1, 0.75, 2050, 300, 0.70),
    GALIL("Galil AR", 30, 90, 42, 35, 5, false, 3000, true, 0.003, 0.035, 0.38, 0.009, 0.21, 1, 0.75, 1800, 300, 0.775),
    AUG("AUG", 28, 95, 55, 30, 4, false, 3800, true, 0.001, 0.02, 0.28, 0.007, 0.15, 1, 0.75, 3300, 300, 0.90),
    SG553("SG 553", 30, 100, 60, 30, 4, false, 2800, true, 0.002, 0.03, 0.32, 0.007, 0.16, 1, 0.75, 3000, 300, 1.00),

    // --- 狙击枪 (Snipers) ---
    AWP("AWP", 115, 1400, 100, 5, 3, false, 3700, false, 0.0, 0.001, 0.8, 0.05, 0.8, 1, 0.50, 4750, 100, 0.975),
    SSG08("SSG 08", 88, 1250, 70, 10, 3, false, 3700, false, 0.00005, 0.01, 0.2, 0.03, 0.1, 1, 0.9, 1700, 300, 0.85),
    SCAR20("SCAR-20", 80, 250, 70, 20, 3, false, 3500, true, 0.001, 0.01, 0.3, 0.02, 0.15, 1, 0.75, 5000, 300, 0.825),
    G3SG1("G3SG1", 80, 250, 70, 20, 3, false, 3500, true, 0.001, 0.01, 0.3, 0.02, 0.15, 1, 0.75, 5000, 300, 0.825),

    // --- 冲锋枪 (SMGs) ---
    MP9("MP9", 26, 70, 25, 30, 3, false, 2100, true, 0.024, 0.045, 0.3, 0.015, 0.02, 1, 1.0, 1250, 600, 0.60),
    MAC10("MAC-10", 29, 65, 22, 30, 4, false, 2600, true, 0.028, 0.06, 0.32, 0.014, 0.025, 1, 1.0, 1050, 600, 0.575),
    MP7("MP7", 29, 80, 30, 30, 4, false, 3100, true, 0.018, 0.054, 0.28, 0.016, 0.06, 1, 1.0, 1500, 600, 0.625),
    MP5SD("MP5-SD", 29, 80, 30, 30, 4, false, 3100, true, 0.018, 0.054, 0.28, 0.016, 0.06, 1, 1.0, 1500, 600, 0.625),
    UMP45("UMP-45", 35, 110, 35, 25, 4, false, 3500, true, 0.015, 0.075, 0.35, 0.012, 0.06, 1, 1.0, 1200, 600, 0.65),
    P90("P90", 26, 65, 30, 50, 3, false, 3200, true, 0.021, 0.03, 0.25, 0.018, 0.02, 1, 1.0, 2350, 300, 0.625),
    BIZON("PP-Bizon", 27, 80, 18, 64, 3, false, 2400, true, 0.03, 0.036, 0.22, 0.02, 0.06, 1, 1.0, 1400, 600, 0.54),

    // --- 霰弹枪 (Shotguns) ---
    NOVA("Nova", 26, 680, 10, 8, 4, true, 500, false, 0.09, 0.0, 0.3, 0.0, 0.3, 9, 1.0, 1050, 900, 0.50),
    XM1014("XM1014", 20, 240, 15, 7, 6, true, 400, true, 0.1, 0.05, 0.35, 0.01, 0.28, 6, 1.0, 2000, 900, 0.80),
    MAG7("MAG-7", 30, 800, 12, 5, 4, false, 2400, false, 0.08, 0.0, 0.3, 0.0, 0.25, 5, 1.0, 1300, 900, 0.75),
    SAWEDOFF("Sawed-Off", 32, 850, 8, 7, 6, true, 450, false, 0.12, 0.0, 0.3, 0.0, 0.3, 8, 1.0, 1100, 900, 0.75),

    // --- 轻机枪 (Machine Guns) ---
    NEGEV("Negev", 35, 75, 55, 150, 3, false, 5700, true, 0.008, 0.04, 0.5, 0.02, 0.6, 1, 0.40, 1700, 300, 0.75),
    M249("M249", 33, 85, 50, 100, 3, false, 5700, true, 0.003, 0.02, 0.38, 0.015, 0.35, 1, 0.40, 5200, 300, 0.80),

    // --- 手枪 (Pistols) ---
    GLOCK18("Glock-18", 30, 150, 10, 20, 4, false, 2200, false, 0.008, 0.05, 0.5, 0.02, 0.08, 1, 1.0, 200, 300, 0.47),
    P2000("P2000", 35, 170, 15, 13, 5, false, 2200, false, 0.003, 0.04, 0.45, 0.025, 0.07, 1, 1.0, 200, 300, 0.505),
    USPS("USP-S", 35, 170, 15, 12, 3, false, 2200, false, 0.003, 0.04, 0.45, 0.025, 0.07, 1, 1.0, 200, 300, 0.505),
    DUAL_BERETTAS("Dual Berettas", 38, 120, 12, 30, 3, false, 3800, false, 0.01, 0.05, 0.5, 0.02, 0.05, 2, 1.0, 300,
            300, 0.575),
    P250("P250", 35, 160, 20, 13, 4, false, 1800, false, 0.003, 0.06, 0.5, 0.022, 0.06, 1, 1.0, 300, 300, 0.64),
    FIVESEVEN("Five-SeveN", 32, 150, 28, 20, 3, false, 2200, false, 0.008, 0.05, 0.48, 0.023, 0.05, 1, 1.0, 500, 300,
            0.90),
    TEC9("Tec-9", 33, 120, 25, 18, 4, false, 1900, false, 0.01, 0.07, 0.55, 0.018, 0.04, 1, 1.0, 500, 300, 0.90),
    CZ75("CZ75-Auto", 31, 100, 20, 12, 3, false, 2700, true, 0.012, 0.08, 0.6, 0.02, 0.06, 1, 1.0, 500, 300, 0.775),
    DEAGLE("Desert Eagle", 53, 267, 40, 7, 4, false, 2000, false, 0.0015, 0.02, 0.7, 0.04, 0.08, 1, 1.0, 700, 300,
            0.93),
    R8("R8 Revolver", 72, 750, 90, 8, 3, false, 3400, true, 0.008, 0.03, 0.8, 0.03, 0.08, 1, 1.0, 600, 300, 0.93);

    // --- 枚举成员变量 ---
    public final String name; // 武器显示名称 (用于UI和日志)
    public final int damage; // 单发基础伤害值 (命中身体)
    public final long fireRateMillis; // 射速间隔 (毫秒/发)，值越大射速越慢
    public final double penetrationPower; // 穿透能力系数，决定子弹能穿透多厚的障碍物
    public final int magazineSize; // 弹匣容量 (发)
    public final int maxReserveMags; // 最大备用弹匣数 (个)
    public final boolean isIndividualReload; // 是否为逐发装填 (如霰弹枪)
    public final long reloadTimeMillis; // 换弹时间 (毫秒)，对于逐发装填是单发的装填时间
    public final boolean isFullAuto; // 是否为全自动武器 (true=连发, false=点射)
    public final double baseSpread; // 基础散射度 (弧度)，静止站立时的最小散布
    public final double recoilPerShot; // 每发后坐力增量 (弧度)，影响连射时的散布增长
    public final double maxSpread; // 最大散射度 (弧度)，连射时散布的上限
    public final double spreadRecoveryRate; // 散射恢复速度 (弧度/秒)，停止射击后散布恢复的速率
    public final double movementSpreadPenalty; // 移动散射惩罚系数，移动时增加的额外散布倍数
    public final int pelletCount; // 弹丸数量 (霰弹枪>1，其他武器=1)
    public final double speedMultiplier; // 持枪移动速度倍率 (1.0=正常速度，<1.0=减速)
    public final int cost; // 购买价格 ($)
    public final int killReward; // 击杀奖励 ($)
    public final double armorPenetration; // 护甲穿透率 (0.0-1.0)，1.0=完全无视护甲
    // 阵营归属

    public enum Faction {
        CT, // CT专属
        T, // T专属
        ANY // 双方通用
    }

    /**
     * 获取本武器的阵营归属。
     * 这种方式无需修改构造函数，将阵营定义集中管理，便于维护。
     * 
     * @return 返回武器所属的阵营 (CT, T, or ANY)。
     */
    public Faction getFaction() {
        switch (this) {
            // --- CT 专属 ---
            case M4A4:
            case M4A1S:
            case FAMAS:
            case AUG:
            case MP9:
            case MP5SD:
            case FIVESEVEN:
            case USPS:
            case P2000:
            case MAG7:
            case SCAR20:
                return Faction.CT;

            // --- T 专属 ---
            case GLOCK18:
            case AK47:
            case GALIL:
            case SG553:
            case MAC10:
            case TEC9:
            case SAWEDOFF:
            case G3SG1:
                return Faction.T;

            // --- 其他所有武器均为双方通用 ---
            default:
                return Faction.ANY;
        }
    }

    Weapon(String name, int damage, long fireRateMillis, double penetrationPower, int magazineSize, int maxReserveMags,
            boolean isIndividualReload, long reloadTimeMillis,
            boolean isFullAuto, double baseSpread, double recoilPerShot, double maxSpread, double spreadRecoveryRate,
            double movementSpreadPenalty, int pelletCount, double speedMultiplier, int cost, int killReward,
            double armorPenetration) {
        this.name = name;
        this.damage = damage;
        this.fireRateMillis = fireRateMillis;
        this.penetrationPower = penetrationPower;
        this.magazineSize = magazineSize;
        this.maxReserveMags = maxReserveMags;
        this.isIndividualReload = isIndividualReload;
        this.reloadTimeMillis = reloadTimeMillis;
        this.isFullAuto = isFullAuto;
        this.baseSpread = baseSpread;
        this.recoilPerShot = recoilPerShot;
        this.maxSpread = maxSpread;
        this.spreadRecoveryRate = spreadRecoveryRate;
        this.movementSpreadPenalty = movementSpreadPenalty;
        this.pelletCount = pelletCount;
        this.speedMultiplier = speedMultiplier;
        this.cost = cost;
        this.killReward = killReward;
        this.armorPenetration = armorPenetration;
    }

    /**
     * 内部方法，根据距离计算子弹的伤害衰减系数。采用线性衰减模型。
     * [开始衰减距离, 结束衰减距离, 最低衰减伤害百分比]
     * 
     * @param r 击中敌人的距离
     * @return 子弹伤害系数 (一个 0.0 到 1.0 之间的浮点数)
     */
    public double getDamageFalloff(double r) {
        double startFalloffDist;
        double endFalloffDist;
        double minDamageMultiplier;

        // 根据武器类型，定义其独特的伤害衰减参数
        switch (this) {
            // --- 步枪 (Rifles) ---
            // 拥有较远的交战距离和较低的伤害衰减
            case AK47:
            case M4A4:
            case SG553:
            case M4A1S:
            case AUG:
                startFalloffDist = 800; // 800单位内不衰减
                endFalloffDist = 4500; // 超过4500单位时达到最大衰减
                minDamageMultiplier = 0.90; // 远距离伤害至少保留90%
                break;
            case FAMAS:
            case GALIL:
                startFalloffDist = 600;
                endFalloffDist = 4000;
                minDamageMultiplier = 0.88; // 作为价格更低的步枪，衰减稍快
                break;

            // --- 狙击枪 (Snipers) ---
            // 几乎没有伤害衰减，以保证远距离的击杀能力
            case AWP:
                startFalloffDist = 1500;
                endFalloffDist = 10000; // 极远的衰减距离
                minDamageMultiplier = 0.98; // 伤害几乎不损失
                break;
            case SSG08:
            case SCAR20:
            case G3SG1:
                startFalloffDist = 1200;
                endFalloffDist = 8000;
                minDamageMultiplier = 0.92; // 比AWP衰减略多一点
                break;

            // --- 冲锋枪 (SMGs) ---
            // 近距离王者，但远距离伤害衰减非常明显
            case UMP45:
            case MP7:
            case MP5SD:
                startFalloffDist = 400;
                endFalloffDist = 2000;
                minDamageMultiplier = 0.60; // 远距离伤害保留60%
                break;
            case P90: // P90穿甲好，衰减也略好
                startFalloffDist = 500;
                endFalloffDist = 2200;
                minDamageMultiplier = 0.68;
                break;
            case MP9:
            case MAC10:
            case BIZON:
                startFalloffDist = 300;
                endFalloffDist = 1800;
                minDamageMultiplier = 0.50; // 高射速冲锋枪，衰减更快
                break;

            // --- 霰弹枪 (Shotguns) ---
            // 伤害衰减极为严重，是近距离专用武器 (伤害为单颗弹丸)
            case XM1014: // 连喷的有效距离稍远
                startFalloffDist = 300;
                endFalloffDist = 1600;
                minDamageMultiplier = 0.20; // 远距离伤害极低
                break;
            case NOVA:
            case MAG7:
            case SAWEDOFF:
                startFalloffDist = 200;
                endFalloffDist = 1300;
                minDamageMultiplier = 0.12; // 衰减非常严重
                break;

            // --- 轻机枪 (Machine Guns) ---
            // 定位是火力压制，衰减模型类似步枪
            case M249:
                startFalloffDist = 700;
                endFalloffDist = 5000;
                minDamageMultiplier = 0.88;
                break;
            case NEGEV: // 价格便宜，衰减也稍快
                startFalloffDist = 600;
                endFalloffDist = 4800;
                minDamageMultiplier = 0.85;
                break;

            // --- 手枪 (Pistols) ---
            // 手枪的衰减差异化很大
            case DEAGLE:
            case R8:
                startFalloffDist = 600;
                endFalloffDist = 3500;
                minDamageMultiplier = 0.82; // 沙鹰和R8的远距离能力很强
                break;
            case FIVESEVEN:
            case TEC9:
            case P250:
            case CZ75:
            case DUAL_BERETTAS:
                startFalloffDist = 400;
                endFalloffDist = 2500;
                minDamageMultiplier = 0.70;
                break;
            case USPS:
            case P2000:
                startFalloffDist = 500;
                endFalloffDist = 2800;
                minDamageMultiplier = 0.60;
                break;
            case GLOCK18: // 初始手枪，远距离能力最弱
                startFalloffDist = 300;
                endFalloffDist = 2000;
                minDamageMultiplier = 0.55;
                break;

            default:
                // 如果有未定义的武器，则不进行任何衰减，伤害系数为1.0
                return 1.0;
        }

        // --- 核心衰减逻辑 ---

        // 在开始衰减距离内，无伤害衰减，返回100%伤害系数
        if (r <= startFalloffDist) {
            return 1.0;
        }

        // 超过结束衰减距离，返回最低伤害系数
        if (r >= endFalloffDist) {
            return minDamageMultiplier;
        }

        // 在衰减区间内，进行线性插值计算
        double falloffRange = endFalloffDist - startFalloffDist;
        double distIntoFalloff = r - startFalloffDist;

        // 计算当前距离在衰减区间的进度 (0.0 到 1.0)
        double falloffProgress = distIntoFalloff / falloffRange;

        // 根据进度计算当前的伤害乘数
        // (从1.0线性降低到minDamageMultiplier)

        return 1.0 - ((1.0 - minDamageMultiplier) * falloffProgress);
    }

    /**
     * penetrationCostPerPixel 方法是获取每个像素消耗的穿透力系数
     */
    public double penetrationCostPerPixel() {
        if (this == NEGEV)
            return 2.3;
        if (this == R8)
            return 1.1;
        if (this == SSG08)
            return 1.2;
        return getWeaponType().isLongRange() ? 1.5 : 2;
    }

    /**
     * 内部枚举，用于对武器进行分类。
     */
    public enum WeaponType {
        RIFLE, SNIPER, SMG, SHOTGUN, LMG, PISTOL;

        public boolean isLightWeapon() {
            return this == SMG || this == PISTOL || this == SHOTGUN;
        }

        public boolean isLongRange() {
            return this == RIFLE || this == SNIPER || this == LMG;
        }

        public boolean isShortRange() {
            return this == SMG || this == SHOTGUN || this == PISTOL;
        }

        public boolean isPistol() {
            return this == PISTOL;
        }
    }

    public WeaponType getWeaponType() {
        switch (this) {
            case AK47:
            case M4A4:
            case M4A1S:
            case FAMAS:
            case GALIL:
            case AUG:
            case SG553:
                return WeaponType.RIFLE;
            case AWP:
            case SSG08:
            case SCAR20:
            case G3SG1:
                return WeaponType.SNIPER;
            case MP9:
            case MAC10:
            case MP7:
            case UMP45:
            case P90:
            case BIZON:
            case MP5SD:
                return WeaponType.SMG;
            case NOVA:
            case XM1014:
            case MAG7:
            case SAWEDOFF:
                return WeaponType.SHOTGUN;
            case NEGEV:
            case M249:
                return WeaponType.LMG;
            default:
                return WeaponType.PISTOL;
        }
    }

    /**
     * 获取武器的固定后坐力模式。
     * 
     * @param w 目标武器
     * @return 返回一个 double 数组，代表每次射击的角度偏移量（已适配游戏坐标）。如果武器没有固定模式，则返回 null。
     */
    public double[] getFixedRecoilPattern(Weapon w) {
        double D = 20;
        double[] d = null;
        if (w.getWeaponType().isShortRange())
            D = 60;
        if (w.getWeaponType().isPistol())
            D = 20;
        if (w.equals(Weapon.NEGEV))
            D = 15;
        if (w.equals(Weapon.MP9))
            D = 30;
        if (w.equals(Weapon.BIZON))
            D = 2;
        if (w.equals(Weapon.UMP45))
            D = 2;
        if (w.equals(Weapon.P90))
            D = 23D;

        switch (w) {
            // --- 步枪 (Rifles) ---
            case AK47: {
                d = new double[] {
                        // 前10发相对集中，之后开始大幅度左右横跳
                        0, 0.2, 0.3, 0.7, 1.1, 1.2, 0.8, 0.4, -0.2, -1.1,
                        -3, -3, -2, 4, 3, -2, 2, 3, 3, 3,
                        4, 3.5, 3, 2, 1, 0, -0.8, -1.2, -1.6, -1.8
                };
                break;
            }
            case M4A4: {
                d = new double[] {
                        // 弹道平滑，呈窄"S"形上升，易于控制
                        0, 0.15, 0.2, 0.5, 0.9, 1.1, 1.3, 1.2, 0.9, 0.5,
                        0.1, -0.4, -0.9, -1.3, -1.5, -1.4, -1.1, -0.6, 0, 0.7,
                        1.2, 1.7, 1.9, 1.6, 1.1, 0.4, -0.5, -1.3, -1.8, -1.5
                };
                break;
            }
            case M4A1S: {
                d = new double[] {
                        // 非常稳定，后段有极其轻微的、平滑的左右摆动
                        0, 0.1, 0.2, 0.5, 0.9, 1.1, 1.4, 1.5, 1.4, 1.2,
                        0.9, 0.5, 0.1, -0.3, -0.6, -0.7, -0.6, -0.3, 0.2, 0.7,
                        1.1, 1.4, 1.6, 1.4, 1.0
                };
                break;
            }
            case GALIL: {
                d = new double[] {
                        // 弹道左右晃动，但过渡更平滑，减少了突然的转向
                        0, 0.2, 0.3, 0.7, 1.1, 1.2, 0.7, 0.1, -0.3,
                        -0.9, -1.6, -2, -1.8, -0.9, 0.2, 1.3, 2.2, 2.9, 3.3,
                        3.0, 2.4, 1.5, 0.6, -0.7, -1.8, -2.7, -3.2, -2.9, -2.1,
                        -1.0, 0.1, 1.2, 2.0, 1.5
                };
                break;
            }
            case FAMAS: {
                d = new double[] {
                        // 前期稳定，中后期开始逐渐加大左右摆动幅度
                        0, 0.1, 0.2, 0.5, 0.9, 1.3, 1.5, 1.0, 0.2, -0.7,
                        -1.6, -2.3, -2.8, -2.4, -1.7, -0.8, 0.4, 1.5, 2.6, 3.2,
                        2.8, 1.9, 0.7, -1.0, -2.5
                };
                break;
            }
            case AUG: {
                d = new double[] {
                        // 弹道非常集中，只有轻微的、平滑的左右晃动
                        0, 0.05, 0.1, 0.2, 0.4, 0.7, 1.1, 1.3, 1.2, 1.0,
                        0.7, 0.3, 0, -0.3, -0.6, -0.8, -0.6, -0.3, 0, 0.4,
                        0.8, 1.2, 1.5, 1.7, 1.5, 1.2, 0.8, 0.2, -0.5, -1.2
                };
                break;
            }
            case SG553: {
                d = new double[] {
                        // 弹道向右上方平滑倾斜，然后逐渐向左修正
                        0, 0.2, 0.3, 0.7, 1.1, 1.2, 1.9, 2.3, 2.1, 1.5,
                        0.8, 0.1, -0.7, -1.4, -1.9, -2.2, -2.0, -1.6, -0.9, -0.1,
                        0.8, 1.6, 2.3, 2.7, 2.5, 1.9, 1.1, 0.2, -1.0, -2.0
                };
                break;
            }

            // --- 冲锋枪 (SMGs) ---
            case MP9: {
                d = new double[] {
                        // 射速快，弹道呈平滑的 "S" 形，水平抖动过程可预测
                        0, 0.5, 1.0, 1.5, 2.2, 2.8, 3.1, 2.7, 2.0, 1.2,
                        0.2, -0.8, -1.8, -2.5, -3.0, -2.6, -2.0, -1.0, 0.1, 1.2,
                        2.2, 3.0, 3.6, 3.2, 2.5, 1.5, 0.5, -1.0, -2.5, -3.5
                };
                break;
            }
            case MAC10: {
                d = new double[] {
                        // 垂直上扬后，开始平滑地大幅度左右扫射
                        0, 1.5, 2.5, 3.5, 4.0, 3.2, 2.0, 0.5, -1.5, -2.8,
                        -3.8, -3.0, -1.8, -0.5, 1.0, 2.5, 3.5, 4.2, 3.5, 2.2,
                        0.8, -1.0, -2.5, -3.8, -4.5, -3.5, -2.0, 0.0, 2.0, 4.0
                };
                break;
            }
            case MP7: {
                d = new double[] {
                        // 弹道稳定，缓慢的“~”形上升
                        0, 0.8, 1.0, 1.2, 1.4, 1.6, 1.5, 1.3, 1.0, 0.6,
                        0.2, -0.3, -0.7, -1.0, -0.8, -0.4, 0.1, 0.6, 1.1, 1.5,
                        1.8, 2.0, 1.8, 1.5, 1.1, 0.5, -0.2, -0.9, -1.4, -1.0
                };
                break;
            }
            case UMP45: {
                d = new double[25];
                for (int i = 0; i < d.length; i++) {
                    double phase = i / 20.0; // 调整这个值可以改变抖动频率
                    d[i] = (Math.sin(7 * phase) * 0.1); // 极小的垂直上升 + 几乎不可察的抖动
                }
                break;
            }
            case P90: {
                // 弹道非常平滑，适合扫射
                d = new double[] {
                        0.0, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0,
                        1.1, 1.2, 1.3, 1.2, 1.1, 0.9, 0.7, 0.5, 0.2, 0.0,
                        -0.3, -0.6, -0.9, -1.2, -1.4, -1.5, -1.4, -1.2, -0.9, -0.6,
                        -0.3, 0.0, 0.4, 0.7, 1.0, 1.3, 1.6, 1.8, 1.6, 1.3,
                        1.0, 0.7, 0.4, 0.0, -0.5, -1.0, -1.5, -1.0, -0.5, 0.0
                };
                break;
            }
            case BIZON: {
                // 极其稳定，几乎只有微弱的垂直后坐力
                d = new double[64];
                for (int i = 0; i < d.length; i++) {
                    double phase = i / 20.0; // 调整这个值可以改变抖动频率
                    d[i] = 0.05 + (Math.sin(6 * phase - 0.5) * 0.08); // 极小的垂直上升 + 几乎不可察的抖动
                }
                break;
            }

            // --- 轻机枪 (Machine Guns) ---
            case NEGEV: {
                // 前15发后坐力巨大且混乱，之后变为几乎无后坐力的激光枪
                d = new double[150];
                double[] initialRecoil = { 0, 0.5, 0.8, 1, 1.5, 1.2, 0.9, 0.5, 0.3, 0, -0.2, -0.6, -0.8, -1.2, -0.5 };
                double[] NEGEV_ARRAY = { 0.5, 0.5, 0.5, 0.4, 0.5, 0.5, 0.5, 0.04, 0.05, 0.5, 0.45, 0.35, 0.4, 0.45, 0.5,
                        0.45 };

                for (int i = 0; i < d.length; i++) {
                    if (i < 15) {

                        d[i] = initialRecoil[i];
                    } else {
                        d[i] = NEGEV_ARRAY[i % 15];
                    }
                }
                break;
            }
            case M249: {
                // 持续、平滑的中等幅度波浪形抖动
                d = new double[100];
                double[] pattern = { 0, 0.5, 0.8, 1, 1.5, 1.2, 0.9, 0.5, 0.3, 0, -0.2, -0.6, -0.8, -1.2, -0.5, -0.7, -1,
                        -0.7, 0.3, 0 };
                for (int i = 0; i < 5; i++) { // 重复5次这个抖动模式
                    System.arraycopy(pattern, 0, d, i * pattern.length, pattern.length);
                }
                break;
            }
            case GLOCK18: {
                // 持续、平滑的中等幅度波浪形抖动
                d = new double[Weapon.GLOCK18.magazineSize];
                double[] patternG18 = { 0, 0.2, 0.3, 0.4, 0.5, 0.3 };
                for (int i = 0; i < 3; i++) { // 重复5次这个抖动模式
                    System.arraycopy(patternG18, 0, d, i * patternG18.length, patternG18.length);
                }
                break;
            }

            // --- 手枪 (Pistols) ---
            case USPS: {
                // 非常精准，后坐力小且有规律，呈轻微的右上-左上交替
                d = new double[] { 0, 0.1, 0.2, 0.1, -0.1, -0.2, -0.1, 0.1, 0.2, 0.1, -0.1, 0 };
                break;
            }
            case P250: {
                // 后坐力稍大，呈一条向上然后向左的平滑曲线
                d = new double[] { 0, 0.2, 0.4, 0.6, 0.7, 0.6, 0.4, 0.2, 0.0, -0.3, -0.5, -0.6, -0.4 };
                break;
            }
            case FIVESEVEN: {
                // 弹道精准，后坐力主要为垂直，水平晃动极小且平滑
                d = new double[] { 0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.0, -0.1, -0.2,
                        -0.1, 0.0, 0.1 };
                break;
            }
            case TEC9: {
                // 射速快，水平后坐力大，呈"S"形
                d = new double[] { 0, 0.4, 0.8, 1.2, 1.5, 1.2, 0.8, 0.3, -0.3, -0.9, -1.4, -1.0, -0.5, 0.1, 0.7, 1.2,
                        1.6, 1.1 };
                break;
            }
            case DEAGLE: {
                // 后坐力巨大，每次射击后都有一个强的随机方向偏移，但整体趋势向上
                d = new double[] { 0, 0.8, -1.8, 2.8, -3.5, 2.5, -1.5 };
                break;
            }
            case R8: {
                // 每次射击后坐力都很大，但因为射速慢，所以弹道影响不大，这里模拟一个轻微的晃动
                d = new double[] { 0, 0.2, -0.2, 0.3, -0.3, 0.2, -0.2, 0.1 };
                break;
            }

            default:
                // 对于没有定义固定后坐力的武器（如手枪、狙击枪等），d 保持为 null
                break;
        }

        // --- 统一缩放，将设计数值转换为游戏内的弧度偏移 ---
        if (d != null) {
            for (int i = 0; i < d.length; i++) {
                // 这个缩放值可以全局调整所有枪的后坐力大小，数值越大后坐力越小
                d[i] = d[i] / D;
            }
        }

        return d;
    }

    /**
     * [为新功能添加] 公开获取器：获取此武器的基础穿透力。
     * 
     * @return 武器的穿透力数值。
     */
    public double getPenetrationPower() {
        return this.penetrationPower;
    }

    /**
     * [为新功能添加] 公开获取器：获取此武器穿透每像素的成本。
     * 注意：这里我们直接调用已有的 penetrationCostPerPixel() 方法。
     * 
     * @return 穿透每像素的成本系数。
     */
    public double getPenetrationCostPerPixel() {
        // 直接调用并返回你已经写好的逻辑即可
        return penetrationCostPerPixel();
    }

}
