package cs2d.server;// GameState.java: 内部新增 SpatialGrid 类 (或单独文件)

import cs2d.playerAndAi.Player;

import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空间网格：用于加速实体查找和碰撞检测。
 */
public class SpatialGrid {
    // 假设玩家/僵尸尺寸 Player.SIZE = 24，我们将格子大小设为玩家尺寸的 4 倍
    private final int CELL_SIZE = 100;
    private final int mapWidth;
    private final int mapHeight;

    // 存储实体列表的并发哈希表：Key=GridIndex, Value=List<Player>
    private final Map<Integer, List<Player>> grid = new ConcurrentHashMap<>();

    public SpatialGrid(int mapWidth, int mapHeight) {
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
    }

    /**
     * 根据世界坐标计算网格的唯一索引。
     */
    private int getIndex(Point2D.Double pos) {
        int col = (int) (pos.x / CELL_SIZE);
        int row = (int) (pos.y / CELL_SIZE);
        // 使用简单的哈希函数将二维坐标映射到一维索引
        return row * (mapWidth / CELL_SIZE + 1) + col;
    }

    /**
     * 将所有实体添加到网格中。通常在每次 GameState.update() 开始时调用。
     */
    public void populate(List<Player> entities) {
        grid.clear(); // 每次刷新时清空网格
        for (Player p : entities) {
            int index = getIndex(p.position);
            // 使用 computeIfAbsent 确保线程安全地初始化 List
            grid.computeIfAbsent(index, k -> new LinkedList<>()).add(p);
        }
    }

    /**
     * 获取给定实体附近（包括自身格子）的所有潜在碰撞对象。
     * @param target 目标实体。
     * @return 附近的实体列表。
     */
    public List<Player> getNearby(Player target) {
        // [关键] 必须检查目标所在的 3x3 区域

        int targetCol = (int) (target.position.x / CELL_SIZE);
        int targetRow = (int) (target.position.y / CELL_SIZE);

        List<Player> nearby = new LinkedList<>();
        int maxCol = mapWidth / CELL_SIZE;
        int maxRow = mapHeight / CELL_SIZE;

        for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
            for (int colOffset = -1; colOffset <= 1; colOffset++) {
                int col = targetCol + colOffset;
                int row = targetRow + rowOffset;

                // 边界检查
                if (col >= 0 && col <= maxCol && row >= 0 && row <= maxRow) {
                    int index = row * (maxCol + 1) + col;
                    // 获取该格子内的所有实体
                    List<Player> cellEntities = grid.getOrDefault(index, Collections.emptyList());

                    for(Player p : cellEntities) {
                        // 排除目标自身
                        if (p != target) {
                            nearby.add(p);
                        }
                    }
                }
            }
        }
        return nearby;
    }
}