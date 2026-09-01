// [新文件]
package cs2d.AIControl.A;

import com.google.gson.Gson;
import cs2d.AIControl.A.PathfindingModule.Node;
import cs2d.AIControl.A.PathfindingModule.Pathfinder;
import cs2d.AIControl.BW.MapViz.DynamicPathfinderVisualizer;
import cs2d.playerAndAi.Player; // [新] 需要 Player 来判断阵营

import java.awt.geom.Point2D;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cs2d.AIControl.BW.MapViz.DynamicPathfinderVisualizer.*; // 导入 PathType 枚举

/**
 * [新] 预设寻路模块 (内嵌模块)
 * [修改] 加载 TDM 和 Demolition 路径，并根据请求返回对应类型的路径列表。
 */
public class PresetPathModule {

    /** A selected map-authored route together with its stable index. */
    public record PresetPathSelection(int routeIndex, List<Point2D.Double> keyPoints) {
        public PresetPathSelection {
            keyPoints = List.copyOf(keyPoints);
        }
    }

    private static final String PRE_PATH_DIR = "O:\\java\\games\\CS2D-MultiplayerUDP\\maps\\prePath";

    // =========================================================================
    // [新] 静态缓存
    // =========================================================================

    /** * 用于Gson解析的 POJO (现在包含所有路径类型)
     */
    private static class PresetPathCollection {
        List<List<Point2D.Double>> presetPathsTtoCT;
        List<List<Point2D.Double>> presetPathsCTtoT;
        List<List<Point2D.Double>> presetPathsCTtoA; // [新]
        List<List<Point2D.Double>> presetPathsCTtoB; // [新]
        List<List<Point2D.Double>> presetPathsTtoA;  // [新]
        List<List<Point2D.Double>> presetPathsTtoB;  // [新]
    }

    /**
     * 静态缓存
     */
    private static Map<String, PresetPathCollection> pathCache = new HashMap<>();

    /**
     * 静态锁
     */
    private static final Object cacheLock = new Object();
    // =========================================================================


    // --- 实例字段 ---
    // [修改] 指向缓存中完整的 PresetPathCollection 对象
    private PresetPathCollection loadedCollection;

    private boolean isLoaded = false;
    private String loadedMapName = "";


    /**
     * 构造函数
     */
    public PresetPathModule() {
        // 构造函数现在非常轻量
    }

    /**
     * [修改] 加载地图，现在会优先使用静态缓存
     * @param mapName 地图名 (例如 "mirage_3x无粗")
     */
    public void loadMap(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            System.err.println("PresetPathModule: mapName 为空，无法加载。");
            return;
        }
        this.loadedMapName = mapName;

        // --- [新] 缓存检查 ---
        PresetPathCollection cachedData = pathCache.get(mapName);

        if (cachedData != null) {
            // 缓存命中！
            this.loadedCollection = cachedData;
            // 检查是否至少加载了一种路径
            this.isLoaded = (cachedData.presetPathsTtoCT != null && !cachedData.presetPathsTtoCT.isEmpty()) ||
                    (cachedData.presetPathsCTtoT != null && !cachedData.presetPathsCTtoT.isEmpty()) ||
                    (cachedData.presetPathsCTtoA != null && !cachedData.presetPathsCTtoA.isEmpty()) ||
                    (cachedData.presetPathsCTtoB != null && !cachedData.presetPathsCTtoB.isEmpty()) ||
                    (cachedData.presetPathsTtoA != null && !cachedData.presetPathsTtoA.isEmpty()) ||
                    (cachedData.presetPathsTtoB != null && !cachedData.presetPathsTtoB.isEmpty());
            return;
        }

