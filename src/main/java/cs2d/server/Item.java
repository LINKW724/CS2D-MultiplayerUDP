// cs2d/server/Item.java

package cs2d.server;

/**
 * 游戏物品枚举类 - 定义所有可购买的游戏物品
 * 参考CS2的经济系统设计，平衡游戏性
 */
public enum Item {
    // === 防护装备 ===
    KEVLAR(650, 1, ItemType.GEAR),           // 防弹衣 - 减少子弹伤害
    KEVLAR_HELMET(1000, 1, ItemType.GEAR),   // 头盔+防弹衣 - 额外防护爆头伤害
    DEFUSE_KIT(400, 1, ItemType.GEAR),       // 拆弹工具 - CT专用，加速C4拆除

    // === 投掷物 ===
    HE_GRENADE(300, 1, ItemType.GRENADE),    // 高爆手雷 - 范围伤害
    FLASHBANG(200, 2, ItemType.GRENADE),     // 闪光弹 - 致盲效果，可携带2个
    SMOKE_GRENADE(300, 1, ItemType.GRENADE), // 烟雾弹 - 制造视觉障碍
    MOLOTOV(600, 1, ItemType.GRENADE),       // 燃烧瓶 - T专用，制造火焰区域
    INCENDIARY(600, 1, ItemType.GRENADE),    // 燃烧弹 - CT专用，功能同燃烧瓶
    DECOY(50, 1, ItemType.GRENADE);          // 诱饵弹 - 制造虚假枪声

    // 物品价格 - 基于CS:GO经济系统平衡设计
    public final int cost;
    // 最大携带数量 - 限制物品堆积，保持游戏平衡
    public final int maxQuantity;
    // 物品类型 - 用于UI分类和购买逻辑
    public final ItemType type;

    /**
     * 物品构造函数
     * @param cost 价格 - 游戏内货币单位
     * @param maxQuantity 最大堆叠数量
     * @param type 物品分类
     */
    Item(int cost, int maxQuantity, ItemType type) {
        this.cost = cost;
        this.maxQuantity = maxQuantity;
        this.type = type;
    }

    /**
     * 物品类型枚举 - 区分功能性分类
     */
    public enum ItemType {
        GEAR,       // 装备类 - 提供持久性增益效果
        GRENADE     // 投掷物类 - 一次性战术道具
    }
}