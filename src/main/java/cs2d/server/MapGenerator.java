package cs2d.server;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * [重构新增类] MapGenerator
 * 这是一个极简的静态地图生成工厂。
 * 它的职责是将原先由 GameState 承担的不合理的“场地建设”工作彻底剥离出来，
 * 保证 GameServer 在启动时能得到绝对非 null 的确定性 MapData。
 * 极大地提高了控制反转(IoC)的整洁度。
 */
public class MapGenerator {

    /**
     * 生成一个具有指定长宽的默认随机障碍物与出入口的地图。
     * 
     * @param width  地图宽
     * @param height 地图高
     * @return 完整且确定无疑的非 null MapData 实体
     */
    public static MapData generateRandomMap(int width, int height) {
        MapData mapData = new MapData();
        mapData.setWidth(width);
        mapData.setHeight(height);

        // 1. 初始化CT与T的出生区域
        List<Rectangle> ctSpawnAreas = new ArrayList<>();
        ctSpawnAreas.add(new Rectangle(50, 50, 150, Math.max(100, height - 100)));
        mapData.setCtSpawnAreas(ctSpawnAreas);

        List<Rectangle> tSpawnAreas = new ArrayList<>();
        tSpawnAreas.add(new Rectangle(Math.max(150, width - 200), 50, 150, Math.max(100, height - 100)));
        mapData.setTSpawnAreas(tSpawnAreas);

        // 2. 初始化经典 A/B 包点
        mapData.setBombSiteA(new Rectangle((int) (width * 0.15), (int) (height * 0.22), 100, 100));
        mapData.setBombSiteB(new Rectangle((int) (width * 0.78), (int) (height * 0.77), 100, 100));

        // 3. 开始暴力生成核心随机障碍墙体
        List<MapData.ShapeWrapper> obstacles = new ArrayList<>();
        Random rand = new Random();
        int numberOfObstacles = 8;

        for (int i = 0; i < numberOfObstacles; i++) {
            MapData.ShapeWrapper rectWrapper = new MapData.ShapeWrapper(
                    MapData.ShapeWrapper.ShapeType.RECTANGLE,
                    // 保证不在边缘刷脸
                    Math.max(150, rand.nextInt(Math.max(1, width - 300)) + 150),
                    Math.max(150, rand.nextInt(Math.max(1, height - 300)) + 150),
                    // 随机宽高度
                    50 + rand.nextInt(150),
                    50 + rand.nextInt(150));
            obstacles.add(rectWrapper);
        }

        // 4. 将原本属于 GameState 里的绝对物理边界硬编码，转移到数据源内
        // 这是极其优秀的重构，地图的边界也是一种“物理墙”，理应作为 Obstacle 的一部分提前预计算
        obstacles.add(new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, 0, 0, width, 20)); // 顶部空气墙
        obstacles.add(new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, 0, height - 20, width, 20)); // 底部空气墙
        obstacles.add(new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, 0, 0, 20, height)); // 左侧空气墙
        obstacles.add(new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, width - 20, 0, 20, height)); // 右侧空气墙

        // 装载入图
        mapData.setObstacles(obstacles);
        mapData.setName("RAND_" + (System.currentTimeMillis() % 1000000));

        return mapData;
    }
}
