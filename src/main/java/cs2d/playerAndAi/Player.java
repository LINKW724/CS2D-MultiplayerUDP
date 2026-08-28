package cs2d.playerAndAi;

import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cs2d.AIControl.A.AttackModule;
import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.B.PerceptionModule;
import cs2d.AIControl.BG.TEAM_DEATHMATCHcontrol;
import cs2d.AIControl.BG.ZOMBIEcontrol;
import cs2d.server.*;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 代表游戏中的一个玩家或AI实体。
 */
public class Player {

      // --- 物理与碰撞常量 ---
      public static final double SIZE = 24;

      // --- 玩家身份与基本信息 ---
      public final String id;
      public String name;
      public Team team;
      public Point2D.Double position;
      public int health = 100;
      public boolean isRequestingUnderhandThrow = false;
      public String spectatorTargetId = null;
      public int maxHealth = 100;

      // --- 战斗状态 ---
      public boolean isShooting = false;
      public DamageInfo lastDamageSource;
      public int score = 0;
      public int kills = 0; // [新增] 纯击杀数计数
      public int deaths = 0;
      public double angle = 0;

      // --- 重生与无敌状态 ---
      public long respawnTime = 0;
      public boolean isInvincible = false;

      // --- 运动物理参数 ---
      public double vx = 0, vy = 0;
      public double ax = 0, ay = 0;
      public boolean isMoving = false;
      public boolean isSlowed = false;
      public long slowUntil = 0;

      // --- 火焰伤害系统 ---
      public long lastFireDamageTime = 0;

      // --- 装备与武器系统 ---
      public Weapon primaryWeapon;
      public Weapon secondaryWeapon;
      public int currentSlot = 2;
      public final Map<Item, Integer> equipment = new EnumMap<>(Item.class);

      // --- 武器与弹药管理 ---
      public String nextWeapon = "AK47";
      public String selectedPrimaryName = null;
      public String selectedSecondaryName = null;
      public int currentAmmo;
      public int reserveAmmo;

      // --- 射击与换弹状态 ---
      public boolean isReloading = false;
      public long lastShotTime = 0;
      public long reloadStartTime = 0;
      public int currentReloadTime = 0;

      // --- 弹道散布系统 ---
      public double currentSpread = 0;
      public double continueShotingSpread = 0;
      public double movingSpread = 0;

      // --- 固定后坐力系统 ---
      public short shootTimeIndex = 0;
      public double[] weaponFixedRecoil;
      public double predictedRecoilAngle = 0;

      // --- 数据统计 ---
      public int totalShotsFired = 0;
      public int totalShotsHit = 0;
      public int totalHeadshots = 0;
      public int damageDealt = 0;
      public int deathStreak = 0;

      // --- 连接与队伍选择 ---
      public long connectionTime;
      public boolean hasChosenTeam = false;

      // --- 经济与装备系统 ---
      public int money = 800;
      public boolean hasBomb = false;
      public boolean hasDefuseKit = false;
      public boolean hasKevlar = false;
      public boolean hasHelmet = false;
      public int armorValue = 0;

      // --- 交互系统 ---
      public boolean isInteracting = false;
      public long interactionStartTime = 0;

      // --- 回合与经济系统 ---
      public int consecutiveLosses = 0;
      public boolean wasAliveLastRound = false;
      public final List<String> itemsBoughtThisFreezeTime = new ArrayList<>();
      public final Map<String, Integer> itemPurchaseCostsThisFreezeTime = new HashMap<>();

      // --- 输入控制 ---
      public final Set<String> keysDown = ConcurrentHashMap.newKeySet();
      public int primary_currentAmmo;
      public int primary_reserveAmmo;
      public int secondary_currentAmmo;
      public int secondary_reserveAmmo;

      // --- C4炸弹交互 ---
      public long lastKeyPressTime = 0;

      // --- 观战系统 ---
      public String spectatorMode = "NONE";

      // --- 僵尸模式专用 ---
      public long lastAttackTime = 0;
      public static final long PLAYER_ZOMBIE_RESPAWN_MS = 5000L;
      public static final long PLAYER_ZOMBIE_ATTACK_COOLDOWN_MS = 800L;
      public static final double PLAYER_ZOMBIE_ATTACK_RANGE = Player.SIZE * 1.5;

