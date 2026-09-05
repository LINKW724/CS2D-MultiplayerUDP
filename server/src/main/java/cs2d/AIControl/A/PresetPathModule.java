// [新文件]
package cs2d.AIControl.A;

import cs2d.AIControl.route.PresetRouteCatalogRepository;
import cs2d.AIControl.route.RouteCatalog;
import cs2d.AIControl.route.RouteDescriptor;
import cs2d.AIControl.route.RouteType;
import cs2d.playerAndAi.Player; // [新] 需要 Player 来判断阵营

import java.awt.geom.Point2D;
import java.util.List;
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

    private RouteCatalog routeCatalog;

    private boolean isLoaded = false;
    private String loadedMapName = "";


    /**
     * 构造函数
     */
    public PresetPathModule() {
        // 构造函数现在非常轻量
    }

    /** Loads or reuses the process-wide immutable route catalog. */
    public void loadMap(String mapName) {
        this.routeCatalog = PresetRouteCatalogRepository.load(mapName);
        this.loadedMapName = routeCatalog.mapName();
        this.isLoaded = !routeCatalog.isEmpty();
    }

    public RouteCatalog getRouteCatalog() {
        return routeCatalog;
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

        RouteType routeType = toRouteType(pathType);
        if (!isLoaded || routeCatalog == null || routeType == null) {
            return null; // 未加载
        }
        List<RouteDescriptor> routes = routeCatalog.routesFor(routeType);
        if (routes.isEmpty()) {
            // System.err.println("PresetPathModule: 类型 " + pathType + " 没有可用的预设路径。");
            return null; // 该类型没有路径
        }

        // 同一波次尽量覆盖所有地图路线，再开始下一轮。
        int selectionIndex = PresetRouteAllocator.nextRouteIndex(loadedMapName, pathType, routes.size());
        RouteDescriptor route = routes.get(selectionIndex);
        List<Point2D.Double> points = route.keyPoints().stream()
                .map(point -> new Point2D.Double(point.x(), point.y()))
                .toList();
        return new PresetPathSelection(route.routeIndex(), points);
    }

    /** Selects one authored route by its stable catalog id, without allocator rotation. */
    public PresetPathSelection getPresetPathSelection(String routeId) {
        if (!isLoaded || routeCatalog == null || routeId == null) {
            return null;
        }
        return routeCatalog.find(routeId)
                .filter(route -> !route.keyPoints().isEmpty())
                .map(route -> new PresetPathSelection(route.routeIndex(), route.keyPoints().stream()
                        .map(point -> new Point2D.Double(point.x(), point.y()))
                        .toList()))
                .orElse(null);
    }

    /**
     * 保留旧接口，避免其他模式或外部工具失去兼容性。
     */
    @Deprecated
    public List<Point2D.Double> getRandomPresetKeyPoints(Player.Team team, PathType pathType) {
        return getPresetKeyPoints(team, pathType);
    }

    private static RouteType toRouteType(PathType pathType) {
        if (pathType == null) {
            return null;
        }
        return switch (pathType) {
            case TDM_T_CT -> RouteType.TDM_T_CT;
            case TDM_CT_T -> RouteType.TDM_CT_T;
            case DEMO_CT_A -> RouteType.DEMO_CT_A;
            case DEMO_CT_B -> RouteType.DEMO_CT_B;
            case DEMO_T_A -> RouteType.DEMO_T_A;
            case DEMO_T_B -> RouteType.DEMO_T_B;
        };
    }
}
