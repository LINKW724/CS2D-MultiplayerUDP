// 文件: WaypointNode.java (新文件，放在 cs2d.server 或 cs2d.playerAndAi 包中)
package cs2d.playerAndAi.doublePlayer;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务器运行时使用的路径点节点。
 * 它包含对邻居节点的直接引用，用于A*寻路。
 */
public class WaypointNode {
    public final int index; // 对应其在 MapData 列表中的索引
    public final Point2D.Double position;
    public List<WaypointNode> neighbors; // 存储直接对象引用
    public List<Double> costs;       // 存储到邻居的成本

    // A* 寻路算法需要的数据
    public WaypointNode parent;
    public double gCost;
    public double hCost;

    public WaypointNode(int index, Point2D.Double position) {
        this.index = index;
        this.position = position;
        this.neighbors = new ArrayList<>();
        this.costs = new ArrayList<>();
        reset();
    }

    public double fCost() {
        return gCost + hCost;
    }

    // 重置 A* 寻路数据
    public void reset() {
        this.parent = null;
        this.gCost = Double.POSITIVE_INFINITY; // 初始化 gCost 为无穷大
        this.hCost = 0;
    }
}