      // AI与感官系统
      public final boolean isAI;
      private AIDifficulty difficulty;
      private final GameState gameState;
      public final PathfindingModule pathfindingModule;
      public final PerceptionModule perceptionModule;
      public final AttackModule attackModule;
      public final TEAM_DEATHMATCHcontrol tdmController;
      public final ZOMBIEcontrol zombieController;

      public long lastSuppressedTime = 0;
      public Point2D.Double lastDamageSourcePosition = null;
      public long lastDamageSourcePositionTime = 0;

      public boolean isWalking = false;
      public long lastFootstepTime = 0;
      public Point2D.Double bufferedMissionTarget = null;
      public Point2D.Double bufferedAttackTarget = null;
      public long lastMissionBufferApplyTime = 0;
      public long lastAttackBufferApplyTime = 0;
      public static final long MISSION_BUFFER_INTERVAL_MS = 1000L;
      public static final long ATTACK_BUFFER_INTERVAL_MS = 500L;
      public volatile boolean isCollidingWithTeammate = false;

      // 夺舍系统
      public String controllingBotId = null;
      public String controlledByPlayerId = null;
      public boolean wasShootingLastFrame = false;
      public boolean wasRequestingUnderhandThrowLastFrame = false;
      public boolean isSemiAutoReady = false;
      public boolean isMeleeReady = false;
      public long r8ChargeStartTime = 0;
      public long lastControlRequestTime = 0;

      // --- Getter 方法 ---
      public AIDifficulty getDifficulty() {
            return difficulty;
      }

      public long getFlashDuration() {
            return (attackModule != null && attackModule.isFlashed(System.currentTimeMillis())) ? 1000 : 0;
      }

      public GameState getGameState() {
            return gameState;
      }

      public PathfindingModule getPathfindingModule() {
            return pathfindingModule;
      }

      public PerceptionModule getPerceptionModule() {
            return perceptionModule;
      }

      public AttackModule getAttackModule() {
            return attackModule;
      }

      public TEAM_DEATHMATCHcontrol getTdmController() {
            return tdmController;
      }

      public ZOMBIEcontrol getZombieController() {
            return zombieController;
      }

      public Player(String id, String name, Point2D.Double position, Team team, boolean isAI, GameMode gameMode,
                  GameState gameState, AIDifficulty difficulty, Consumer<String> logger) {
            this.id = id;
            this.name = name;
            this.position = position;
            this.team = team;
            this.isAI = isAI;
            this.gameState = gameState;
            this.difficulty = difficulty;

            // 初始化武器弹药
            this.primaryWeapon = null;
            this.primary_currentAmmo = 0;
            this.primary_reserveAmmo = 0;
            this.secondaryWeapon = (team == Team.T) ? Weapon.GLOCK18 : Weapon.USPS;
            this.secondary_currentAmmo = this.secondaryWeapon.magazineSize;
            this.secondary_reserveAmmo = this.secondaryWeapon.magazineSize * 4;
            this.currentSlot = 2;
            this.currentAmmo = this.secondary_currentAmmo;
            this.reserveAmmo = this.secondary_reserveAmmo;

            if (isAI) {
                  this.pathfindingModule = new PathfindingModule(this);
                  this.perceptionModule = new PerceptionModule(this, gameState, difficulty, logger);
                  this.attackModule = new AttackModule(this, difficulty);

                  TEAM_DEATHMATCHcontrol tdm = null;
                  ZOMBIEcontrol zombie = null;

                  switch (gameMode) {
                        case TEAM_DEATHMATCH:
                        case DEATHMATCH:
                              tdm = new TEAM_DEATHMATCHcontrol(this, gameState, difficulty,
                                          this.perceptionModule, this.attackModule, this.pathfindingModule, logger);
                              tdm.initializeWeaponChoice();
                              break;
                        case ZOMBIE_MODE:
                              zombie = new ZOMBIEcontrol(this, gameState, difficulty,
                                          this.perceptionModule, this.attackModule, this.pathfindingModule, logger);
                              zombie.initializeWeaponChoice();
                              break;
                        default:
                              this.selectedPrimaryName = (team == Team.T) ? "AK47" : "M4A4";
                              this.selectedSecondaryName = (team == Team.T) ? "GLOCK18" : "USPS";
                              break;
                  }
                  this.tdmController = tdm;
                  this.zombieController = zombie;
            } else {
                  this.pathfindingModule = null;
                  this.perceptionModule = null;
                  this.attackModule = null;
                  this.tdmController = null;
                  this.zombieController = null;
            }
      }

