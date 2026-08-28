package cs2d.AIControl.BW;

import cs2d.server.GameServer;
import cs2d.server.GameState;
import cs2d.server.SoundEvent;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// [注意] 确保这个 import 是正确的
// import static com.sun.java.accessibility.util.AWTEventMonitor.addWindowListener;
// [备用] 如果上面的 import 失败，请使用这个
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;


// 将此内部类放在 ServerControlPanel 内部
public class ServerStatusWindow extends JDialog {
    // --- 定义颜色常量 ---
    private static final Color BACKGROUND_DARK = Color.decode("#1a202c");
    private static final Color CARD_BACKGROUND = Color.decode("#2d3748");
    private static final Color BUTTON_COLOR = Color.decode("#2d3748");
    private static final Color BUTTON_HOVER_COLOR = Color.decode("#4a5568");
    private static final Color BORDER_COLOR = Color.decode("#4a5568");
    private static final Color PRIMARY_BLUE = Color.decode("#63B3ED");
    private static final Color TEXT_LIGHT = PRIMARY_BLUE; // 或者 Color.WHITE
    // --- 颜色常量定义结束 ---

    // ---  定义字体常量 (如果 styleTable 等方法也需要) ---
    private static final Font labelFont = new Font("SansSerif", Font.BOLD, 12); // 可以调整字体
    // --- 字体常量定义结束 ---
    private final GameServer gameServer;

    // ---  重命名 JTextArea ---
    private final JTextArea performanceLogArea;
    private final Timer refreshTimer;

