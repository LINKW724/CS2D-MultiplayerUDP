package cs2d.client;

/**
 * 定义了游戏支持的几种模式。
 * 这是一个简单的枚举类型，用于在游戏状态(GameState)中区分不同的逻辑，
 * 例如计分方式、重生规则、AI行为等。
 * 新增了爆破模式 (DEMOLITION)。
 */
public enum GameMode {
    /**
     * 团队死亡竞赛 (Team Deathmatch, TDM)
     * 两队玩家（CT 和 T）进行对抗，目标是在规定时间内获得比对方更多的击杀数。
     * 玩家死亡后会在一定时间后重生。
     */
    TEAM_DEATHMATCH,

    /**
     * 僵尸模式 (Zombie Mode)
     * 少量幸存者玩家合作对抗一波又一波由AI控制的僵尸。
     * 目标是尽可能地生存下去，挑战更高的波数。
     * 幸存者玩家死亡后不会重生。
     */
    ZOMBIE_MODE,

    /**
     * 新增: 爆破模式 (Demolition)
     * T队的目标是安放并引爆炸弹，CT队的目标是阻止T队或拆除已安放的炸弹。
     * 玩家在本回合死亡后不会重生。游戏按回合进行。
     * 拥有独立的经济和购买系统。
     */
    DEMOLITION
}