      public enum Team {
            CT, T, ZOMBIE
      }

      public final List<DamageLogEntry> damageLog = new CopyOnWriteArrayList<>();

      public record DamageLogEntry(String attackerId, String victimId, int damage, long timestamp, boolean isKill) {
            public JsonObject toJson() {
                  JsonObject obj = new JsonObject();
                  obj.addProperty("atk", attackerId);
                  obj.addProperty("vic", victimId);
                  obj.addProperty("dmg", damage);
                  obj.addProperty("ts", timestamp);
                  obj.addProperty("kill", isKill);
                  obj.addProperty("type", "damage_event");
                  return obj;
            }
      }

      public record DamageInfo(int damage, boolean isHeadshot) {
      }

      /**
       * 获取当前正在使用的武器对象。
       * 
       * @return 主武器或副武器枚举，如果是其他槽位则返回null
       */
      public Weapon getCurrentWeapon() {
            return switch (currentSlot) {
                  case 1 -> primaryWeapon;
                  case 2 -> secondaryWeapon;
                  default -> null;
            };
      }

      public Item getCurrentGrenade() {
            return switch (currentSlot) {
                  case 6 -> Item.FLASHBANG;
                  case 7 -> Item.HE_GRENADE;
                  case 8 -> Item.SMOKE_GRENADE;
                  case 9 -> equipment.containsKey(Item.MOLOTOV) ? Item.MOLOTOV
                              : (equipment.containsKey(Item.INCENDIARY) ? Item.INCENDIARY : null);
                  case 10 -> Item.DECOY;
                  default -> null;
            };
      }

      public void switchToSlot(int slot) {
            if (slot < 1 || slot > 10)
                  return;
            boolean hasItemInSlot = switch (slot) {
                  case 1 -> primaryWeapon != null;
                  case 2 -> secondaryWeapon != null;
                  case 3 -> true;
                  case 6 -> equipment.getOrDefault(Item.FLASHBANG, 0) > 0;
                  case 7 -> equipment.getOrDefault(Item.HE_GRENADE, 0) > 0;
                  case 8 -> equipment.getOrDefault(Item.SMOKE_GRENADE, 0) > 0;
                  case 9 ->
                        equipment.getOrDefault(Item.MOLOTOV, 0) > 0 || equipment.getOrDefault(Item.INCENDIARY, 0) > 0;
                  case 10 -> equipment.getOrDefault(Item.DECOY, 0) > 0;
                  default -> false;
            };
            if (!hasItemInSlot)
                  return;

            if (this.currentSlot != slot) {
                  if (this.currentSlot == 1) {
                        primary_currentAmmo = this.currentAmmo;
                        primary_reserveAmmo = this.reserveAmmo;
                  } else if (this.currentSlot == 2) {
                        secondary_currentAmmo = this.currentAmmo;
                        secondary_reserveAmmo = this.reserveAmmo;
                  }
            }

            this.currentSlot = slot;
            this.isReloading = false;

            if (this.currentSlot == 1) {
                  this.currentAmmo = primary_currentAmmo;
                  this.reserveAmmo = primary_reserveAmmo;
            } else if (this.currentSlot == 2) {
                  this.currentAmmo = secondary_currentAmmo;
                  this.reserveAmmo = secondary_reserveAmmo;
            } else {
                  this.currentAmmo = 0;
                  this.reserveAmmo = 0;
            }

            this.shootTimeIndex = 0;
            this.continueShotingSpread = 0;
            this.predictedRecoilAngle = 0;
            this.r8ChargeStartTime = 0;
            this.wasShootingLastFrame = false;

            Weapon wep = getCurrentWeapon();
            if (wep != null)
                  this.weaponFixedRecoil = wep.getFixedRecoilPattern(wep);
            else
                  this.weaponFixedRecoil = null;
      }

      public void updateSpreadAndRecoil(double baseSpeed) {
            Weapon weapon = getCurrentWeapon();
            if (weapon == null)
                  return;
            double speed = Math.sqrt(vx * vx + vy * vy);
            movingSpread = (speed / baseSpeed) * weapon.movementSpreadPenalty;
            if (!isShooting && continueShotingSpread > 0) {
                  continueShotingSpread -= weapon.spreadRecoveryRate;
                  if (continueShotingSpread < 0)
                        continueShotingSpread = 0;
            }
            currentSpread = Math.min(weapon.baseSpread + movingSpread + continueShotingSpread, weapon.maxSpread);

            long resetTime = Math.max(250, weapon.fireRateMillis + 150);
            if (weaponFixedRecoil != null && System.currentTimeMillis() - lastShotTime > resetTime) {
                  shootTimeIndex = 0;
                  predictedRecoilAngle = 0;
            }
      }

