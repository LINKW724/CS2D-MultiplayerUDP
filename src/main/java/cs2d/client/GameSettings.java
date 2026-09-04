package cs2d.client;

import com.google.gson.JsonObject;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.paint.Color;

/**
 * 游戏设置类，存储和管理客户端的游戏偏好。
 */
public class GameSettings {
    public static final int MIN_KILL_FEED_ENTRIES = 0;
    public static final int MAX_KILL_FEED_ENTRIES = 10;
    public static final double MIN_FOG_DARKNESS = 0.0;
    public static final double MAX_FOG_DARKNESS = 1.0;
    public static final double DEFAULT_FOG_DARKNESS = 0.7;
    private static final BooleanProperty followRecoil = new SimpleBooleanProperty(true);
    private static final BooleanProperty showAimLine = new SimpleBooleanProperty(false);
    private static final BooleanProperty wallPenetrationPrediction = new SimpleBooleanProperty(false); // 新增：穿墙伤害预测开关
    private static final ObjectProperty<Color> aimLineColor = new SimpleObjectProperty<>(Color.LIMEGREEN);
    private static final ObjectProperty<Color> crosshairColor = new SimpleObjectProperty<>(Color.LIMEGREEN);
    private static final ObjectProperty<Color> wallPenColorFull = new SimpleObjectProperty<>(Color.GREEN); // 100% 伤害时的颜色
    private static final ObjectProperty<Color> wallPenColorNone = new SimpleObjectProperty<>(Color.RED);   // 0% 伤害时的颜色
    private static final DoubleProperty aimLineOpacity = new SimpleDoubleProperty(0.7);
    private static final IntegerProperty maxKillFeedEntries = new SimpleIntegerProperty(5);
    private static final DoubleProperty fogDarkness = new SimpleDoubleProperty(DEFAULT_FOG_DARKNESS);
    private static final DamageNumberModePolicy damageNumberModePolicy = new DamageNumberModePolicy();

    private static final BooleanProperty mouseWheelZoomEnabled = new SimpleBooleanProperty(true); // 新增：鼠标滚轮缩放开关
    private static final DoubleProperty followZoomFactor = new SimpleDoubleProperty(1.0);

    public boolean isFollowRecoil() {
        return followRecoil.get();
    }

    public BooleanProperty followRecoilProperty() {
        return followRecoil;
    }

    public boolean isShowAimLine() {
        return showAimLine.get();
    }

    public BooleanProperty showAimLineProperty() {
        return showAimLine;
    }

    public boolean isWallPenetrationPrediction() {
        return wallPenetrationPrediction.get();
    }

    public BooleanProperty wallPenetrationPredictionProperty() {
        return wallPenetrationPrediction;
    }

    public boolean isMouseWheelZoomEnabled() {
        return mouseWheelZoomEnabled.get();
    }

    public BooleanProperty mouseWheelZoomEnabledProperty() {
        return mouseWheelZoomEnabled;
    }

    public Color getAimLineColor() {
        return aimLineColor.get();
    }

    public ObjectProperty<Color> aimLineColorProperty() {
        return aimLineColor;
    }

    public Color getCrosshairColor() {
        return crosshairColor.get();
    }

    public ObjectProperty<Color> crosshairColorProperty() {
        return crosshairColor;
    }

    public Color getWallPenColorFull() {
        return wallPenColorFull.get();
    }

    public ObjectProperty<Color> wallPenColorFullProperty() {
        return wallPenColorFull;
    }

    public Color getWallPenColorNone() {
        return wallPenColorNone.get();
    }

    public ObjectProperty<Color> wallPenColorNoneProperty() {
        return wallPenColorNone;
    }

    public double getAimLineOpacity() {
        return aimLineOpacity.get();
    }

    public DoubleProperty aimLineOpacityProperty() {
        return aimLineOpacity;
    }

    public int getMaxKillFeedEntries() {
        return maxKillFeedEntries.get();
    }

    public IntegerProperty maxKillFeedEntriesProperty() {
        return maxKillFeedEntries;
    }

    public void setMaxKillFeedEntries(int entries) {
        maxKillFeedEntries.set(clampKillFeedEntries(entries));
    }

    static int clampKillFeedEntries(int entries) {
        return Math.max(MIN_KILL_FEED_ENTRIES, Math.min(MAX_KILL_FEED_ENTRIES, entries));
    }

    public double getFogDarkness() {
        return fogDarkness.get();
    }

    public DoubleProperty fogDarknessProperty() {
        return fogDarkness;
    }

    public void setFogDarkness(double darkness) {
        fogDarkness.set(clampFogDarkness(darkness));
    }

    static double clampFogDarkness(double darkness) {
        if (!Double.isFinite(darkness)) {
            return DEFAULT_FOG_DARKNESS;
        }
        return Math.max(MIN_FOG_DARKNESS, Math.min(MAX_FOG_DARKNESS, darkness));
    }