    // 状态窗口的构造函数
    public ServerStatusWindow(JFrame parent, GameServer server) {
        // [修改] 更改窗口标题
        super(parent, "服务器性能日志", false);
        this.gameServer = server;

        // --- 1. 创建文本区域 ---
        // [修改] 重命名变量
        performanceLogArea = new JTextArea();
        performanceLogArea.setEditable(false); // 不可编辑
        performanceLogArea.setLineWrap(true); // 自动换行
        performanceLogArea.setWrapStyleWord(true); // 按单词换行
        // 应用样式
        performanceLogArea.setBackground(CARD_BACKGROUND);
        performanceLogArea.setForeground(TEXT_LIGHT);
        performanceLogArea.setFont(new Font("Monospaced", Font.PLAIN, 14)); // [修改] 使用等宽字体，加大字号
        performanceLogArea.setMargin(new Insets(5, 5, 5, 5)); // 设置内边距

        // --- 2. 设置布局 ---
        JScrollPane scrollPane = new JScrollPane(performanceLogArea); // 将文本区域放入滚动面板
        scrollPane.getViewport().setBackground(CARD_BACKGROUND); // 滚动面板背景
        getContentPane().setLayout(new BorderLayout()); // 设置内容面板为边界布局
        getContentPane().add(scrollPane, BorderLayout.CENTER); // 将滚动面板添加到中央

        // --- 3. 窗口属性 ---
        setSize(500, 400); // 调整大小
        setLocationRelativeTo(parent); // 相对于主窗口居中

        // --- 4. 设置刷新定时器 ---
        // 定时器每 500ms 更新一次
        refreshTimer = new Timer(500, e -> updateStatusData());
        refreshTimer.start(); // 启动定时器

        // 添加监听器，在窗口关闭时停止定时器
        // [修改] 使用标准的 WindowAdapter 替换 'com.sun' 包
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                // [修改] 更新日志
                System.out.println("性能监视窗口已关闭，正在停止定时器。");
                refreshTimer.stop();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (refreshTimer.isRunning()) {
                    // [修改] 更新日志
                    System.out.println("性能监视窗口已销毁，正在停止定时器。");
                    refreshTimer.stop();
                }
            }
        });
    }

    /**
     * [重大修改]
     * 更新文本区域显示的数据。
     * 此版本现在会同时显示 GameState 逻辑耗时和 NetworkBroadcaster 的详细网络耗时。
     */
    private void updateStatusData() {
        // 检查服务器是否仍在运行且可访问
        if (gameServer == null || gameServer.getGameState() == null) {
            // 如果服务器停止，停止定时器并清空文本区域
            SwingUtilities.invokeLater(() -> performanceLogArea.setText("服务器未运行或状态无效。"));
            if (refreshTimer.isRunning()) {
                System.out.println("GameServer 为 null，正在停止性能监视定时器。");
                refreshTimer.stop();
            }
            return;
        }

        GameState currentState = gameServer.getGameState(); // 获取状态

        // --- [新] 获取所有性能数据 ---
        // 游戏逻辑耗时 (来自 GameState)
        double aiPrepTime = currentState.getPerfTimeAiInputPrep();
        double physicsTime = currentState.getPerfTimePhysics();
        double logicTime = currentState.getPerfTimeGameLogic();
        double logicTotalTime = currentState.getPerfTimeTotal();

        // 网络详细耗时 (来自 GameServer -> NetworkBroadcaster)
        double networkJsonTime = gameServer.getPerfTimeJsonSerialization();
        double networkChunkTime = gameServer.getPerfTimeChunkPreparation();
        double networkSendTime = gameServer.getPerfTimeParallelSend(); // 'Send' 现在是纯 I/O
        double networkTotalTime = gameServer.getPerfTimeNetworkSend(); // 这是网络线程的总耗时

        // --- [新] 真实的游戏主线程耗时 (只包含逻辑) ---
        // 注意：actualTotalTime 现在只代表逻辑线程
        double actualTotalTime = logicTotalTime;
        double tpsCap = GameServer.TPS;
        double currentTps = (actualTotalTime > 0) ? (1000.0 / actualTotalTime) : tpsCap;
        currentTps = Math.min(currentTps, tpsCap);


        // --- 格式化性能列表为字符串 ---
        final StringBuilder sb = new StringBuilder(); // 使用 final StringBuilder

        // 注意：数据是每 2 秒 (120 逻辑帧) 更新一次的
        sb.append(String.format("--- 实时性能日志 (数据每 ~2s 更新) ---\n"));
        sb.append("------------------------------------------\n");
        sb.append("  --- 游戏逻辑 (GameState.update @ 120Hz) ---\n");
        sb.append(String.format("    [1] AI/输入/准备:      \t\t%.3f ms\n", aiPrepTime));
        sb.append(String.format("    [2] 物理 (移动/碰撞):   \t\t%.3f ms\n", physicsTime));
        sb.append(String.format("    [3] 游戏逻辑:          \t\t%.3f ms\n", logicTime));
        sb.append(String.format("    >>> 逻辑总耗时:         \t\t%.3f ms\n\n", logicTotalTime));

        sb.append("  --- 网络发送 (NetworkBroadcaster @ 60Hz) ---\n");
        sb.append(String.format("    [4.1] 主JSON序列化:     \t\t%.3f ms\n", networkJsonTime));
        sb.append(String.format("    [4.2] 分片打包(Base64): \t\t%.3f ms\n", networkChunkTime));
        sb.append(String.format("    [4.3] 并行I/O发送:      \t\t%.3f ms\n", networkSendTime));
        sb.append(String.format("    >>> 网络总耗时:          \t\t%.3f ms\n\n", networkTotalTime));

        sb.append("------------------------------------------\n");
        // [修改] "游戏帧总耗时" 已更名为 "逻辑总耗时"
        sb.append(String.format("  服务器TICK (TPS): \t%.1f / %.1f\n", currentTps, tpsCap));
        sb.append("------------------------------------------");


        // --- 在 EDT 上更新 JTextArea ---
        // 将最终的字符串存储在局部变量中，以便 lambda 表达式可以访问
        final String performanceText = sb.toString();
        // 6. 提交任务到 EDT (极快: 纳秒)
        SwingUtilities.invokeLater(() -> {
            // 7. 更新GUI文本 (很快: 几毫秒)
            performanceLogArea.setText(performanceText);
            performanceLogArea.setCaretPosition(0);
        });
    }


} // ServerStatusWindow 内部类结束