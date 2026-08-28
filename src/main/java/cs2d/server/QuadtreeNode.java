// 文件: QuadtreeNode.java (确保放在 cs2d.server 包下)
package cs2d.server; // 确保包名正确

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
// --- ^^^^ ---

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器端使用的 Quadtree 节点。
 * 用于高效地查询空间中的静态障碍物 (ShapeWrapper)。
 * (已从客户端版本转换而来)
 */
public class QuadtreeNode {

    private final int maxObjects; // 节点在分裂前可以容纳的最大对象数
    private final int maxDepth;   // 树的最大深度
    private final int level;      // 当前节点的深度级别
    

    private final Rectangle2D.Double bounds; 

    

    private final List<MapData.ShapeWrapper> objects; 

    
    private QuadtreeNode[] children; // 四个子节点 (如果是叶子节点则为 null), 类型保持 QuadtreeNode

    /**
     * QuadtreeNode 的构造函数 (服务器版)。
     * @param level 当前深度级别。
     * @param bounds 此节点的矩形边界 (AWT 类型)。
     * @param maxObjects 分裂前的最大对象数。
     * @param maxDepth 最大树深度。
     */
    public QuadtreeNode(int level, Rectangle2D.Double bounds, int maxObjects, int maxDepth) { // <-- 修改参数类型
        this.level = level;
        this.bounds = bounds;
        this.maxObjects = maxObjects;
        this.maxDepth = maxDepth;
        this.objects = new ArrayList<>(); // 初始化为空列表
        this.children = new QuadtreeNode[4]; // 初始化子节点数组，元素初始为 null
    }

    /**
     * 清空 Quadtree 节点及其所有子节点。
     */
    public void clear() {
        objects.clear();
        if (children != null) { // <-- 修正: 检查 children 是否为 null
            for (int i = 0; i < children.length; i++) {
                if (children[i] != null) {
                    children[i].clear();
                    children[i] = null; // 移除引用
                }
            }
            // 不需要将 children 设为 null，split() 会重新创建
        }
    }

    /**
     * 将节点分裂成 4 个子节点。
     */
    private void split() {
        double subWidth = bounds.getWidth() / 2;
        double subHeight = bounds.getHeight() / 2;
        double x = bounds.getX(); // 使用 getX()
        double y = bounds.getY(); // 使用 getY()
        int nextLevel = level + 1;

        children = new QuadtreeNode[4]; // 重新创建数组

        // --- VVVV 核心修改: 创建 AWT Rectangle2D.Double VVVV ---
        // 右上 (索引 0)
        children[0] = new QuadtreeNode(nextLevel, new Rectangle2D.Double(x + subWidth, y, subWidth, subHeight), maxObjects, maxDepth);
        // 左上 (索引 1)
        children[1] = new QuadtreeNode(nextLevel, new Rectangle2D.Double(x, y, subWidth, subHeight), maxObjects, maxDepth);
        // 左下 (索引 2)
        children[2] = new QuadtreeNode(nextLevel, new Rectangle2D.Double(x, y + subHeight, subWidth, subHeight), maxObjects, maxDepth);
        // 右下 (索引 3)
        children[3] = new QuadtreeNode(nextLevel, new Rectangle2D.Double(x + subWidth, y + subHeight, subWidth, subHeight), maxObjects, maxDepth);
        // --- ^^^^ ---

        // 将此节点的对象重新分配给子节点
        int i = 0; // 使用索引循环以便安全移除
        while (i < objects.size()) {
            MapData.ShapeWrapper obj = objects.get(i); // 获取 ShapeWrapper
            int index = getIndex(obj); // 获取应该去的子节点索引
            if (index != -1 && children[index] != null) { // 确保子节点存在
                // 如果对象完全适合某个子节点，则将其移动到该子节点
                children[index].insert(obj);
                objects.remove(i); // 从当前节点移除
            } else {
                // 如果跨越边界或子节点不存在，保留在当前节点，索引递增
                i++;
            }
        }
    }

