package cs2d.playerAndAi;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * "粗算"系统中的一个节点（粗略点）
 */
public class Waypoint {
    
    public final Point2D.Double position;
    
    // "粗算"A*寻路时需要的数据
    public Map<Waypoint, Double> neighbors; // 相邻的Waypoint和到它的距离
    public Waypoint parent;                 // A* 寻路用的父节点
    public double gCost;                    // A* g_cost
    public double hCost;                    // A* h_cost

    public Waypoint(Point2D.Double position) {
        this.position = position;
        this.neighbors = new HashMap<>();
    }
    
    // 添加一个相邻的、无障碍的粗略点
    public void addNeighbor(Waypoint neighbor, double cost) {
        this.neighbors.put(neighbor, cost);
    }
    
    public double fCost() {
        return gCost + hCost;
    }
    
    // 重置A*寻路数据，以便下次复用
    public void reset() {
        this.parent = null;
        this.gCost = 0;
        this.hCost = 0;
    }
}