      public void updateSpread(double baseSpeed) {
            Weapon weapon = getCurrentWeapon();
            if (weapon == null)
                  return;
            double speed = Math.sqrt(vx * vx + vy * vy);
            movingSpread = (speed / baseSpeed) * weapon.movementSpreadPenalty;
            boolean hasMovementPenalty = movingSpread > 2.0 * 0.6;
            if (!isShooting && continueShotingSpread > 0) {
                  continueShotingSpread -= weapon.spreadRecoveryRate;
                  if (continueShotingSpread < 0)
                        continueShotingSpread = 0;
            }
            if (hasMovementPenalty)
                  movingSpread = 0;
            currentSpread = movingSpread + continueShotingSpread;
            if (weapon == Weapon.NEGEV && continueShotingSpread > 0.4 * Weapon.NEGEV.maxSpread && !hasMovementPenalty) {
                  currentSpread = Math.max(0.8 * Weapon.NEGEV.maxSpread - continueShotingSpread,
                              Weapon.NEGEV.baseSpread * 0.06);
                  return;
            }
            currentSpread = Math.max(weapon.baseSpread, Math.min(currentSpread, weapon.maxSpread));
      }

      /**
       * 受到伤害时的统一处理接口。
       * 
       * @param damageInfo 包含伤害值、是否爆头等信息的对象
       */
      public void takeDamage(DamageInfo damageInfo) {
            if (isInvincible)
                  return;
            this.lastDamageSource = damageInfo;
            this.health = Math.max(0, this.health - damageInfo.damage());
      }

      /**
       * 判断实体当前是否存活。
       * 
       * @return 实体是否存活
       */
      public boolean isAlive() {
            return this.health > 0;
      }

      public void startReload() {
            Weapon wep = getCurrentWeapon();
            if (isReloading || reserveAmmo <= 0 || (wep != null && currentAmmo >= wep.magazineSize))
                  return;
            if (wep != null) {
                  isReloading = true;
                  reloadStartTime = System.currentTimeMillis();
                  currentReloadTime = (int) wep.reloadTimeMillis;
                  if (gameState != null)
                        gameState.addSoundEvent(SoundEvent.SoundType.RELOAD, wep.name() + "_reload", position.x,
                                    position.y, this.id);
            }
      }

      public void setWeapon(Weapon newWeapon, GameMode gameMode) {
            if (newWeapon == null)
                  return;
            this.isReloading = false;
            if (currentSlot == 1) {
                  primary_currentAmmo = currentAmmo;
                  primary_reserveAmmo = reserveAmmo;
            } else if (currentSlot == 2) {
                  secondary_currentAmmo = currentAmmo;
                  secondary_reserveAmmo = reserveAmmo;
            }

            int initialReserve = newWeapon.magazineSize
                        * ((gameMode == GameMode.ZOMBIE_MODE || gameMode == GameMode.DEATHMATCH) ? 100
                                    : newWeapon.maxReserveMags);

            if (newWeapon.getWeaponType().isPistol()) {
                  this.secondaryWeapon = newWeapon;
                  this.secondary_currentAmmo = newWeapon.magazineSize;
                  this.secondary_reserveAmmo = initialReserve;
                  this.currentSlot = 2;
            } else {
                  this.primaryWeapon = newWeapon;
                  this.primary_currentAmmo = newWeapon.magazineSize;
                  this.primary_reserveAmmo = initialReserve;
                  this.currentSlot = 1;
            }

            if (this.currentSlot == 1) {
                  this.currentAmmo = this.primary_currentAmmo;
                  this.reserveAmmo = this.primary_reserveAmmo;
            } else if (this.currentSlot == 2) {
                  this.currentAmmo = this.secondary_currentAmmo;
                  this.reserveAmmo = this.secondary_reserveAmmo;
            }

            this.weaponFixedRecoil = newWeapon.getFixedRecoilPattern(newWeapon);
            this.shootTimeIndex = 0;
            this.predictedRecoilAngle = 0;
      }