    public DamageNumberVisibility getDamageNumberVisibilityFor(String gameMode) {
        return damageNumberModePolicy.getVisibilityFor(gameMode);
    }

    public ObjectProperty<DamageNumberVisibility> damageNumberVisibilityProperty(String gameMode) {
        return damageNumberModePolicy.visibilityProperty(gameMode);
    }

    public void setDamageNumberVisibility(String gameMode, DamageNumberVisibility visibility) {
        damageNumberModePolicy.setVisibility(gameMode, visibility);
    }

    public boolean showsOwnDamageNumbers(String gameMode) {
        return damageNumberModePolicy.showsOwnDamage(gameMode);
    }

    public boolean showsTeammateDamageNumbers(String gameMode) {
        return damageNumberModePolicy.showsTeammateDamage(gameMode);
    }

    public boolean showsSelfCombatFeedback(String gameMode) {
        return damageNumberModePolicy.showsSelfFeedback(gameMode);
    }

    public static double getFollowZoomFactor() {
        return followZoomFactor.get();
    }

    public DoubleProperty followZoomFactorProperty() {
        return followZoomFactor;
    }

    public void setFollowZoomFactor(double factor) {
        // 添加一些合理的限制，例如最小 0.25 倍，最大 4 倍
        followZoomFactor.set(Math.max(0.25, Math.min(factor, 4.0)));
    }

    /**
     * 将设置转换为 JsonObject。
     *
     * @return 包含设置信息的 JsonObject
     */
    public static JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("followRecoil", followRecoil.get());
        json.addProperty("showAimLine", showAimLine.get());
        json.addProperty("wallPenetrationPrediction", wallPenetrationPrediction.get());
        json.addProperty("mouseWheelZoomEnabled", mouseWheelZoomEnabled.get()); // 新增
        json.addProperty("aimLineColor", aimLineColor.get().toString());
        json.addProperty("aimLineOpacity", aimLineOpacity.get());
        json.addProperty("crosshairColor", crosshairColor.get().toString());
        json.addProperty("wallPenColorFull", wallPenColorFull.get().toString()); // 新增
        json.addProperty("wallPenColorNone", wallPenColorNone.get().toString()); // 新增
        json.addProperty("followZoomFactor", getFollowZoomFactor());
        json.addProperty("maxKillFeedEntries", maxKillFeedEntries.get());
        json.addProperty("fogDarkness", fogDarkness.get());
        json.add("damageNumbers", damageNumberModePolicy.toJson());
        return json;
    }

    /**
     * 从 JsonObject 中加载设置。
     *
     * @param json 包含设置信息的 JsonObject
     */
    public void fromJson(JsonObject json) {
        if (json == null) {
            return;
        }
        try {
            if (json.has("followRecoil")) {
                followRecoil.set(json.get("followRecoil").getAsBoolean());
            }
            if (json.has("showAimLine")) {
                showAimLine.set(json.get("showAimLine").getAsBoolean());
            }
            if (json.has("wallPenetrationPrediction")) {
                wallPenetrationPrediction.set(json.get("wallPenetrationPrediction").getAsBoolean());
            }
            if (json.has("mouseWheelZoomEnabled")) { // 新增
                mouseWheelZoomEnabled.set(json.get("mouseWheelZoomEnabled").getAsBoolean());
            }
            if (json.has("aimLineColor")) {
                aimLineColor.set(Color.valueOf(json.get("aimLineColor").getAsString()));
            }
            if (json.has("aimLineOpacity")) {
                aimLineOpacity.set(json.get("aimLineOpacity").getAsDouble());
            }
            if (json.has("crosshairColor")) {
                crosshairColor.set(Color.valueOf(json.get("crosshairColor").getAsString()));
            }
            if (json.has("wallPenColorFull")) { // 新增
                wallPenColorFull.set(Color.valueOf(json.get("wallPenColorFull").getAsString()));
            }
            if (json.has("wallPenColorNone")) { // 新增
                wallPenColorNone.set(Color.valueOf(json.get("wallPenColorNone").getAsString()));
            }
            if (json.has("maxKillFeedEntries")) {
                setMaxKillFeedEntries(json.get("maxKillFeedEntries").getAsInt());
            }
            if (json.has("followZoomFactor")) {
                setFollowZoomFactor(json.get("followZoomFactor").getAsDouble());
            }
            if (json.has("fogDarkness")) {
                setFogDarkness(json.get("fogDarkness").getAsDouble());
            }
            if (json.has("damageNumbers") && json.get("damageNumbers").isJsonObject()) {
                damageNumberModePolicy.fromJson(json.getAsJsonObject("damageNumbers"));
            }
        } catch (Exception e) {
            System.err.println("从JSON文件加载设置时出错: " + e.getMessage() + "。将使用默认值。");
        }
    }
}