    /**
     * [服务器版] 确定对象属于哪个子节点。-1 表示对象跨越边界。
     * @param wrapper 要检查的对象 (ShapeWrapper)
     * @return 子节点索引 (0-3) 或 -1
     */
    private int getIndex(MapData.ShapeWrapper wrapper) { // <-- 修改参数类型
        int index = -1;
        // --- VVVV 核心修改: 使用 MapData 获取 AWT 边界 VVVV ---
        Rectangle2D.Double pRect = MapData.getObstacleBounds(wrapper); 
        // --- ^^^^ ---
        if (pRect == null || pRect.isEmpty()) return -1; // 无效边界

        double verticalMidpoint = bounds.getX() + (bounds.getWidth() / 2);
        double horizontalMidpoint = bounds.getY() + (bounds.getHeight() / 2);

        // --- VVVV 核心修改: 使用 AWT 坐标访问 VVVV ---
        // 对象是否完全位于顶部象限
        boolean topQuadrant = (pRect.getY() < horizontalMidpoint && pRect.getY() + pRect.getHeight() < horizontalMidpoint);
        // 对象是否完全位于底部象限
        boolean bottomQuadrant = (pRect.getY() > horizontalMidpoint);

        // 对象是否完全位于左侧象限
        if (pRect.getX() < verticalMidpoint && pRect.getX() + pRect.getWidth() < verticalMidpoint) {
            if (topQuadrant) {
                index = 1; // 左上子节点
            } else if (bottomQuadrant) {
                index = 2; // 左下子节点
            }
        }
        // 对象是否完全位于右侧象限
        else if (pRect.getX() > verticalMidpoint) {
            if (topQuadrant) {
                index = 0; // 右上子节点
            } else if (bottomQuadrant) {
                index = 3; // 右下子节点
            }
        }
        // --- ^^^^ ---
        return index;
    }

    /**
     * [服务器版] 将对象插入到四叉树中。
     * @param wrapper 要插入的对象 (ShapeWrapper)
     */
    public void insert(MapData.ShapeWrapper wrapper) { // <-- 修改参数类型
        if (wrapper == null) return; // 安全检查

        // 如果有子节点，尝试插入到子节点
        if (children != null && children[0] != null) { // 检查子节点数组及第一个元素是否存在
            int index = getIndex(wrapper);
            if (index != -1 && children[index] != null) { // 再次检查特定子节点是否存在
                children[index].insert(wrapper);
                return;
            }
        }

        // 否则，将对象存储在此节点
        objects.add(wrapper); // <-- 添加 ShapeWrapper

        // 检查是否需要分裂
        // [修正] 检查 children 是否为 null 来判断是否已分裂
        if (objects.size() > maxObjects && level < maxDepth && children == null) { 
            split(); // 分裂

            // 分裂后，重新尝试将当前节点的对象分配到子节点
            int i = 0;
            while (i < objects.size()) {
                MapData.ShapeWrapper currentObj = objects.get(i);
                int index = getIndex(currentObj);
                // 再次检查 index 和 children[index]
                if (index != -1 && children != null && children[index] != null) { 
                    children[index].insert(objects.remove(i)); // 移除并插入子节点
                } else {
                    i++; // 保留在当前节点
                }
            }
        }
    }