      public void finishReload(GameMode gameMode) {
            isReloading = false;
            this.shootTimeIndex = 0;
            Weapon weapon = getCurrentWeapon();
            if (weapon == null || reserveAmmo <= 0 || currentAmmo >= weapon.magazineSize)
                  return;

            if (gameMode == GameMode.ZOMBIE_MODE || gameMode == GameMode.DEATHMATCH) {
                  this.currentAmmo = weapon.magazineSize;
            } else if (weapon.isIndividualReload) {
                  this.currentAmmo += 1;
                  this.reserveAmmo -= 1;
            } else {
                  int ammoToTake = Math.min(weapon.magazineSize, this.reserveAmmo);
                  this.reserveAmmo -= ammoToTake;
                  this.currentAmmo = ammoToTake;
            }
            this.weaponFixedRecoil = weapon.getFixedRecoilPattern(weapon);
            this.shootTimeIndex = 0;
            this.predictedRecoilAngle = 0;

            if (this.currentSlot == 1) {
                  this.primary_currentAmmo = this.currentAmmo;
                  this.primary_reserveAmmo = this.reserveAmmo;
            } else if (this.currentSlot == 2) {
                  this.secondary_currentAmmo = this.currentAmmo;
                  this.secondary_reserveAmmo = this.reserveAmmo;
            }
      }

      public void setWeapon(Weapon newWeapon) {
            setWeapon(newWeapon, GameMode.TEAM_DEATHMATCH);
      }

      /**
       * 在回合开始或死亡后重新出生，重置状态。
       * 
       * @param spawnPoint 出生点坐标
       * @param gameMode   当前的游戏模式
       */
      public void respawn(Point2D.Double spawnPoint, GameMode gameMode) {
            this.health = 100;
            this.position = spawnPoint;
            this.isInvincible = true;
            this.respawnTime = System.currentTimeMillis();
            this.isReloading = false;
            this.vx = 0;
            this.vy = 0;
            this.ax = 0;
            this.ay = 0;
            this.isShooting = false;
            this.isRequestingUnderhandThrow = false;
            this.keysDown.clear();

            if (gameMode == GameMode.TEAM_DEATHMATCH || gameMode == GameMode.DEATHMATCH) {
                  // 这里解决团队死斗/死斗模式玩家可以通过选择手枪直接把手枪塞到主武器槽内的BUG
                  // 通过分别存储选定的主武器和副武器名称，避免槽位覆盖
                  if (this.selectedPrimaryName == null || this.selectedPrimaryName.isEmpty()) {
                        this.selectedPrimaryName = (team == Team.T) ? "AK47" : "M4A4";
                  }
                  if (this.selectedSecondaryName == null || this.selectedSecondaryName.isEmpty()) {
                        this.selectedSecondaryName = (team == Team.T) ? "GLOCK18" : "USPS";
                  }

                  try {
                        this.primaryWeapon = Weapon.valueOf(this.selectedPrimaryName);
                  } catch (Exception e) {
                        this.primaryWeapon = (team == Team.T) ? Weapon.AK47 : Weapon.M4A4;
                        this.selectedPrimaryName = this.primaryWeapon.name();
                  }

                  try {
                        this.secondaryWeapon = Weapon.valueOf(this.selectedSecondaryName);
                  } catch (Exception e) {
                        this.secondaryWeapon = (team == Team.T) ? Weapon.GLOCK18 : Weapon.USPS;
                        this.selectedSecondaryName = this.secondaryWeapon.name();
                  }

                  int ammoMultiplier = (gameMode == GameMode.ZOMBIE_MODE || gameMode == GameMode.DEATHMATCH) ? 1000 : 4;
                  if (this.primaryWeapon != null) {
                        this.primary_currentAmmo = this.primaryWeapon.magazineSize;
                        this.primary_reserveAmmo = this.primaryWeapon.magazineSize * ammoMultiplier;
                  }
                  if (this.secondaryWeapon != null) {
                        this.secondary_currentAmmo = this.secondaryWeapon.magazineSize;
                        this.secondary_reserveAmmo = this.secondaryWeapon.magazineSize * ammoMultiplier;
                  }
                  this.switchToSlot(1);
            } else if (gameMode == GameMode.ZOMBIE_MODE) {
                  if (team == Team.CT) {
                        if (this.isAI && this.zombieController != null)
                              this.zombieController.initializeWeaponChoice();
                        try {
                              setWeapon(Weapon.valueOf(this.nextWeapon), gameMode);
                        } catch (Exception e) {
                              setWeapon(Weapon.XM1014, gameMode);
                        }
                  } else {
                        this.primaryWeapon = null;
                        this.secondaryWeapon = null;
                        this.currentSlot = 3;
                        this.currentAmmo = 0;
                        this.reserveAmmo = 0;
                  }
            }
            this.damageLog.clear();
            if (this.isAI) {
                  if (this.pathfindingModule != null)
                        this.pathfindingModule.reset();
                  if (this.perceptionModule != null)
                        this.perceptionModule.clearPerceptions();
                  if (this.attackModule != null)
                        this.attackModule.reset();
                  if (this.tdmController != null)
                        this.tdmController.reset();
                  if (this.zombieController != null)
                        this.zombieController.reset();
            }
      }

