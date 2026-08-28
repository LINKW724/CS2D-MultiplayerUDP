//package cs2d.playerAndAi.doublePlayer;
//
//import org.json.JSONArray;
//import org.json.JSONObject;
//import org.opencv.core.*;
//
//import javax.imageio.ImageIO;
//import java.awt.*;
//import java.awt.image.BufferedImage;
//import java.io.File;
//import java.io.FileWriter;
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.List;
//// import java.util.Queue; // 不再需要
//
//public class ImageToObstacles {
//
//    // --- 在这里调整参数 ---
//    /**
//     * 亮度阈值 (0-255)。高于此值的像素被认为是“白色”障碍物。
//     * 100 是一个很低的值，用于测试细线。
//     * 对于Overpass地图，200 是一个比较好的值。
//     */
//    private static final int BRIGHTNESS_THRESHOLD = 100; // <-- 已设为 100，便于测试
//
//    /**
//     * 输入的地图图片路径。
//     */
//    private static final String INPUT_FILE_PATH = "test2.png"; // <-- 记得改成您的测试图片
//
//    /**
//     * 输出的JSON文件路径。
//     */
//    private static final String OUTPUT_FILE_PATH = "map_obstacles.json";
//    // --- 调整结束 ---
//
//
//    // *************************************************************************
//    //
//    //  请根据您放置图片的位置，在下面两个 main 方法中 "二选一"
//    //  (请重命名您要用的那个为 "main")
//    //
//    // *************************************************************************
//
//    /**
//     * 方案A: 用于图片在 【项目根目录】
//     */
//    public static void main(String[] args) {
//        // public static void main_A(String[] args) { // <-- 如果用方案B，把这个改成 main_A
//        try {
//            System.out.println("正在加载图片: " + INPUT_FILE_PATH);
//            File inputFile = new File(INPUT_FILE_PATH);
//            if (!inputFile.exists()) {
//                System.err.println("错误：在【项目根目录】找不到图片文件！");
//                System.err.println("请确保: " + INPUT_FILE_PATH + " 存在于 " + new File(".").getAbsolutePath());
//                return;
//            }
//            BufferedImage image = ImageIO.read(inputFile);
//
//            runImageProcessing(image);
//
//        } catch (Exception e) {
//            System.err.println("发生了一个错误: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 方案B: 用于图片在 【src/main/resources】
//     */
//    // public static void main(String[] args) { // <-- 如果用方案A，把这个改成 main_B
//    // // public static void main_B(String[] args) {
//    //     try {
//    //         System.out.println("正在加载图片: " + INPUT_FILE_PATH);
//    //
//    //         ClassLoader classLoader = ImageToObstacles.class.getClassLoader();
//    //         InputStream inputStream = classLoader.getResourceAsStream(INPUT_FILE_PATH);
//    //
//    //         if (inputStream == null) {
//    //             System.err.println("错误：在 'src/main/resources' 文件夹中找不到图片文件！");
//    //             System.err.println("请确保: " + INPUT_FILE_PATH + " 存在于 'src/main/resources/'");
//    //             return;
//    //         }
//    //         BufferedImage image = ImageIO.read(inputStream);
//    //
//    //         runImageProcessing(image);
//    //
//    //     } catch (Exception e) {
//    //         System.err.println("发生了一个错误: " + e.getMessage());
//    //         e.printStackTrace();
//    //     }
//    // }
//
//
//    /**
//     * 统一的图像处理和JSON生成逻辑
//     */
//    private static void runImageProcessing(BufferedImage image) throws IOException {
//        if (image == null) {
//            System.err.println("错误：图片文件格式无法读取！");
//            return;
//        }
//
//        System.out.println("图片加载成功。尺寸: " + image.getWidth() + "x" + image.getHeight());
//
//        // *****************************************************************
//        // *** 根据图片尺寸和乘数来定义世界尺寸 ***
//        // *****************************************************************
//        final double SCALE_MULTIPLIER = 4.0; // <-- 在这里设置您要的倍数！
//
//        double worldWidth = image.getWidth() * SCALE_MULTIPLIER;
//        double worldHeight = image.getHeight() * SCALE_MULTIPLIER;
//
//        System.out.println("已设置为 " + SCALE_MULTIPLIER + " 倍缩放。");
//        System.out.println("输出JSON地图尺寸将为: " + (int)worldWidth + "x" + (int)worldHeight);
//        // *****************************************************************
//
//        System.out.println("开始扫描障碍物... (这可能需要一些时间)");
//
//        // 将新的世界尺寸传递给扫描方法
//        List<JSONObject> obstacles = findObstacles(image, worldWidth, worldHeight);
//
//        System.out.println("扫描完成。找到了 " + obstacles.size() + " 个粗略的障碍物。");
//
//        // 构建最终的JSON
//        JSONObject root = new JSONObject();
//        root.put("width", (int) worldWidth);
//        root.put("height", (int) worldHeight);
//        root.put("obstacles", new JSONArray(obstacles));
//        root.put("ctSpawnAreas", new JSONArray());
//        root.put("tSpawnAreas", new JSONArray());
//        root.put("bombSiteA", JSONObject.NULL);
//        root.put("bombSiteB", JSONObject.NULL);
//
//        // 写入文件
//        try (FileWriter file = new FileWriter(OUTPUT_FILE_PATH)) {
//            file.write(root.toString(4)); // 格式化输出，缩进为4
//            System.out.println("成功将JSON写入到: " + OUTPUT_FILE_PATH);
//            System.out.println("您现在可以用 MapEditor 加载这个 '" + OUTPUT_FILE_PATH + "' 文件了。");
//        }
//    }
//
//
//    /**
//     * **********************************************************************
//     * *** 核心逻辑已重写：采用您的“贪心矩形分解”算法 ***
//     * **********************************************************************
//     */
//    private static final int MIN_PIXEL_SIZE_FILTER = 3;
//    // --- 调整结束 ---
//
//
//    private static List<JSONObject> findObstacles(BufferedImage image, double worldWidth, double worldHeight) {
//        int imgWidth = image.getWidth();
//        int imgHeight = image.getHeight();
//
//        // 访问过的像素标记
//        boolean[][] visited = new boolean[imgWidth][imgHeight];
//        List<JSONObject> jsonObstacles = new ArrayList<>();
//
//        // 坐标系转换的比例
//        double scaleX = worldWidth / (double) imgWidth;
//        double scaleY = worldHeight / (double) imgHeight;
//
//        // 遍历所有像素
//        for (int y = 0; y < imgHeight; y++) {
//            for (int x = 0; x < imgWidth; x++) {
//
//                // 如果这个像素不是障碍物，或者我们已经处理过它了，就跳过
//                if (visited[x][y] || !isPixelObstacle(image, x, y)) {
//                    continue;
//                }
//
//                // --- 找到了一个新矩形的左上角 (x, y) ---
//
//                // 1. 向右“连接”，找到最大宽度 (rectWidth)
//                int rectWidth = 1;
//                while (x + rectWidth < imgWidth &&
//                        !visited[x + rectWidth][y] &&
//                        isPixelObstacle(image, x + rectWidth, y)) {
//                    rectWidth++;
//                }
//
//                // 2. 向下“连接”，找到最大高度 (rectHeight)
//                int rectHeight = 1;
//                boolean canExpandDown = true;
//                while (y + rectHeight < imgHeight && canExpandDown) {
//
//                    // 检查下一整行 (从 x 到 x + rectWidth - 1) 是否都有效
//                    for (int i = 0; i < rectWidth; i++) {
//                        if (visited[x + i][y + rectHeight] ||
//                                !isPixelObstacle(image, x + i, y + rectHeight)) {
//
//                            canExpandDown = false; // 这一行不完美，停止向下
//                            break;
//                        }
//                    }
//
//                    if (canExpandDown) {
//                        rectHeight++;
//                    }
//                }
//
//                // 3. 标记这个矩形内的所有像素为“已访问”，确保没有重叠
//                for (int j = 0; j < rectHeight; j++) {
//                    for (int i = 0; i < rectWidth; i++) {
//                        visited[x + i][y + j] = true;
//                    }
//                }
//
//                // 4. ********** 新增：最小尺寸过滤器 **********
//                //    (如果矩形太“瘦”或太“矮”，就跳过它)
//                if (rectWidth < MIN_PIXEL_SIZE_FILTER || rectHeight < MIN_PIXEL_SIZE_FILTER) {
//                    continue;
//                }
//                // *************************************************
//
//                // 5. 将这个矩形（已转换为世界坐标）添加到列表中
//                JSONObject rect = new JSONObject();
//                rect.put("type", "RECTANGLE");
//                rect.put("x", x * scaleX);
//                rect.put("y", y * scaleY);
//                rect.put("width", rectWidth * scaleX);     // 像素宽度 * 缩放
//                rect.put("height", rectHeight * scaleY); // 像素高度 * 缩放
//                jsonObstacles.add(rect);
//            }
//        }
//
//        System.out.println("（调试信息：在过滤后，最终保留了 " + jsonObstacles.size() + " 个矩形）");
//        return jsonObstacles;
//    }
//
//
//    /**
//     * 检查单个像素是否为“障碍物”（即是否足够“白”）。
//     */
//    private static boolean isPixelObstacle(BufferedImage image, int x, int y) {
//        int pixel = image.getRGB(x, y);
//
//        // 检查完全透明的像素 (alpha = 0)，它们不应被识别
//        int alpha = (pixel >> 24) & 0xff;
//        if (alpha < 128) { // 忽略半透明或全透明的
//            return false;
//        }
//
//        Color color = new Color(pixel);
//        // 计算亮度 (R, G, B 的平均值)
//        int brightness = (color.getRed() + color.getGreen() + color.getBlue()) / 3;
//
//        return brightness > BRIGHTNESS_THRESHOLD;
//    }
//}
//
//
//
//
//
////
////package com.itlinkwheat.cs2; // 确保这个包名和您的一致
////
////        import nu.pattern.OpenCV; // <-- 新增
////        import org.json.JSONArray;
////        import org.json.JSONObject;
////        import org.opencv.core.*; // <-- 新增
////        import org.opencv.imgcodecs.Imgcodecs; // <-- 新增
////        import org.opencv.imgproc.Imgproc; // <-- 新增
////
////        import javax.imageio.ImageIO;
////        import java.awt.image.BufferedImage;
////        import java.io.ByteArrayInputStream;
////        import java.io.File;
////        import java.io.FileWriter;
////        import java.io.IOException;
////        import java.io.InputStream;
////        import java.util.ArrayList;
////        import java.util.List;
////
////public class ImageToObstacles {
////
////    // --- 在这里调整参数 ---
////    /**
////     * 亮度阈值 (0-255)。高于此值的像素被认为是“白色”障碍物。
////     */
////    private static final int BRIGHTNESS_THRESHOLD = 150; // 阈值可以调低一点
////
////    /**
////     * 多边形近似的“精度”。
////     * - 值越小（例如 1.0）：多边形越精确，顶点越多，越贴合像素。
////     * - 值越大（例如 3.0 或 4.0）：多边形越“粗糙”，顶点越少，性能越高。
////     * 2.5 是一个很好的起点。
////     */
////    private static final double POLYGON_APPROXIMATION_ACCURACY = 2.5;
////
////    /**
////     * 输入的地图图片路径。
////     */
////    private static final String INPUT_FILE_PATH = "test2.png"; // <-- 您的测试图片
////
////    /**
////     * 输出的JSON文件路径。
////     */
////    private static final String OUTPUT_FILE_PATH = "map_obstacles.json";
////    // --- 调整结束 ---
////
////
////    /**
////     * 静态代码块，用于加载 OpenCV 本地库
////     */
////    static {
////        try {
////            // 这会自动下载并加载 OpenCV
////            OpenCV.loadLocally();
////            System.out.println("OpenCV 库加载成功！");
////        } catch (Exception e) {
////            System.err.println("!!!!!!!!!! OpenCV 库加载失败 !!!!!!!!!!");
////            System.err.println("请检查您的网络连接或 Maven 依赖。");
////            e.printStackTrace();
////        }
////    }
////
////    /**
////     * 方案A: 用于图片在 【项目根目录】
////     */
////    public static void main(String[] args) {
////        try {
////            System.out.println("正在加载图片: " + INPUT_FILE_PATH);
////            File inputFile = new File(INPUT_FILE_PATH);
////            if (!inputFile.exists()) {
////                System.err.println("错误：在【项目根目录】找不到图片文件！");
////                return;
////            }
////            // OpenCV 用它自己的方式读取图片
////            Mat image = Imgcodecs.imread(inputFile.getAbsolutePath());
////
////            runImageProcessing(image);
////
////        } catch (Exception e) {
////            System.err.println("发生了一个错误: " + e.getMessage());
////            e.printStackTrace();
////        }
////    }
////
////    // (方案B的代码已为OpenCV更新，如果您需要，可以取消注释并替换方案A)
////    /**
////     * 方案B: 用于图片在 【src/main/resources】
////     */
////    // public static void main(String[] args) {
////    //     try {
////    //         System.out.println("正在加载图片: " + INPUT_FILE_PATH);
////    //
////    //         ClassLoader classLoader = ImageToObstacles.class.getClassLoader();
////    //         InputStream inputStream = classLoader.getResourceAsStream(INPUT_FILE_PATH);
////    //
////    //         if (inputStream == null) {
////    //             System.err.println("错误：在 'src/main/resources' 文件夹中找不到图片文件！");
////    //             return;
////    //         }
////    //
////    //         // OpenCV 需要一个 MatOfByte
////    //         byte[] imageBytes = inputStream.readAllBytes();
////    //         Mat image = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_UNCHANGED);
////    //
////    //         runImageProcessing(image);
////    //
////    //     } catch (Exception e) {
////    //         System.err.println("发生了一个错误: " + e.getMessage());
////    //         e.printStackTrace();
////    //     }
////    // }
////
////
////    /**
////     * 统一的图像处理和JSON生成逻辑 (已为OpenCV重写)
////     */
////    private static void runImageProcessing(Mat image) throws IOException {
////        if (image.empty()) {
////            System.err.println("错误：OpenCV 无法读取图片文件！");
////            return;
////        }
////
////        System.out.println("图片加载成功。尺寸: " + image.width() + "x" + image.height());
////
////        // *****************************************************************
////        // *** 根据图片尺寸和乘数来定义世界尺寸 ***
////        // *****************************************************************
////        final double SCALE_MULTIPLIER = 4.0; // <-- 在这里设置您要的倍数！
////
////        double worldWidth = image.width() * SCALE_MULTIPLIER;
////        double worldHeight = image.height() * SCALE_MULTIPLIER;
////
////        System.out.println("已设置为 " + SCALE_MULTIPLIER + " 倍缩放。");
////        System.out.println("输出JSON地图尺寸将为: " + (int)worldWidth + "x" + (int)worldHeight);
////
////        // *****************************************************************
////        // *** VVVV 错误修正的地方 VVVV ***
////        // *** 在这里计算 scaleX 和 scaleY ***
////        double scaleX = worldWidth / (double) image.width();
////        double scaleY = worldHeight / (double) image.height();
////        // *****************************************************************
////
////        System.out.println("开始使用OpenCV进行V3算法扫描... (轮廓描摹)");
////
////        // 将新的世界尺寸传递给扫描方法
////        List<JSONObject> obstacles = findObstaclesV3(image, scaleX, scaleY); // <-- 现在可以正确传递了
////
////        System.out.println("扫描完成。找到了 " + obstacles.size() + " 个多边形障碍物。");
////
////        // 构建最终的JSON
////        JSONObject root = new JSONObject();
////        root.put("width", (int) worldWidth);
////        root.put("height", (int) worldHeight);
////        root.put("obstacles", new JSONArray(obstacles));
////        root.put("ctSpawnAreas", new JSONArray());
////        root.put("tSpawnAreas", new JSONArray());
////        root.put("bombSiteA", JSONObject.NULL);
////        root.put("bombSiteB", JSONObject.NULL);
////
////        // 写入文件
////        try (FileWriter file = new FileWriter(OUTPUT_FILE_PATH)) {
////            file.write(root.toString(4)); // 格式化输出，缩进为4
////            System.out.println("成功将JSON写入到: " + OUTPUT_FILE_PATH);
////        }
////    }
////
////
////    /**
////     * **********************************************************************
////     * *** V3 核心逻辑：使用 OpenCV 进行轮廓描摹和多边形近似 ***
////     * **********************************************************************
////     */
////    private static List<JSONObject> findObstaclesV3(Mat image, double scaleX, double scaleY) {
////        List<JSONObject> jsonObstacles = new ArrayList<>();
////
////        // 1. 预处理：将图像转换为灰度图
////        Mat grayImage = new Mat();
////        // 检查图像是否已有 Alpha 通道
////        if (image.channels() == 4) {
////            // 如果有 Alpha 通道，先用 Alpha 通道作为蒙版
////            // (这能正确处理PNG的透明度)
////            List<Mat> channels = new ArrayList<>();
////            Core.split(image, channels);
////            // 使用 Alpha 通道作为二值化图像
////            Imgproc.threshold(channels.get(3), grayImage, 128, 255, Imgproc.THRESH_BINARY);
////        } else {
////            // 否则，使用亮度
////            Imgproc.cvtColor(image, grayImage, Imgproc.COLOR_BGR2GRAY);
////            Imgproc.threshold(grayImage, grayImage, BRIGHTNESS_THRESHOLD, 255, Imgproc.THRESH_BINARY);
////        }
////
////        // 2. 二值化 (已在上面合并处理)
////        Mat binaryImage = grayImage; // 重命名一下
////
////        // 3. 查找轮廓
////        List<MatOfPoint> contours = new ArrayList<>();
////        Mat hierarchy = new Mat();
////
////        Imgproc.findContours(binaryImage, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE);
////
////        System.out.println("（调试信息：OpenCV 找到了 " + contours.size() + " 个原始轮廓）");
////
////        // 4. 遍历所有轮廓，进行“多边形简化”
////        for (MatOfPoint contour : contours) {
////
////            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
////
////            // 5. *** 这就是您要的“近似”算法！ ***
////            MatOfPoint2f approxPoly = new MatOfPoint2f();
////            Imgproc.approxPolyDP(contour2f, approxPoly, POLYGON_APPROXIMATION_ACCURACY, true);
////
////            // 过滤掉太小的噪点（如果简化后的顶点少于3个，它就不是一个多边形）
////            if (approxPoly.rows() < 3) {
////                continue;
////            }
////
////            // 6. 将顶点转换为JSON
////            JSONObject polygon = convertContourToPolygon(approxPoly, scaleX, scaleY);
////            jsonObstacles.add(polygon);
////        }
////
////        return jsonObstacles;
////    }
////
////    /**
////     * 辅助方法：将OpenCV的轮廓点转换为JSON对象
////     */
////    private static JSONObject convertContourToPolygon(MatOfPoint2f approxPoly, double scaleX, double scaleY) {
////        JSONObject poly = new JSONObject();
////        poly.put("type", "POLYGON"); // <-- 类型是多边形
////
////        JSONArray xPoints = new JSONArray();
////        JSONArray yPoints = new JSONArray();
////
////        // 遍历所有“简化”后的顶点
////        for (Point p : approxPoly.toList()) {
////            xPoints.put(p.x * scaleX);
////            yPoints.put(p.y * scaleY);
////        }
////
////        // 您的 MapData.ShapeWrapper 需要 xPoints 和 yPoints 数组
////        poly.put("xPoints", xPoints);
////        poly.put("yPoints", yPoints);
////
////        // (您的 MapData.ShapeWrapper 还需要 x 和 y，我们就用第一个点作为基准点)
////        if (xPoints.length() > 0) {
////            poly.put("x", xPoints.getDouble(0));
////            poly.put("y", yPoints.getDouble(0));
////        }
////
////        return poly;
////    }
////
////    // (isPixelObstacle 方法不再需要，OpenCV接管了所有图像处理)