    /**
     * [服务器版] 查询与给定射线可能相交的所有对象。
     * @param results 存储结果的列表 (List<MapData.ShapeWrapper>)
     * @param rayStart 射线起点 (AWT Point2D.Double)
     * @param rayEnd 射线终点 (AWT Point2D.Double)
     * @return 传入的 results 列表 (为了链式调用)
     */
    public List<MapData.ShapeWrapper> queryRay(List<MapData.ShapeWrapper> results, Point2D.Double rayStart, Point2D.Double rayEnd) { // <-- 修改结果列表类型和参数类型
        // --- VVVV 核心修改: 使用 AWT Line2D VVVV ---
        Line2D.Double rayLine = new Line2D.Double(rayStart, rayEnd);
        // --- ^^^^ ---

        // --- 1. 剪枝步骤：检查射线是否与此节点的边界相交 ---
        // --- VVVV 核心修改: 使用 AWT intersectsLine VVVV ---
        if (!bounds.intersectsLine(rayLine)) { // 使用 AWT 的 intersectsLine
            return results; 
        }
        // --- ^^^^ ---

        // --- 2. 添加此节点的对象 (检查碰撞) ---
        for (MapData.ShapeWrapper obj : objects) { // <-- 遍历 ShapeWrapper
            if (obj == null) continue;
            // --- VVVV 核心修改: 使用 MapData 获取 AWT 边界 VVVV ---
            Rectangle2D.Double objBounds = MapData.getObstacleBounds(obj); 
            // --- ^^^^ ---
            // --- VVVV 核心修改: 使用 AWT intersectsLine VVVV ---
            if (objBounds != null && objBounds.intersectsLine(rayLine)) { 
                results.add(obj); // <-- 添加 ShapeWrapper
            }
            // --- ^^^^ ---
        }

        // --- 3. 递归检查子节点 ---
        if (children != null) { // <-- 修正: 检查 children 是否为 null
            // --- VVVV 核心修改: 使用 AWT intersectsLine VVVV ---
            if (children[0] != null && children[0].bounds.intersectsLine(rayLine)) children[0].queryRay(results, rayStart, rayEnd);
            if (children[1] != null && children[1].bounds.intersectsLine(rayLine)) children[1].queryRay(results, rayStart, rayEnd);
            if (children[2] != null && children[2].bounds.intersectsLine(rayLine)) children[2].queryRay(results, rayStart, rayEnd);
            if (children[3] != null && children[3].bounds.intersectsLine(rayLine)) children[3].queryRay(results, rayStart, rayEnd);
            // --- ^^^^ ---
        }

        return results;
    }

    // [删除] intersectsRay 方法，因为 AWT Rectangle2D.Double 自带 intersectsLine

    /**
     * [服务器版] 返回所有其边界与给定查询边界相交的对象。
     * @param returnObjects 用于收集结果的列表 (List<MapData.ShapeWrapper>)
     * @param queryBounds   要查询的矩形区域 (AWT Rectangle2D.Double)
     * @return 传入的 returnObjects 列表
     */
    public List<MapData.ShapeWrapper> queryBounds(List<MapData.ShapeWrapper> returnObjects, Rectangle2D.Double queryBounds) { // <-- 修改结果和参数类型
        // --- 1. 剪枝：检查查询区域是否与此节点的边界相交 ---
        // --- VVVV 核心修改: 使用 AWT intersects VVVV ---
        if (!bounds.intersects(queryBounds)) {
            return returnObjects; 
        }
        // --- ^^^^ ---

        // --- 2. 检查此节点直接存储的对象 ---
        for (MapData.ShapeWrapper obj : objects) { // <-- 遍历 ShapeWrapper
            if (obj == null) continue;
            // --- VVVV 核心修改: 使用 MapData 获取 AWT 边界 VVVV ---
            Rectangle2D.Double objBounds = MapData.getObstacleBounds(obj);
            // --- ^^^^ ---
            // --- VVVV 核心修改: 使用 AWT intersects VVVV ---
            if (objBounds != null && objBounds.intersects(queryBounds)) {
                returnObjects.add(obj); // <-- 添加 ShapeWrapper
            }
            // --- ^^^^ ---
        }

        // --- 3. 递归检查子节点 ---
        if (children != null) { // <-- 修正: 检查 children 是否为 null
            // --- VVVV 核心修改: 使用 AWT intersects VVVV ---
            if (children[0] != null && children[0].bounds.intersects(queryBounds)) children[0].queryBounds(returnObjects, queryBounds);
            if (children[1] != null && children[1].bounds.intersects(queryBounds)) children[1].queryBounds(returnObjects, queryBounds);
            if (children[2] != null && children[2].bounds.intersects(queryBounds)) children[2].queryBounds(returnObjects, queryBounds);
            if (children[3] != null && children[3].bounds.intersects(queryBounds)) children[3].queryBounds(returnObjects, queryBounds);
            // --- ^^^^ ---
        }

        return returnObjects;
    }
}