        // --- 缓存未命中，需要从硬盘加载 ---
        synchronized (cacheLock) {
            // 再次检查
            cachedData = pathCache.get(mapName);
            if (cachedData != null) {
                this.loadedCollection = cachedData;
                this.isLoaded = (cachedData.presetPathsTtoCT != null && !cachedData.presetPathsTtoCT.isEmpty()) ||
                        (cachedData.presetPathsCTtoT != null && !cachedData.presetPathsCTtoT.isEmpty()) ||
                        (cachedData.presetPathsCTtoA != null && !cachedData.presetPathsCTtoA.isEmpty()) ||
                        (cachedData.presetPathsCTtoB != null && !cachedData.presetPathsCTtoB.isEmpty()) ||
                        (cachedData.presetPathsTtoA != null && !cachedData.presetPathsTtoA.isEmpty()) ||
                        (cachedData.presetPathsTtoB != null && !cachedData.presetPathsTtoB.isEmpty());
                return;
            }

            // --- 真正执行I/O加载 ---
            String filePath = Paths.get(PRE_PATH_DIR, mapName + "_prePath.json").toString();
            try (FileReader reader = new FileReader(filePath)) {
                Gson gson = new Gson();
                PresetPathCollection data = gson.fromJson(reader, PresetPathCollection.class);

                if (data != null) {
                    // [核心] 将新加载的数据存入 *实例* 和 *静态缓存*
                    this.loadedCollection = data;

                    // 检查并初始化空的列表，防止 NullPointerException
                    if (data.presetPathsTtoCT == null) data.presetPathsTtoCT = new ArrayList<>();
                    if (data.presetPathsCTtoT == null) data.presetPathsCTtoT = new ArrayList<>();
                    if (data.presetPathsCTtoA == null) data.presetPathsCTtoA = new ArrayList<>();
                    if (data.presetPathsCTtoB == null) data.presetPathsCTtoB = new ArrayList<>();
                    if (data.presetPathsTtoA == null) data.presetPathsTtoA = new ArrayList<>();
                    if (data.presetPathsTtoB == null) data.presetPathsTtoB = new ArrayList<>();

                    this.isLoaded = !data.presetPathsTtoCT.isEmpty() || !data.presetPathsCTtoT.isEmpty() ||
                            !data.presetPathsCTtoA.isEmpty() || !data.presetPathsCTtoB.isEmpty() ||
                            !data.presetPathsTtoA.isEmpty() || !data.presetPathsTtoB.isEmpty();

                    pathCache.put(mapName, data); // 存入缓存

                    // [重要] 这个日志现在只会打印一次！
                    System.out.println("PresetPathModule: 成功加载预设路径 (地图: " + mapName + ")");
                    System.out.println("  - TDM T->CT: " + data.presetPathsTtoCT.size() + " 条");
                    System.out.println("  - TDM CT->T: " + data.presetPathsCTtoT.size() + " 条");
                    System.out.println("  - Demo CT->A: " + data.presetPathsCTtoA.size() + " 条");
                    System.out.println("  - Demo CT->B: " + data.presetPathsCTtoB.size() + " 条");
                    System.out.println("  - Demo T->A: " + data.presetPathsTtoA.size() + " 条"); // 这里会显示 3
                    System.out.println("  - Demo T->B: " + data.presetPathsTtoB.size() + " 条"); // 这里也会显示 3

                } else {
                    System.err.println("PresetPathModule: JSON数据为空或格式错误: " + filePath);
                    this.loadedCollection = new PresetPathCollection(); // 初始化为空对象
                }

            } catch (IOException e) {
//                System.err.println("PresetPathModule: 找不到或无法读取预设路径文件: " + filePath);
//                System.err.println("         请先运行 DynamicPathfinderVisualizer.java 来(重新)生成该文件！");
                this.isLoaded = false;
                this.loadedCollection = new PresetPathCollection(); // 初始化为空对象
            }
        } // --- 释放锁 ---
    }

    public boolean isLoaded() {
        return isLoaded;
    }

    /**
     * 根据AI阵营和目标类型，选择一条预设的"关键点"路径。
     * 同一地图、同一路径类型会均衡轮转，避免一批AI随机挤到同一条路线。
     * @param team AI的阵营
     * @param pathType 路径类型 (TDM_T_CT, DEMO_CT_A 等)
     * @return 一个 Point2D.Double 列表 (约10个点)，如果失败则返回 null
     */
    public List<Point2D.Double> getPresetKeyPoints(Player.Team team, PathType pathType) {
        PresetPathSelection selection = getPresetPathSelection(team, pathType);
        return selection == null ? null : selection.keyPoints();
    }

    /**
     * Selects a balanced route and exposes its index for higher-level tactical
     * occupancy analysis. Callers that only need points should use
     * {@link #getPresetKeyPoints(Player.Team, PathType)}.
     */
    public PresetPathSelection getPresetPathSelection(Player.Team team, PathType pathType) {

        if (!isLoaded || loadedCollection == null) {
            return null; // 未加载
        }

        List<List<Point2D.Double>> targetList = null;

        //   根据 pathType 选择正确的列表
        switch (pathType) {
            case TDM_T_CT:
                targetList = loadedCollection.presetPathsTtoCT;
                break;
            case TDM_CT_T:
                targetList = loadedCollection.presetPathsCTtoT;
                break;
            case DEMO_CT_A:
                targetList = loadedCollection.presetPathsCTtoA;
                break;
            case DEMO_CT_B:
                targetList = loadedCollection.presetPathsCTtoB;
                break;
            case DEMO_T_A:
                targetList = loadedCollection.presetPathsTtoA;
                break;
            case DEMO_T_B:
                targetList = loadedCollection.presetPathsTtoB;
                break;
        }

        if (targetList == null || targetList.isEmpty()) {
            // System.err.println("PresetPathModule: 类型 " + pathType + " 没有可用的预设路径。");
            return null; // 该类型没有路径
        }

        // 同一波次尽量覆盖所有地图路线，再开始下一轮。
        int routeIndex = PresetRouteAllocator.nextRouteIndex(loadedMapName, pathType, targetList.size());
        List<Point2D.Double> chosenPathPoints = targetList.get(routeIndex);

        if (chosenPathPoints == null || chosenPathPoints.isEmpty()) {
            System.err.println("PresetPathModule: 选择的路径为空！");
            return null;
        }

        // 2. 返回这个 "点列表"
        return new PresetPathSelection(routeIndex, chosenPathPoints);
    }

    /**
     * 保留旧接口，避免其他模式或外部工具失去兼容性。
     */
    @Deprecated
    public List<Point2D.Double> getRandomPresetKeyPoints(Player.Team team, PathType pathType) {
        return getPresetKeyPoints(team, pathType);
    }
}