      /**
       * 为新回合重置游戏状态（如移除购买记录、重置护甲/交互状态等）。
       */
      public void resetForNewRound() {
            this.health = 100;
            this.isInteracting = false;
            this.interactionStartTime = 0;
            this.itemsBoughtThisFreezeTime.clear();
            this.itemPurchaseCostsThisFreezeTime.clear();
            this.predictedRecoilAngle = 0;
            if (!wasAliveLastRound) {
                  this.armorValue = 0;
                  this.hasKevlar = false;
                  this.hasHelmet = false;
            }
            this.damageLog.clear();
      }

      public Rectangle getBounds() {
            return new Rectangle((int) (position.x - SIZE / 2), (int) (position.y - SIZE / 2), (int) SIZE, (int) SIZE);
      }

      /**
       * 将当前玩家的状态序列化为 JSON 对象，用于网络广播同步。
       * 
       * @return 包含基本属性及状态的 JsonObject
       */
      public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (isControllingBot() && gameState != null) {
                  Player controlledBot = gameState.getPlayerById(this.controllingBotId);
                  if (controlledBot != null && controlledBot.isAlive()) {
                        obj.addProperty("id", this.id);
                        obj.addProperty("name", this.name + " (Controlling " + controlledBot.name + ")");
                        obj.addProperty("team", this.team.name());
                        obj.addProperty("x", controlledBot.position.x);
                        obj.addProperty("y", controlledBot.position.y);
                        obj.addProperty("vx", controlledBot.vx);
                        obj.addProperty("vy", controlledBot.vy);
                        obj.addProperty("angle", controlledBot.angle);
                        obj.addProperty("isAlive", true);
                        obj.addProperty("health", controlledBot.health);
                        obj.addProperty("maxHealth", controlledBot.maxHealth);
                        obj.addProperty("armorValue", controlledBot.armorValue);
                        obj.addProperty("hasKevlar", controlledBot.hasKevlar);
                        obj.addProperty("hasHelmet", controlledBot.hasHelmet);
                        obj.addProperty("money", controlledBot.money);
                        obj.addProperty("isReloading", controlledBot.isReloading);
                        obj.addProperty("isInteracting", controlledBot.isInteracting);
                        obj.addProperty("interactionStart", controlledBot.interactionStartTime);
                        // [核心修复] 使用控制者（人类）自己的累计积分、击杀、死亡
                        obj.addProperty("score", this.score);
                        obj.addProperty("kills", this.kills);
                        obj.addProperty("deaths", this.deaths);
                        obj.addProperty("totalShotsFired", this.totalShotsFired);
                        obj.addProperty("totalShotsHit", this.totalShotsHit);
                        obj.addProperty("totalHeadshots", this.totalHeadshots);
                        obj.addProperty("damageDealt", this.damageDealt);
                        Weapon currentWep = controlledBot.getCurrentWeapon();
                        obj.addProperty("weaponName", currentWep != null ? currentWep.name() : "Fists");
                        obj.addProperty("weaponKey", currentWep != null ? currentWep.name() : "KNIFE");
                        obj.addProperty("currentAmmo", controlledBot.currentAmmo);
                        obj.addProperty("reserveAmmo", controlledBot.reserveAmmo);
                        obj.addProperty("currentSlot", controlledBot.currentSlot);
                        JsonArray equipArray = new JsonArray();
                        synchronized (controlledBot.equipment) {
                              controlledBot.equipment.forEach((item, count) -> {
                                    JsonObject eq = new JsonObject();
                                    eq.addProperty("name", item.name());
                                    eq.addProperty("count", count);
                                    equipArray.add(eq);
                              });
                        }
                        obj.add("equipment", equipArray);
                        obj.addProperty("spectatorMode", this.spectatorMode);
                        obj.addProperty("spectatorTargetId", this.spectatorTargetId);

                        if (gameState != null) {
                              obj.addProperty("inForbiddenZone",
                                          gameState.isPointInGeneralForbiddenZone(controlledBot.position));
                        } else {
                              obj.addProperty("inForbiddenZone", false);
                        }
                        return obj;
                  }
            }
            obj.addProperty("id", id);
            obj.addProperty("name", name);
            obj.addProperty("team", team.name());
            obj.addProperty("x", Math.round(position.x * 10.0) / 10.0);
            obj.addProperty("y", Math.round(position.y * 10.0) / 10.0);
            obj.addProperty("vx", Math.round(vx * 10.0) / 10.0);
            obj.addProperty("vy", Math.round(vy * 10.0) / 10.0);
            obj.addProperty("angle", Math.round(angle * 10.0) / 10.0);
            obj.addProperty("isAlive", isAlive());
            obj.addProperty("health", health);
            obj.addProperty("maxHealth", maxHealth);
            obj.addProperty("isInvincible", isInvincible);
            obj.addProperty("isSlowed", isSlowed);
            obj.addProperty("score", score);
            obj.addProperty("kills", kills);
            obj.addProperty("deaths", deaths);
            obj.addProperty("money", money);
            obj.addProperty("armorValue", armorValue);
            obj.addProperty("hasKevlar", hasKevlar);
            obj.addProperty("hasHelmet", hasHelmet);
            obj.addProperty("hasDefuseKit", hasDefuseKit);
            obj.addProperty("hasBomb", hasBomb);
            obj.addProperty("isReloading", isReloading);
            obj.addProperty("isInteracting", isInteracting);
            obj.addProperty("interactionStart", interactionStartTime);
            obj.addProperty("predictedRecoilAngle", predictedRecoilAngle);
            Weapon currentWep = getCurrentWeapon();
            obj.addProperty("weaponName", currentWep != null ? currentWep.name() : "Fists");
            obj.addProperty("weaponKey", currentWep != null ? currentWep.name() : "KNIFE");
            obj.addProperty("currentAmmo", currentAmmo);
            obj.addProperty("reserveAmmo", reserveAmmo);
            obj.addProperty("currentSlot", currentSlot);
            obj.addProperty("totalShotsFired", this.totalShotsFired);
            obj.addProperty("totalShotsHit", this.totalShotsHit);
            obj.addProperty("totalHeadshots", this.totalHeadshots);
            obj.addProperty("damageDealt", this.damageDealt);
            JsonArray equipArray = new JsonArray();
            synchronized (this.equipment) {
                  this.equipment.forEach((item, count) -> {
                        JsonObject eq = new JsonObject();
                        eq.addProperty("name", item.name());
                        eq.addProperty("count", count);
                        equipArray.add(eq);
                  });
            }
            obj.add("equipment", equipArray);
            JsonArray boughtItems = new JsonArray();
            synchronized (itemsBoughtThisFreezeTime) {
                  itemsBoughtThisFreezeTime.forEach(boughtItems::add);
            }
            obj.add("itemsBoughtThisFreezeTime", boughtItems);

            if (gameState != null) {
                  obj.addProperty("inForbiddenZone", gameState.isPointInGeneralForbiddenZone(this.position));
            } else {
                  obj.addProperty("inForbiddenZone", false);
            }
            return obj;
      }

      public Rectangle getBounds2D() {
            return new Rectangle((int) (position.x - SIZE / 2), (int) (position.y - SIZE / 2), (int) SIZE, (int) SIZE);
      }

      public record PlayerSnapshot(String id, String name, Point2D.Double position, Player.Team team, boolean isAI,
                  int health, double angle, double vx, double vy, boolean isInteracting, long lastShotTime,
                  boolean hasBomb) {
      }

      public PlayerSnapshot createSnapshot() {
            return new PlayerSnapshot(this.id, this.name, (Point2D.Double) this.position.clone(), this.team, this.isAI,
                        this.health, this.angle, this.vx, this.vy, this.isInteracting, this.lastShotTime, this.hasBomb);
      }

      public boolean isControllingBot() {
            return this.controllingBotId != null;
      }

      public boolean isControlledByPlayer() {
            return this.controlledByPlayerId != null;
      }

      public boolean isAI() {
            return this.isAI;
      }
}
