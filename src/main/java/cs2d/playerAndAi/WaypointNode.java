package cs2d.playerAndAi;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 粗算系统中的节点，包含 ID 和邻居信息。
 * 用于 GameState 中的 waypointGraph。
 */
public class WaypointNode {
    public int index;
    public Point2D.Double position;
    public List<WaypointNode> neighbors;
    public List<Double> costs;

    // A* 寻路所需数据 (用于高层 A*)
    public WaypointNode parent;
    public double gCost;
    public double hCost;

    public WaypointNode(int index, Point2D.Double position) {
        this.index = index;
        this.position = position;
        this.neighbors = new ArrayList<>();
        this.costs = new ArrayList<>();
    }

    /**
     * A* 估值函数 f = g + h
     */
    public double fCost() {
        return gCost + hCost;
    }

    /**
     * 重置节点状态，以便下一次寻路复用。
     */
    public void reset() {
        this.parent = null;
        this.gCost = 0;
        this.hCost = 0;
    }
}
