// 定义这个类所在的包路径，用于组织和管理项目结构。
package cs2d.server;

// 导入Google的Gson库，用于在Java对象和JSON字符串之间进行转换。
import com.google.gson.Gson;
// 导入Gson库中的GsonBuilder类，用于创建具有特定配置（如格式化输出）的Gson实例。
import com.google.gson.GsonBuilder;
// 导入用于创建简单HTTP服务器的类，用于从浏览器访问游戏。
import com.sun.net.httpserver.HttpServer;
import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.B.PerceptionInfo;
import cs2d.AIControl.B.PerceptionModule;
import cs2d.AIControl.BW.ServerStatusWindow;
import cs2d.playerAndAi.Player;
// 导入javax.swing包下的所有类，这是Java的图形用户界面（GUI）工具包。
import javax.swing.*;
// 导入Swing中的Border接口，用于定义组件的边框样式。
import javax.swing.border.Border;
// 导入文件选择器中的文件名扩展过滤器，用于限制用户只能选择特定类型的文件。
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;
// 导入java.awt包下的所有类，这是Java的基础图形用户界面和图形库。
import java.awt.*;
// 导入File类，用于表示文件系统中的文件或目录。
import java.awt.geom.Point2D;
import java.io.File;
// 导入FileReader类，用于从文件中读取字符数据。
import java.io.FileReader;
// 导入IOException类，用于处理输入/输出操作中发生的异常。
import java.io.IOException;
// 导入OutputStream类，用于写入字节输出流。
import java.io.OutputStream;
// 导入DatagramSocket类，用于UDP通信。
import java.net.*;
// 导入InetAddress类，代表一个IP地址。
// 导入InetSocketAddress类，代表一个IP套接字地址（IP地址 + 端口号）。
// 导入URI类，代表一个统一资源标识符。
// 导入UnknownHostException类，表示无法确定主机的IP地址时抛出的异常。
// 导入Files类，提供了操作文件和目录的静态方法。
import java.nio.file.Files;
// 导入Path接口，代表文件系统中的路径。
import java.nio.file.Path;
// 导入Paths类，提供了将字符串路径转换为Path对象的静态方法。
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个简单的 Swing GUI，作为服务器的启动器和控制中心。
 * 它允许用户在启动服务器前配置游戏模式、AI难度和数量等参数。
 */
public class ServerControlPanel {

    private JFrame frame;
    private JTextArea logArea;
    private JComboBox<String> modeComboBox;
    private JComboBox<AIDifficulty> difficultyComboBox;
    private JSpinner aiCountSpinner;
    private JSpinner waveSpinner;
    private GameServer gameServer;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private JPanel configPanel;
    // private JPanel actionPanel; // 旧的actionPanel声明
    private JPanel mainActionPanel; // 将mainActionPanel声明为成员变量
    private HttpServer webServer;

    private JButton startButton;
    private JButton stopButton;
    private JButton newMapButton;
    private JButton editMapButton;
    // 将 "添加人机" 按钮重命名为 "管理AI" 按钮
    private JButton freezeAiButton;
    private JButton manageAiButton;
    // 声明命令输入框和执行按钮
    private JTextField commandField;
    private JButton executeCommandButton;

    private final Color BACKGROUND_DARK = Color.decode("#1a202c");
    private final Color CARD_BACKGROUND = Color.decode("#2d3748");
    private final Color BUTTON_COLOR = Color.decode("#2d3748");
    private final Color BUTTON_HOVER_COLOR = Color.decode("#4a5568");
    private final Color BORDER_COLOR = Color.decode("#4a5568");
    private final Color PRIMARY_BLUE = Color.decode("#63B3ED");
    private final Color TEXT_LIGHT = PRIMARY_BLUE;
    private final Font labelFont = new Font("SansSerif", Font.BOLD, 14);
    private final Font buttonFont = new Font("Orbitron", Font.BOLD, 14);

    /**
     * 构造函数，调用UI创建方法。
     */
    public ServerControlPanel() {
        // 设置一个现代的外观和感觉(Look and Feel)给UI。
        try { // 尝试。
              // 获取并设置当前操作系统的默认外观，使窗口看起来更原生。
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) { // 如果设置失败。
            e.printStackTrace(); // 打印错误堆栈信息。
        }
        createUI(); // 调用创建UI的方法。
    }

    /**
     * 创建并显示服务器控制面板的UI组件。
     */
    private void createUI() {
        frame = new JFrame("CS2D 服务器控制面板 (UDP)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                stopServer();
                System.exit(0);
            }
        });
        frame.setSize(1024, 768); // 初始大小
        frame.setLayout(new BorderLayout(10, 10));
        frame.getContentPane().setBackground(BACKGROUND_DARK);

        configPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        configPanel.setBackground(CARD_BACKGROUND);
        configPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        addStyledLabel(configPanel, "模式:");
        modeComboBox = new JComboBox<>(new String[] { "团队死亡竞赛", "僵尸模式", "爆破模式", "死斗模式" /* , "训练爆破模式 (RL)" */ });
        styleComponent(modeComboBox);
        configPanel.add(modeComboBox);

        addStyledLabel(configPanel, "AI难度:");
        difficultyComboBox = new JComboBox<>(AIDifficulty.values());
        difficultyComboBox.setSelectedItem(AIDifficulty.NORMAL);
        styleComponent(difficultyComboBox);
        configPanel.add(difficultyComboBox);

        addStyledLabel(configPanel, "AI 数量:");
        aiCountSpinner = new JSpinner(new SpinnerNumberModel(9, 0, 2000, 1));
        styleComponent(aiCountSpinner);
        configPanel.add(aiCountSpinner);

        addStyledLabel(configPanel, "起始波数:");
        waveSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100000, 1));
        styleComponent(waveSpinner);
        waveSpinner.setEnabled(false);
        configPanel.add(waveSpinner);

        modeComboBox.addActionListener(e -> {
            int selectedIndex = modeComboBox.getSelectedIndex();
            // 索引 1 (僵尸模式) 启用波数
            waveSpinner.setEnabled(selectedIndex == 1);

            // 索引 2 (爆破) 或 3 (死斗) 设置默认AI
            if (selectedIndex == 2 || selectedIndex == 3) {
                aiCountSpinner.setValue(10);
            }
        });

        frame.add(configPanel, BorderLayout.NORTH);

        // --- 底部动作面板 (重构为两行) ---
        // 1. 创建一个总的底部容器，它将容纳两行面板
        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS)); // 使用垂直盒子布局
        southPanel.setBackground(CARD_BACKGROUND);

        // 2. 创建第一行：主要功能按钮
        mainActionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10)); // 去掉前面的 "JPanel"
        mainActionPanel.setBackground(CARD_BACKGROUND);

        startButton = new JButton("启动游戏");
        styleButton(startButton);
        startButton.addActionListener(e -> chooseMapAndStartServer());
        mainActionPanel.add(startButton);

        stopButton = new JButton("关闭游戏");
        styleButton(stopButton);
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopServer());
        mainActionPanel.add(stopButton);

        newMapButton = new JButton("新建地图");
        styleButton(newMapButton);
        newMapButton.addActionListener(e -> new MapEditor(null));
        mainActionPanel.add(newMapButton);

        editMapButton = new JButton("编辑地图");
        styleButton(editMapButton);
        editMapButton.addActionListener(e -> openMapEditor());
        mainActionPanel.add(editMapButton);

        manageAiButton = new JButton("管理AI");
        styleButton(manageAiButton);
        manageAiButton.setEnabled(false);
        manageAiButton.addActionListener(e -> {
            if (gameServer == null) {
                JOptionPane.showMessageDialog(frame, "请先启动游戏服务器！", "提示", JOptionPane.INFORMATION_MESSAGE);
                return; // 服务器未运行，直接返回
            }

            // --- 修改选项数组，增加 "显示AI状态" ---
            Object[] options = { "添加 CT", "添加 T", "移除一个 CT方AI", "移除一个 T方AI", "杀死所有 AI", "显示AI状态", "取消" };
            // --- 选项数组修改结束 ---

            int choice = JOptionPane.showOptionDialog(frame,
                    "请选择要执行的 AI 操作：",
                    "管理 AI",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null, options, options[6]); // 注意：取消选项现在是索引 6

            // --- 修改 switch 语句，处理新选项 ---
            switch (choice) {
                case 0: // 添加 CT
                    gameServer.addBotToGame(cs2d.playerAndAi.Player.Team.CT);
                    log("手动添加一名 [反恐精英] 人机。");
                    break;
                case 1: // 添加 T
                    gameServer.addBotToGame(cs2d.playerAndAi.Player.Team.T);
                    log("手动添加一名 [恐怖分子] 人机。");
                    break;
                case 2: // 移除 CT AI
                    if (gameServer != null)
                        gameServer.removeBotByTeam(cs2d.playerAndAi.Player.Team.CT);
                    break;
                case 3: // 移除 T AI
                    if (gameServer != null)
                        gameServer.removeBotByTeam(cs2d.playerAndAi.Player.Team.T);
                    break;
                case 4: // 杀死所有 AI
                    if (gameServer != null)
                        gameServer.killAllBots();
                    break;
                case 5: // 显示AI状态 (新添加的选项)
                    // 创建并显示 AI 管理对话框
                    new AiManagerDialog(frame, gameServer);
                    break;
                case 6: // 取消 或 关闭对话框
                default:
                    // 什么也不做
                    break;
            }
            // --- switch 语句修改结束 ---
        });
        mainActionPanel.add(manageAiButton);

        serverStatusButton = new JButton("服务器状态"); // 创建按钮
        styleButton(serverStatusButton); // 应用你的样式
        serverStatusButton.setEnabled(false); // 初始时禁用，因为服务器未运行
        // 添加点击事件监听器
        serverStatusButton.addActionListener(e -> showServerStatusWindow());
        mainActionPanel.add(serverStatusButton); // 将按钮添加到第一行面板

        freezeAiButton = new JButton("冻结AI"); // 初始化成员变量
        styleButton(freezeAiButton);
        freezeAiButton.setEnabled(false); // 初始禁用

        freezeAiButton.addActionListener(e -> {
            if (gameServer != null) {
                gameServer.toggleAiFreeze(); // 调用服务器的方法
                boolean isFrozen = gameServer.isAiFrozen(); // 获取新状态
                freezeAiButton.setText(isFrozen ? "恢复AI" : "冻结AI"); // 更新按钮文本
                log(isFrozen ? "所有AI已被冻结。" : "所有AI已恢复行动。");
            }
        });
        mainActionPanel.add(freezeAiButton); // 添加“冻结AI”按钮
        // =================================================================
        // “管理玩家”按钮
        // =================================================================
        JButton managePlayersButton = new JButton("管理玩家");
        styleButton(managePlayersButton);
        managePlayersButton.setEnabled(false); // 初始为禁用状态
        // 它的启用/禁用将由 setServerRunningUIState 方法统一管理
        mainActionPanel.add(managePlayersButton);

        // 为按钮添加点击事件，点击后打开我们的新窗口
        managePlayersButton.addActionListener(e -> {
            if (gameServer != null) {
                // 创建并显示玩家管理对话框
                new PlayerManagerDialog(frame, gameServer);
            }
        });
        // 3. 创建第二行：命令面板
        JPanel commandPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5)); // 垂直间距可以小一点
        commandPanel.setBackground(CARD_BACKGROUND);

        addStyledLabel(commandPanel, "命令:");

        commandField = new JTextField(35); // 可以把输入框设置得更宽一些
        styleComponent(commandField);
        commandField.setEnabled(false);
        commandPanel.add(commandField);

        executeCommandButton = new JButton("执行");
        styleButton(executeCommandButton);
        executeCommandButton.setEnabled(false);

        executeCommandButton.addActionListener(e -> {
            // 检查 gameServer 是否有效，以及命令输入框是否为空
            if (gameServer != null && !commandField.getText().isBlank()) {
                String command = commandField.getText().trim(); // 获取命令并去除首尾空格
                // log("尝试执行命令: " + command); // 可以保留或移除调试日志

                // --- 命令解析 (保持不变) ---
                java.util.List<String> parts = new java.util.ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(command);
                while (m.find()) {
                    parts.add(m.group(1).replace("\"", "")); // 添加匹配项，并移除引号
                }

                // --- 简洁的命令分发 ---
                if (!parts.isEmpty()) { // 确保至少有一个命令部分
                    String commandName = parts.get(0).toLowerCase(); // 获取命令名称

                    // 检查是否是 moveai 命令
                    if (commandName.equals("moveai")) {
                        // 检查参数数量是否正确
                        if (parts.size() == 4) {
                            handleMoveAiCommand(parts); // 调用新方法处理 moveai
                        } else {
                            // moveai 命令参数数量错误
                            log("命令格式错误：参数数量不正确。格式: moveai <ai_name_or_id>|\"<ai name>\" <x> <y>");
                        }
                    } else {
                        // 如果不是 "moveai"，则执行通用的命令处理
                        log("执行通用命令: " + command);
                        gameServer.executeCommand(command); // 将命令传递给 GameServer 处理
                    }
                }
                // --- 命令分发结束 ---

                commandField.setText(""); // 清空命令输入框
            }
        });
        commandPanel.add(executeCommandButton); // 将按钮添加到面板

        // 4. 将这两行面板都添加到总的底部容器中
        southPanel.add(mainActionPanel);
        southPanel.add(commandPanel);

        // 5. 最后，将总的底部容器添加到窗口的南边（底部）
        frame.add(southPanel, BorderLayout.SOUTH);
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBackground(CARD_BACKGROUND);
        logArea.setForeground(TEXT_LIGHT);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        logArea.setMargin(new Insets(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(CARD_BACKGROUND, 10));
        frame.add(scrollPane, BorderLayout.CENTER);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * 停止正在运行的游戏和Web服务器，并重置UI。
     */

    /**
     * 打开文件选择器让用户选择一个地图文件来编辑。
     */
    private void openMapEditor() {
        JFileChooser fileChooser = new JFileChooser("maps"); // 创建文件选择器，默认打开"maps"目录。
        fileChooser.setFileFilter(new FileNameExtensionFilter("JSON Maps", "json")); // 设置文件过滤器，只显示.json文件。
        int result = fileChooser.showOpenDialog(frame); // 显示打开文件对话框，并等待用户操作。
        if (result == JFileChooser.APPROVE_OPTION) { // 如果用户点击了“打开”按钮。
            new MapEditor(fileChooser.getSelectedFile()); // 创建一个新的地图编辑器实例，并传入选中的文件。
        }
    }

    /**
     * 弹出一个新窗口，列出所有地图文件供用户选择，然后启动服务器。
     */
    private void chooseMapAndStartServer() {
        JDialog mapDialog = new JDialog(frame, "选择地图 (Select Map)", true); // 创建一个模态对话框。
        mapDialog.setSize(400, 300); // 设置对话框大小。
        mapDialog.setLayout(new BorderLayout()); // 设置布局。
        mapDialog.setLocationRelativeTo(frame); // 让对话框在主窗口中央显示。
        mapDialog.getContentPane().setBackground(BACKGROUND_DARK); // 设置背景颜色。

        DefaultListModel<String> listModel = new DefaultListModel<>(); // 创建一个列表模型来存放地图名称。
        listModel.addElement("[随机生成地图 (Randomly Generated)]"); // 添加一个默认选项。

        File mapDir = new File("maps"); // 创建一个指向"maps"目录的File对象。

        if (mapDir.exists() && mapDir.isDirectory()) { // 如果目录存在且确实是一个目录。
            // 列出目录下所有以.json结尾的文件。
            File[] mapFiles = mapDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
            if (mapFiles != null) { // 如果找到了文件。
                for (File mapFile : mapFiles) { // 遍历所有地图文件。
                    listModel.addElement(mapFile.getName()); // 将文件名添加到列表模型中。
                }
            }
        } else { // 如果目录不存在。
            log("警告: 'maps' 文件夹未找到。将只能使用随机地图。"); // 记录警告信息。
        }

        JList<String> mapList = new JList<>(listModel); // 根据列表模型创建一个JList。
        mapList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // 设置为单选模式。
        mapList.setSelectedIndex(0); // 默认选中第一项。
        mapList.setBackground(CARD_BACKGROUND); // 设置背景色。
        mapList.setForeground(TEXT_LIGHT); // 设置文字颜色。
        mapList.setFont(labelFont); // 设置字体。
        JScrollPane listScroller = new JScrollPane(mapList); // 将列表放入滚动面板。
        listScroller.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // 设置内边距。

        JPanel buttonPanel = new JPanel(); // 创建底部按钮面板。
        buttonPanel.setBackground(BACKGROUND_DARK); // 设置背景色。
        JButton selectButton = new JButton("启动 (Start)"); // 创建启动按钮。
        styleButton(selectButton); // 应用样式。
        JButton cancelButton = new JButton("取消 (Cancel)"); // 创建取消按钮。
        styleButton(cancelButton); // 应用样式。
        buttonPanel.add(selectButton); // 添加按钮。
        buttonPanel.add(cancelButton);

        mapDialog.add(listScroller, BorderLayout.CENTER); // 将列表添加到对话框中央。
        mapDialog.add(buttonPanel, BorderLayout.SOUTH); // 将按钮面板添加到对话框底部。

        // 为启动按钮添加点击事件。
        selectButton.addActionListener(e -> {
            String selectedValue = mapList.getSelectedValue(); // 获取用户选中的地图名称。
            if (selectedValue == null) { // 如果没有选中任何项。
                // 弹出警告框。
                JOptionPane.showMessageDialog(mapDialog, "请选择一个地图或随机生成选项。", "未选择", JOptionPane.WARNING_MESSAGE);
                return; // 结束事件处理。
            }

            mapDialog.dispose(); // 关闭地图选择对话框。

            MapData mapData;

            if (!selectedValue.equals("[随机生成地图 (Randomly Generated)]")) { // 如果用户选择了具体的地图文件。
                File mapFile = new File(mapDir, selectedValue); // 创建地图文件对象。
                try (FileReader reader = new FileReader(mapFile)) { // 尝试读取文件。
                    mapData = gson.fromJson(reader, MapData.class); // 使用Gson解析为MapData对象。
                    mapData.setName(mapFile.getName()); // 提取纯净地图名并绑定到数据核心
                    log("已加载核心文件地形网络: " + mapData.getName()); // 记录日志。
                } catch (Exception ex) { // 如果加载失败。
                    log("致命错误: 无法解析 JSON 地图文件. " + ex.getMessage()); // 记录错误。
                    return; // 结束。
                }
            } else { // 如果用户选择了随机生成。
                // [依赖注入重构 (IoC / Dependency Injection)]
                log("系统正在呼叫专职的 MapGenerator 提前布置随机沙盘世界...");
                mapData = MapGenerator.generateRandomMap(1600, 900);
            }

            // 防御性编程：卡死 null 不允许其污染服务器实体。
            if (mapData == null) {
                JOptionPane.showMessageDialog(mapDialog, "内部错误：地图流提取失败！", "架构阻断", JOptionPane.ERROR_MESSAGE);
                return;
            }

            setServerRunningUIState(true); // 更新UI状态为“服务器运行中”。

            // [修改] 直接传递捆绑好名字的 mapData 对象
            startServer(mapData);
        });

        // 为取消按钮添加点击事件。
        cancelButton.addActionListener(e -> mapDialog.dispose()); // 关闭对话框。

        mapDialog.setVisible(true); // 显示地图选择对话框。
    }

    /**
     * Centralized method to enable/disable UI components based on server state.
     * (根据服务器状态启用/禁用UI组件的集中式方法。)
     * 
     * @param isRunning true if the server is running, false otherwise.
     *                  (如果服务器正在运行则为true，否则为false。)
     */
    private void setServerRunningUIState(boolean isRunning) {

        // 启用/禁用顶部配置面板中的所有组件。
        for (Component c : configPanel.getComponents()) {
            c.setEnabled(!isRunning); // 如果服务器在运行，则禁用；否则启用。
        }

        // 切换底部动作按钮的状态。
        startButton.setEnabled(!isRunning); // 启动按钮。
        newMapButton.setEnabled(!isRunning); // 新建地图按钮。
        editMapButton.setEnabled(!isRunning); // 编辑地图按钮。
        stopButton.setEnabled(isRunning); // 停止按钮。

        manageAiButton.setEnabled(isRunning);
        freezeAiButton.setEnabled(isRunning);
        // 如果UI正在被启用（即服务器停止时），重置按钮文本
        if (!isRunning) {
            freezeAiButton.setText("冻结AI");
        }
        // 根据服务器运行状态，控制“管理玩家”按钮的可用性
        // 我们需要通过反射或查找组件来获取按钮引用
        for (Component comp : mainActionPanel.getComponents()) {
            if (comp instanceof JButton && "管理玩家".equals(((JButton) comp).getText())) {
                comp.setEnabled(isRunning);
            }
        }
        // 控制命令相关组件的可用状态
        commandField.setEnabled(isRunning);
        executeCommandButton.setEnabled(isRunning);
        serverStatusButton.setEnabled(isRunning);
        // 如果UI正在被启用（即服务器停止时）。
        if (!isRunning && statusWindow != null) {
            statusWindow.dispose(); // 关闭窗口
            statusWindow = null; // 清除引用
        }
    }

    // ==================================================================================
    // 玩家实时状态管理面板
    // ==================================================================================
    class PlayerManagerDialog extends JDialog {
        private final GameServer gameServer;
        private JTable playerTable;
        private PlayerTableModel tableModel;
        private Timer refreshTimer; // 用于定时刷新数据的计时器

        PlayerManagerDialog(JFrame parent, GameServer server) {
            super(parent, "实时玩家管理器", false); // false表示非模态，不会阻塞主窗口
            this.gameServer = server;

            // --- 1. 创建表格和数据模型 ---
            tableModel = new PlayerTableModel();
            playerTable = new JTable(tableModel);
            styleTable(playerTable); // 应用样式

            // --- 2. 创建快捷操作按钮 ---
            // --- 2. 创建快捷操作按钮 ---
            JPanel topActionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            topActionPanel.setBackground(BACKGROUND_DARK);

            JButton giveKevlarButton = new JButton("给予半甲");
            styleButton(giveKevlarButton);
            giveKevlarButton.addActionListener(e -> applyToSelectedPlayers(player -> {
                player.hasKevlar = true;
                player.armorValue = 100;
            }));

            JButton giveFullArmorButton = new JButton("给予全甲");
            styleButton(giveFullArmorButton);
            giveFullArmorButton.addActionListener(e -> applyToSelectedPlayers(player -> {
                player.hasKevlar = true;
                player.hasHelmet = true;
                player.armorValue = 100;
            }));

            JButton giveC4Button = new JButton("给予C4");
            styleButton(giveC4Button);
            giveC4Button.addActionListener(e -> applyToSelectedPlayers(player -> {
                if (player.team == cs2d.playerAndAi.Player.Team.T)
                    player.hasBomb = true;
            }));

            // =================================================================
            // 重构“踢出玩家”按钮的事件监听器
            // =================================================================
            JButton kickButton = new JButton("踢出实体");
            styleButton(kickButton);
            kickButton.addActionListener(e -> {
                int[] selectedRows = playerTable.getSelectedRows();
                if (selectedRows.length == 0) {
                    JOptionPane.showMessageDialog(this, "请先在表格中选择要踢出的玩家或AI！", "提示", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }

                java.util.List<String> entitiesToKick = new java.util.ArrayList<>();
                for (int row : selectedRows) {
                    cs2d.playerAndAi.Player player = tableModel.getPlayerAt(row);
                    if (player != null) {
                        entitiesToKick.add(player.id);
                        log("准备踢出 " + (player.isAI ? "AI: " : "玩家: ") + player.name);
                    }
                }

                for (String entityId : entitiesToKick) {
                    gameServer.removePlayer(entityId);
                }

                log("已发送 " + entitiesToKick.size() + " 个踢出请求。");
                updateTableData();
            });
            // =================================================================

            topActionPanel.add(giveKevlarButton);
            topActionPanel.add(giveFullArmorButton);
            topActionPanel.add(giveC4Button);
            topActionPanel.add(kickButton);

            JPanel bottomActionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            bottomActionPanel.setBackground(BACKGROUND_DARK);

            JLabel weaponLabel = new JLabel("指定枪械: ");
            weaponLabel.setForeground(TEXT_LIGHT);
            JTextField weaponField = new JTextField(15);
            styleComponent(weaponField);

            JButton weaponHelpButton = new JButton("!");
            styleButton(weaponHelpButton);
            weaponHelpButton.setMargin(new Insets(2, 5, 2, 5));
            weaponHelpButton.addActionListener(e -> {
                String[] weaponNames = java.util.Arrays.stream(cs2d.playerAndAi.Weapon.values())
                        .map(Enum::name).toArray(String[]::new);

                JDialog weaponDialog = new JDialog(this, "枪械列表 (可复制小本本)", false);
                JTextArea textArea = new JTextArea(String.join("\n", weaponNames));
                textArea.setEditable(false);
                textArea.setBackground(CARD_BACKGROUND);
                textArea.setForeground(TEXT_LIGHT);
                textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
                textArea.setMargin(new Insets(10, 10, 10, 10));

                JScrollPane sp = new JScrollPane(textArea);
                sp.setBorder(BorderFactory.createEmptyBorder());
                weaponDialog.add(sp);
                weaponDialog.setSize(250, 400);
                weaponDialog.setLocationRelativeTo(this);
                weaponDialog.setVisible(true);
            });

            JButton setWeaponButton = new JButton("发枪(装备)");
            styleButton(setWeaponButton);
            setWeaponButton.addActionListener(e -> {
                String req = weaponField.getText().trim().toUpperCase();
                if (req.isEmpty())
                    return;
                try {
                    cs2d.playerAndAi.Weapon wep = cs2d.playerAndAi.Weapon.valueOf(req);
                    applyToSelectedPlayers(player -> {
                        player.setWeapon(wep);
                        if (wep.getWeaponType().isPistol()) {
                            player.selectedSecondaryName = wep.name();
                        } else {
                            player.selectedPrimaryName = wep.name();
                        }
                    });
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(this, "无效的枪械名称: " + req, "错误", JOptionPane.ERROR_MESSAGE);
                }
            });

            bottomActionPanel.add(weaponLabel);
            bottomActionPanel.add(weaponField);
            bottomActionPanel.add(weaponHelpButton);
            bottomActionPanel.add(setWeaponButton);

            JPanel southContainer = new JPanel();
            southContainer.setLayout(new BoxLayout(southContainer, BoxLayout.Y_AXIS));
            southContainer.add(topActionPanel);
            southContainer.add(bottomActionPanel);

            // --- 3. 组装UI ---
            JScrollPane scrollPane = new JScrollPane(playerTable);
            scrollPane.getViewport().setBackground(CARD_BACKGROUND);

            getContentPane().setLayout(new BorderLayout());
            getContentPane().add(scrollPane, BorderLayout.CENTER);
            getContentPane().add(southContainer, BorderLayout.SOUTH);

            // --- 4. 设置窗口属性和定时刷新 ---
            setSize(1100, 400); // <--- 宽度
            setLocationRelativeTo(parent);

            // 创建一个每秒刷新2次的计时器
            refreshTimer = new Timer(500, e -> updateTableData());
            refreshTimer.start();

            // 添加窗口关闭事件，确保计时器被正确停止
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    refreshTimer.stop();
                }
            });

            setVisible(true);
        }

        // 从游戏服务器获取最新玩家数据并刷新表格
        private void updateTableData() {
            // 1. 首先检查 GameServer 和 GameState
            if (gameServer == null || gameServer.getGameState() == null) {
                // [修改] 调用新的更新方法，传入空列表和 null GameState
                tableModel.updateAllPlayerData(Collections.emptyList(), null);
                return;
            }

            // --- [新增] 记录当前选中的玩家 ID，用于刷新后恢复选中状态 ---
            int[] selectedRows = playerTable.getSelectedRows();
            java.util.Set<String> selectedIds = new java.util.HashSet<>();
            for (int row : selectedRows) {
                // 注意：在刷新前获取 ID
                Object idObj = tableModel.getValueAt(row, 0); // 第一列是 ID
                if (idObj != null) {
                    selectedIds.add(idObj.toString());
                }
            }

            // 2. 获取 *当前* 的 GameState 实例一次
            GameState currentGameState = gameServer.getGameState();

            // 3. 使用这个 GameState 实例来获取 *所有* 玩家列表 (包括人类和AI)
            java.util.List<Player> allPlayers = currentGameState.getPlayers(); // 或者 getAllCharacters() 如果你需要包含所有实体

            // 4. [修改] 将玩家列表和 *同一个* GameState 实例传递给模型进行处理
            tableModel.updateAllPlayerData(allPlayers, currentGameState);

            // --- [新增] 恢复选中状态 ---
            if (!selectedIds.isEmpty()) {
                // 暂时静默恢复，addSelectionInterval 会在 fireTableDataChanged 后生效
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    Object idObj = tableModel.getValueAt(i, 0);
                    if (idObj != null && selectedIds.contains(idObj.toString())) {
                        playerTable.getSelectionModel().addSelectionInterval(i, i);
                    }
                }
            }
        }

        // 对所有选中的玩家执行一个操作
        private void applyToSelectedPlayers(java.util.function.Consumer<cs2d.playerAndAi.Player> action) {
            int[] selectedRows = playerTable.getSelectedRows();
            if (selectedRows.length == 0) {
                JOptionPane.showMessageDialog(this, "请先在表格中选择一个或多个玩家！", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            for (int row : selectedRows) {
                cs2d.playerAndAi.Player player = tableModel.getPlayerAt(row);
                if (player != null) {
                    action.accept(player);
                }
            }
            updateTableData(); // 操作后立即刷新
        }

        // 表格样式设置
        private void styleTable(JTable table) {
            table.setBackground(CARD_BACKGROUND);
            table.setForeground(TEXT_LIGHT);
            table.setFont(labelFont);
            table.setGridColor(BORDER_COLOR);
            table.setRowHeight(25);
            table.getTableHeader().setBackground(BACKGROUND_DARK);
            table.getTableHeader().setForeground(PRIMARY_BLUE);
            table.getTableHeader().setFont(labelFont.deriveFont(Font.BOLD));
            table.setFillsViewportHeight(true);
        }

        // --- 表格的数据模型 (TableModel) ---

        private class PlayerTableModel extends AbstractTableModel {

            // 常量数据模型对象
            private record PlayerData(
                    String id, String name, Player.Team team, int health, String armor, int money,
                    String primaryWeapon, String primaryAmmo, String secondaryWeapon, String secondaryAmmo,
                    String equipment, String bandwidth, String aiStatus // 注意：列名与 columnNames 对应
            ) {
            }

            // --- [修改] 存储类型改为 PlayerData ---
            private java.util.List<PlayerData> displayData = new java.util.ArrayList<>();

            // --- 列名保持不变 (或根据需要调整) ---
            private final String[] columnNames = { "ID", "名称", "阵营", "HP", "护甲", "金钱", "主武器", "主武器弹药", "副武器", "副武器弹药",
                    "持有道具", "带宽 (上传/下载)", "AI状态" };

            /**
             * 周期性执行以全量刷新玩家的信息视图。
             * 
             * @param allPlayers       包含机器人的玩家实例全集
             * @param currentGameState 游戏世界的中心数据引用
             */
            public void updateAllPlayerData(java.util.List<Player> allPlayers, GameState currentGameState) {
                // 如果 GameState 无效，清空数据并通知表格
                if (currentGameState == null) {
                    this.displayData = Collections.emptyList();
                    fireTableDataChanged();
                    return;
                }

                java.util.List<PlayerData> newData = new java.util.ArrayList<>();

                for (Player player : allPlayers) {
                    // 基本安全检查
                    if (player == null)
                        continue;

                    // --- 提取并格式化所有列的数据 ---
                    String id = player.id;
                    String name = player.name;
                    Player.Team team = player.team;
                    int health = player.health;
                    String armor = player.armorValue + (player.hasHelmet ? " (H)" : "");
                    int money = player.money;
                    String primaryWeapon = player.primaryWeapon != null ? player.primaryWeapon.name : "N/A";
                    String primaryAmmo = "N/A";
                    if (player.primaryWeapon != null) {
                        if (player.primaryWeapon.isIndividualReload) {
                            primaryAmmo = player.primary_currentAmmo + "/" + player.primary_reserveAmmo;
                        } else {
                            primaryAmmo = player.primary_currentAmmo + "/"
                                    + (player.primary_reserveAmmo / player.primaryWeapon.magazineSize);
                        }
                    }

                    String secondaryWeapon = player.secondaryWeapon != null ? player.secondaryWeapon.name : "N/A";
                    String secondaryAmmo = "N/A";
                    if (player.secondaryWeapon != null) {
                        if (player.secondaryWeapon.isIndividualReload) {
                            secondaryAmmo = player.secondary_currentAmmo + "/" + player.secondary_reserveAmmo;
                        } else {
                            secondaryAmmo = player.secondary_currentAmmo + "/"
                                    + (player.secondary_reserveAmmo / player.secondaryWeapon.magazineSize);
                        }
                    }
                    String equipment = (player.equipment != null && !player.equipment.isEmpty()) ? player.equipment
                            .keySet().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(", ")) : "";

                    String bandwidth = "N/A";
                    if (!player.isAI && gameServer != null) { // 带宽只对人类玩家计算
                        double sendRateBps = gameServer.getSendRateBpsForPlayer(player.id);
                        double receiveRateBps = gameServer.getReceiveRateBpsForPlayer(player.id);
                        double sendRateMBps = sendRateBps / (1024.0 * 1024.0);
                        double receiveRateKBps = receiveRateBps / 1024.0; // 改为 KB/s 可能更合适
                        bandwidth = String.format("%.2f MB/s / %.1f KB/s", sendRateMBps, receiveRateKBps);
                    } else if (player.isAI) {
                        bandwidth = "N/A (人机)";
                    }

                    // AI 状态的获取逻辑可能需要调整，取决于 Player 对象是否直接包含状态字符串
                    // 假设 Player 对象没有直接的状态字符串，我们需要从 AI 控制器获取
                    String aiStatus = "N/A (人类)"; // 默认值
                    if (player.isAI) {
                        aiStatus = "N/A (AI)"; // 基础状态
                        // 尝试获取更详细的状态 (如果 Player 对象能访问控制器)
                        // 这部分逻辑可能需要根据你的 Player 类结构调整
                        if (player.getTdmController() != null) { // 假设有 getTdmController()
                            // 可能需要一个方法从控制器获取状态字符串
                            // aiStatus = player.getTdmController().getCurrentStateName(); // 假设有这个方法
                        } else if (player.getPerceptionModule() != null) {
                            // 或者显示感知信息作为替代
                            Map<String, PerceptionModule.PerceptionInfo> perceived = player.getPerceptionModule()
                                    .getAllPerceivedEnemies();
                            if (!perceived.isEmpty()) {
                                aiStatus = "感知到: " + perceived.size() + " 个目标";
                            } else {
                                aiStatus = "巡逻中/空闲"; // 默认AI状态
                            }
                        }
                    }

                    // 创建包含所有预处理信息的 PlayerData 对象
                    newData.add(new PlayerData(id, name, team, health, armor, money, primaryWeapon, primaryAmmo,
                            secondaryWeapon, secondaryAmmo, equipment, bandwidth, aiStatus));
                }

                // 用新数据替换旧数据，并通知表格更新
                this.displayData = newData;
                fireTableDataChanged(); // 这会触发 EDT 上的 getValueAt 调用
            }

            // --- [修改] 极其简单的 getValueAt 方法 ---
            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                // 边界检查
                if (rowIndex < 0 || rowIndex >= displayData.size())
                    return null;
                PlayerData data = displayData.get(rowIndex);
                // 安全检查
                if (data == null)
                    return null;

                // 直接返回对应列的预处理数据
                switch (columnIndex) {
                    case 0:
                        return data.id;
                    case 1:
                        return data.name;
                    case 2:
                        return data.team;
                    case 3:
                        return data.health;
                    case 4:
                        return data.armor;
                    case 5:
                        return data.money;
                    case 6:
                        return data.primaryWeapon;
                    case 7:
                        return data.primaryAmmo;
                    case 8:
                        return data.secondaryWeapon;
                    case 9:
                        return data.secondaryAmmo;
                    case 10:
                        return data.equipment;
                    case 11:
                        return data.bandwidth;
                    case 12:
                        return data.aiStatus;
                    default:
                        return null;
                }
            }

            // --- 其他 TableModel 方法保持不变 ---
            @Override
            public int getRowCount() {
                return displayData.size();
            }

            @Override
            public int getColumnCount() {
                return columnNames.length;
            }

            @Override
            public String getColumnName(int column) {
                return columnNames[column];
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) { /* ... 保持不变 ... */
                switch (columnIndex) {
                    case 1: // 名称
                    case 2: // 阵营
                    case 3: // HP
                    case 4: // 护甲
                    case 5: // 金钱
                    case 7: // 主武器弹药
                    case 9: // 副武器弹药
                        return true;
                    default:
                        return false;
                }
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) { /* ... 保持不变 ... */
                Player player = getPlayerAt(rowIndex); // 注意：getPlayerAt 现在需要修改
                if (player == null)
                    return;
                boolean dataChanged = true;
                try {
                    switch (columnIndex) {
                        case 1: // 修改名称
                            player.name = aValue.toString();
                            break;
                        case 2: // 修改阵营
                            if (aValue instanceof Player.Team) {
                                player.team = (Player.Team) aValue;
                            }
                            break;
                        case 3: // 修改HP
                            player.health = Math.max(0, Integer.parseInt(aValue.toString()));
                            break;
                        case 4: // 修改护甲 (只支持输入纯数字，简单处理)
                            try {
                                int armorVal = Integer.parseInt(aValue.toString().replaceAll("[^0-9]", ""));
                                player.armorValue = Math.max(0, armorVal);
                                player.hasKevlar = player.armorValue > 0;
                                // 简化：如果编辑护甲，不自动添加头盔，除非明确输入 (H) - 但这里没处理
                                if (player.armorValue == 0)
                                    player.hasHelmet = false;
                            } catch (NumberFormatException ignored) {
                            } // 忽略解析错误
                            break;
                        case 5: // 修改金钱
                            player.money = Math.max(0, Math.min(16000, Integer.parseInt(aValue.toString())));
                            break;
                        case 7: // 修改主武器弹药
                            String[] primaryAmmo = aValue.toString().split("/");
                            if (primaryAmmo.length == 2) {
                                int newClipAmmo = Integer.parseInt(primaryAmmo[0].trim());
                                int newReserveInput = Integer.parseInt(primaryAmmo[1].trim());
                                player.primary_currentAmmo = newClipAmmo;

                                // [弹匣化输入处理]
                                if (player.primaryWeapon != null && !player.primaryWeapon.isIndividualReload) {
                                    // 输入的是弹匣数，转为子弹数
                                    player.primary_reserveAmmo = newReserveInput * player.primaryWeapon.magazineSize;
                                } else {
                                    // 散弹枪或无武器，直接存子弹数
                                    player.primary_reserveAmmo = newReserveInput;
                                }

                                if (player.currentSlot == 1) {
                                    player.currentAmmo = player.primary_currentAmmo;
                                    player.reserveAmmo = player.primary_reserveAmmo;
                                }
                            } else {
                                throw new NumberFormatException("弹药格式必须为 '当前子弹/备用弹匣(或总数)'");
                            }
                            break;
                        case 9: // 修改副武器弹药
                            String[] secondaryAmmo = aValue.toString().split("/");
                            if (secondaryAmmo.length == 2) {
                                int newClipAmmo = Integer.parseInt(secondaryAmmo[0].trim());
                                int newReserveInput = Integer.parseInt(secondaryAmmo[1].trim());
                                player.secondary_currentAmmo = newClipAmmo;

                                // [弹匣化输入处理]
                                if (player.secondaryWeapon != null && !player.secondaryWeapon.isIndividualReload) {
                                    player.secondary_reserveAmmo = newReserveInput
                                            * player.secondaryWeapon.magazineSize;
                                } else {
                                    player.secondary_reserveAmmo = newReserveInput;
                                }

                                if (player.currentSlot == 2) {
                                    player.currentAmmo = player.secondary_currentAmmo;
                                    player.reserveAmmo = player.secondary_reserveAmmo;
                                }
                            } else {
                                throw new NumberFormatException("弹药格式必须为 '当前子弹/备用弹匣(或总数)'");
                            }
                            break;
                        default:
                            dataChanged = false;
                            break;
                    }
                    if (dataChanged) {
                        fireTableCellUpdated(rowIndex, columnIndex);
                        if (gameServer != null) {
                            gameServer.broadcastFullUpdate();
                            log("管理员修改了玩家 " + player.name + " 的状态，已强制广播更新。");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("编辑单元格时出错: " + e.getMessage());
                }
            }

            // --- [修改] getPlayerAt 方法 ---
            // 注意：这个方法现在需要在需要时从 GameState 查找 Player，增加了 EDT 访问 GameState 的风险
            // 最好只在非 EDT 线程（如按钮事件处理器）中使用此方法
            public Player getPlayerAt(int rowIndex) {
                // 边界检查
                if (rowIndex < 0 || rowIndex >= displayData.size())
                    return null;
                // 安全检查
                if (displayData.get(rowIndex) == null)
                    return null;

                String id = displayData.get(rowIndex).id; // 获取 ID
                // 尝试从 GameServer 获取 Player 对象
                if (gameServer != null && gameServer.getGameState() != null) {
                    return gameServer.getGameState().getPlayerById(id);
                }
                // 如果无法获取，返回 null
                System.err.println("警告: PlayerTableModel.getPlayerAt 无法访问 GameState 来查找 Player 对象。");
                return null;
            }

            // [移除] 不再需要 setPlayers 方法
            // public void setPlayers(java.util.List<cs2d.playerAndAi.Player> players) { ...
            // }

        } // PlayerTableModel 结束
    }

    /**
     * 启动服务器的实际动作。
     * 
     * @param mapData 要加载的地图数据，不可为null。
     */
    private void startServer(MapData mapData) {
        log("正在启动服务器..."); // 记录日志。

        try { // 尝试。
              // --- 启动内置Web服务器 ---
            webServer = HttpServer.create(new InetSocketAddress(8080), 0); // 在8080端口创建一个HTTP服务器。
            final Path publicPath = Paths.get("public").toAbsolutePath(); // 获取"public"目录的绝对路径。

            if (!Files.exists(publicPath) || !Files.isDirectory(publicPath)) { // 如果"public"目录不存在。
                log("!!! 严重错误: 'public' 目录未找到 !!!"); // 记录严重错误。
                log("请确保 'public' 文件夹 (包含 index.html) 与服务器程序在同一目录下。");
                setServerRunningUIState(false); // 重新启用UI。
                return; // 结束。
            }

            // 为根路径("/")创建一个上下文，用于处理所有HTTP请求。
            webServer.createContext("/", exchange -> {
                try (exchange) { // 使用try-with-resources确保exchange被关闭。
                    URI uri = exchange.getRequestURI(); // 获取请求的URI。
                    String pathStr = uri.getPath().equals("/") ? "/index.html" : uri.getPath(); // 如果请求根路径，则默认为index.html。
                    Path filePath = publicPath.resolve(pathStr.substring(1)).normalize(); // 构建请求文件在服务器上的实际路径。

                    if (!filePath.startsWith(publicPath)) { // 安全检查：防止路径遍历攻击。
                        String response = "403 Forbidden"; // 如果请求的路径在"public"目录之外，返回403禁止访问。
                        exchange.sendResponseHeaders(403, response.length()); // 发送响应头。
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.getBytes());
                        } // 发送响应体。
                        return;
                    }

                    if (Files.isRegularFile(filePath)) { // 如果请求的是一个存在的文件。
                        exchange.sendResponseHeaders(200, Files.size(filePath)); // 发送200 OK响应和文件大小。
                        try (OutputStream os = exchange.getResponseBody()) {
                            Files.copy(filePath, os);
                        } // 将文件内容复制到响应体。
                    } else { // 如果文件不存在。
                        String response = "404 Not Found"; // 返回404未找到。
                        exchange.sendResponseHeaders(404, response.length());
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.getBytes());
                        }
                    }
                }
            });
            webServer.setExecutor(null); // 使用默认的线程执行器。
            webServer.start(); // 启动Web服务器。
            log("内置 Web 服务器已在端口 8080 启动。"); // 记录日志。

        } catch (IOException e) { // 如果启动失败。
            log("错误: 无法在端口 8080 启动 Web 服务器。端口可能已被占用。");
            log(e.getMessage());
            setServerRunningUIState(false); // 重新启用UI。
            return;
        }

        // --- 获取IP并显示连接信息 ---
        try (final DatagramSocket socket = new DatagramSocket()) { // 创建一个临时UDP套接字来探测本机IP。
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002); // 尝试连接到一个公共DNS服务器。
            String ip = socket.getLocalAddress().getHostAddress(); // 获取本机的局域网IP地址。
            // 在日志区打印连接指南。
            log("==================================================");
            log("成功: 游戏正在运行!");
            log("▶ 对于WEB浏览器客户端，请打开此地址:");
            log("  http://" + ip + ":8080");
            log("--------------------------------------------------");
            log("▶ 对于JavaFX桌面客户端，请连接到此IP地址:");
            log("  " + ip + " (端口: 14726)");
            log("(如果客户端在同一台电脑上运行，请使用 127.0.0.1)");
            log("==================================================");
        } catch (SocketException | UnknownHostException ex) { // 如果自动获取IP失败。
            log("==================================================");
            log("无法自动确定局域网IP。");
            log("请手动查找服务器IP地址并告知玩家。");
            log("==================================================");
        }

        // --- 启动核心的 UDP 游戏服务器 ---
        GameMode selectedMode; // 声明游戏模式变量。
        cs2d.server.GameState.isRLTrainingMode = false;
        switch (modeComboBox.getSelectedIndex()) { // 根据下拉框的选择确定游戏模式。
            case 1:
                selectedMode = GameMode.ZOMBIE_MODE;
                break;
            case 2:
                selectedMode = GameMode.DEMOLITION;
                break;
            // [2025-12-01] 新增死斗
            case 3:
                selectedMode = GameMode.DEATHMATCH;
                break;
            case 4:
                selectedMode = GameMode.DEMOLITION;
                cs2d.server.GameState.isRLTrainingMode = true;
                break;
            default:
                selectedMode = GameMode.TEAM_DEATHMATCH;
                break;
        }
        AIDifficulty selectedDifficulty = (AIDifficulty) difficultyComboBox.getSelectedItem(); // 获取选中的AI难度。
        int aiCount = (int) aiCountSpinner.getValue(); // 获取AI数量。
        int startingWave = (int) waveSpinner.getValue(); // 获取起始波数。

        // [修复] 移除冗余的 mapName 传参，由 GameServer 自动从 mapData 反向提取
        gameServer = new GameServer(14726, selectedMode, selectedDifficulty, aiCount, startingWave, mapData,
                "CS2D Server", this::log);
        gameServer.start(); // 启动游戏服务器线程。
    }

    /**
     * 将消息记录到UI的文本区域。
     */
    public void log(String message) {
        // 使用SwingUtilities.invokeLater确保UI更新操作在事件分发线程（EDT）上执行，保证线程安全。
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n"); // 将消息和换行符追加到文本区域。
            logArea.setCaretPosition(logArea.getDocument().getLength()); // 将光标（滚动条）移动到文本末尾，实现自动滚动。
        });
    }

    // --- 用于设置UI组件样式的辅助方法 ---

    // 添加一个带样式的标签到容器中。
    private void addStyledLabel(Container container, String text) {
        JLabel label = new JLabel(text); // 创建标签。
        label.setFont(labelFont); // 设置字体。
        label.setForeground(TEXT_LIGHT); // 设置文字颜色。
        container.add(label); // 添加到容器。
    }

    // 为组件应用基础样式。
    private void styleComponent(JComponent component) {
        component.setFont(labelFont); // 设置字体。
        // 为下拉框和微调器设置特殊样式。
        if (component instanceof JComboBox || component instanceof JSpinner) {
            component.setBackground(BACKGROUND_DARK); // 设置背景色。
            component.setForeground(TEXT_LIGHT); // 设置前景色。
            component.setPreferredSize(new Dimension(120, 30)); // 设置首选大小。
        }
    }

    // 为按钮应用详细的自定义样式和交互效果。
    private void styleButton(JButton btn) {
        btn.setFont(buttonFont); // 设置字体。
        btn.setBackground(BUTTON_COLOR); // 设置背景色。
        btn.setForeground(TEXT_LIGHT); // 设置文字颜色。
        btn.setFocusPainted(false); // 移除点击时默认的焦点边框。
        Border outerBorder = BorderFactory.createLineBorder(BORDER_COLOR, 2, true); // 创建一个带圆角的线边框。
        Border innerBorder = BorderFactory.createEmptyBorder(10, 20, 10, 20); // 创建一个内边距边框。
        btn.setBorder(BorderFactory.createCompoundBorder(outerBorder, innerBorder)); // 将两个边框组合起来应用到按钮。
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR)); // 鼠标悬停时变为手形光标。

        btn.addMouseListener(new java.awt.event.MouseAdapter() { // 添加鼠标监听器来处理交互效果。
            public void mouseEntered(java.awt.event.MouseEvent evt) { // 鼠标进入按钮区域时。
                if (btn.isEnabled()) { // 如果按钮是启用的。
                    btn.setBackground(BUTTON_HOVER_COLOR); // 设置为悬停背景色。
                    btn.setBorder(BorderFactory.createCompoundBorder( // 设置为悬停边框（蓝色高亮）。
                            BorderFactory.createLineBorder(PRIMARY_BLUE, 2, true),
                            innerBorder));
                }
            }

            public void mouseExited(java.awt.event.MouseEvent evt) { // 鼠标离开按钮区域时。
                btn.setBackground(BUTTON_COLOR); // 恢复默认背景色。
                btn.setBorder(BorderFactory.createCompoundBorder(outerBorder, innerBorder)); // 恢复默认边框。
            }

            public void mousePressed(java.awt.event.MouseEvent evt) { // 鼠标按下时。
                if (btn.isEnabled()) {
                    btn.setBackground(BUTTON_HOVER_COLOR.darker()); // 设置为更深的颜色，模拟按下效果。
                }
            }

            public void mouseReleased(java.awt.event.MouseEvent evt) { // 鼠标释放时。
                if (btn.isEnabled()) {
                    // 检查鼠标是否仍在按钮上，以决定是恢复到悬停状态还是默认状态。
                    if (btn.contains(evt.getPoint())) {
                        btn.setBackground(BUTTON_HOVER_COLOR);
                    } else {
                        btn.setBackground(BUTTON_COLOR);
                    }
                }
            }
        });
    }

    private String findAiIdByNameOrId(String nameOrId) {
        if (gameServer == null || gameServer.getGameState() == null) {
            return null;
        }
        GameState gameState = gameServer.getGameState();
        // 优先尝试直接按ID查找
        Player playerById = gameState.getPlayerById(nameOrId);
        if (playerById != null && playerById.isAI) { // Use isAI field or isAI() method
            return playerById.id;
        }
        // 如果按ID找不到，再按名字查找
        for (Player p : gameState.getAllCharacters()) { // 检查所有角色
            if (p.isAI && p.name.equalsIgnoreCase(nameOrId)) { // Use isAI field or isAI() method
                return p.id;
            }
        }
        return null; // 找不到返回 null
    }

    // ====================== AI ======================

    class AiManagerDialog extends JDialog {
        private final GameServer gameServer;
        private JTable aiTable;
        private AiTableModel tableModel;
        private Timer refreshTimer; // 用于定时刷新数据的计时器

        AiManagerDialog(JFrame parent, GameServer server) {
            // 设置窗口标题为 "实时 AI 状态监视器"
            super(parent, "实时 AI 状态监视器", false); // false表示非模态
            this.gameServer = server;

            // --- 1. 创建表格和数据模型 ---
            tableModel = new AiTableModel(); // 使用新的 AI 数据模型
            aiTable = new JTable((TableModel) tableModel); // 基于 AI 模型创建 JTable
            styleTable(aiTable); // 应用与玩家表格相似的样式

            // --- 2. 组装UI ---
            JScrollPane scrollPane = new JScrollPane(aiTable); // 将表格放入滚动面板
            scrollPane.getViewport().setBackground(CARD_BACKGROUND); // 设置滚动面板背景色

            getContentPane().setLayout(new BorderLayout()); // 设置内容面板布局为边界布局
            getContentPane().add(scrollPane, BorderLayout.CENTER); // 将滚动面板添加到中央

            // --- 3. 设置窗口属性和定时刷新 ---
            setSize(900, 300); // 设置窗口初始大小 (宽度调整以适应新列)
            setLocationRelativeTo(parent); // 窗口居中于父窗口

            // 创建一个每秒 (1000毫秒) 刷新1次的计时器
            refreshTimer = new Timer(1000, e -> updateTableData());
            refreshTimer.start(); // 启动计时器

            // 添加窗口关闭事件监听器，确保窗口关闭时停止计时器
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    // 停止计时器，释放资源
                    refreshTimer.stop();
                }
            });

            setVisible(true); // 显示窗口
        }

        // 从游戏服务器获取最新的AI数据并刷新表格
        // AiManagerDialog 内部
        private void updateTableData() {
            // 1. 检查 GameServer 和 GameState 是否有效
            if (gameServer == null || gameServer.getGameState() == null) {
                // [修改] 调用新的更新方法，传入空列表和 null GameState
                tableModel.updateAllAiData(Collections.emptyList(), null);
                return;
            }

            // --- [新增] 记录当前选中的 AI ID，用于刷新后恢复选中状态 ---
            int[] selectedRows = aiTable.getSelectedRows();
            java.util.Set<String> selectedIds = new java.util.HashSet<>();
            for (int row : selectedRows) {
                Object idObj = tableModel.getValueAt(row, 0); // 第一列是 ID
                if (idObj != null) {
                    selectedIds.add(idObj.toString());
                }
            }

            // 2. 获取 *当前* 的 GameState 实例一次
            GameState currentGameState = gameServer.getGameState();

            // 3. 使用这个 GameState 实例来获取 AI 列表
            java.util.List<Player> allAis = currentGameState.getAllCharacters().stream()
                    .filter(p -> p != null && p.isAI) // 增加 null 检查
                    .collect(Collectors.toList());

            // 4. [修改] 将 AI 列表和 *同一个* GameState 实例传递给模型进行处理
            tableModel.updateAllAiData(allAis, currentGameState);

            // --- [新增] 恢复选中状态 ---
            if (!selectedIds.isEmpty()) {
                // 由于 updateAllAiData 内部使用了 SwingUtilities.invokeLater，
                // 我们也必须在 invokeLater 中执行恢复，确保在 fireTableDataChanged 之后运行。
                SwingUtilities.invokeLater(() -> {
                    for (int i = 0; i < tableModel.getRowCount(); i++) {
                        Object idObj = tableModel.getValueAt(i, 0);
                        if (idObj != null && selectedIds.contains(idObj.toString())) {
                            aiTable.getSelectionModel().addSelectionInterval(i, i);
                        }
                    }
                });
            }
        }

        // 表格样式设置 (复用 PlayerManagerDialog 的样式方法)
        private void styleTable(JTable table) {
            table.setBackground(CARD_BACKGROUND);
            table.setForeground(TEXT_LIGHT);
            table.setFont(labelFont);
            table.setGridColor(BORDER_COLOR);
            table.setRowHeight(25);
            table.getTableHeader().setBackground(BACKGROUND_DARK);
            table.getTableHeader().setForeground(PRIMARY_BLUE);
            table.getTableHeader().setFont(labelFont.deriveFont(Font.BOLD));
            table.setFillsViewportHeight(true); // 表格填充整个视口高度
            // 允许用户调整列宽
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // 关闭自动调整，允许手动或后续设置

            // --- 设置各列的初始宽度 ---
            // "ID" 列
            table.getColumnModel().getColumn(0).setPreferredWidth(180);
            // "名称" 列
            table.getColumnModel().getColumn(1).setPreferredWidth(100);
            // "阵营" 列
            table.getColumnModel().getColumn(2).setPreferredWidth(60);
            // "HP" 列
            table.getColumnModel().getColumn(3).setPreferredWidth(50);
            // "位置" 列
            table.getColumnModel().getColumn(4).setPreferredWidth(120);
            // "目标点" 列 (需要足够宽度显示坐标)
            table.getColumnModel().getColumn(5).setPreferredWidth(150);
            // "按键" 列 (也需要一些宽度)
            table.getColumnModel().getColumn(6).setPreferredWidth(100);
            table.getColumnModel().getColumn(7).setPreferredWidth(250);
        }

        // --- AI 表格的数据模型 (AiTableModel) ---
        private class AiTableModel extends AbstractTableModel { // <--- 确认是 AiTableModel

            // --- [新增] 用于存储预处理好的 AI 显示数据 ---
            private record AiDisplayData(
                    String id, String name, Player.Team team, int health, String position,
                    String pathTarget, String keys, String perceptionString // 存储格式化后的感知字符串
            ) {
            }

            // --- [修改] 存储类型改为 AiDisplayData ---
            private java.util.List<AiDisplayData> displayData = new java.util.ArrayList<>();

            // --- 列名保持不变 ---
            private final String[] columnNames = { "ID", "名称", "阵营", "HP", "当前位置", "寻路目标点", "当前按键", "感知信息" };

            // --- [新增] 这个方法由 Timer 线程调用，负责获取和处理数据 ---
            public void updateAllAiData(java.util.List<Player> allAis, GameState currentGameState) {
                // 如果 GameState 无效，清空数据并通知表格
                if (currentGameState == null) {
                    // 确保在 AWT 事件线程上更新 UI
                    SwingUtilities.invokeLater(() -> {
                        this.displayData = Collections.emptyList();
                        fireTableDataChanged();
                    });
                    return;
                }

                java.util.List<AiDisplayData> newData = new java.util.ArrayList<>();
                long currentTime = System.currentTimeMillis(); // 获取一次当前时间，避免重复调用

                for (Player ai : allAis) {
                    // 基本安全检查 (确保是 AI)
                    if (ai == null || !ai.isAI)
                        continue;

                    // --- 提取基础信息 ---
                    String id = ai.id;
                    String name = ai.name;
                    Player.Team team = ai.team;
                    int health = ai.health;
                    // 安全地格式化位置
                    String position = (ai.position != null)
                            ? String.format("(%.1f, %.1f)", ai.position.x, ai.position.y)
                            : "N/A";

                    // --- 提取寻路信息 ---
                    String pathTarget = "N/A (无模块)";
                    PathfindingModule pathModule = ai.getPathfindingModule();
                    if (pathModule != null) {
                        Point2D.Double target = pathModule.getTargetPosition();
                        pathTarget = (target != null) ? String.format("(%.1f, %.1f)", target.x, target.y) : "无";
                    }

                    // --- 提取按键信息 ---
                    String keys = (ai.keysDown != null && !ai.keysDown.isEmpty()) ? String.join(", ", ai.keysDown)
                            : "无";

                    // --- 核心：处理并格式化感知信息 ---
                    String perceptionString = "N/A (无模块)";
                    PerceptionModule perceptionModule = ai.getPerceptionModule();
                    if (perceptionModule != null) {
                        // 在这里获取感知信息的快照 (确保线程安全)
                        Map<String, PerceptionModule.PerceptionInfo> perceivedEnemies = perceptionModule
                                .getAllPerceivedEnemies();
                        if (perceivedEnemies.isEmpty()) {
                            perceptionString = "无";
                        } else {
                            // 使用传入的 currentGameState (保证是同一时刻的状态) 来查找敌人名字并格式化
                            perceptionString = perceivedEnemies.values().stream()
                                    .map(info -> {
                                        // 使用 currentGameState 查找敌人
                                        Player enemyPlayer = currentGameState.getPlayerById(info.enemyId());
                                        // 安全地获取名字，如果找不到则用部分ID
                                        String enemyName = (enemyPlayer != null && enemyPlayer.name != null)
                                                ? enemyPlayer.name
                                                : info.enemyId().substring(0, Math.min(info.enemyId().length(), 4));
                                        long ageSeconds = (currentTime - info.timestamp()) / 1000;
                                        String visibleMarker = info.isCurrentlyVisible() ? "*" : ""; // 可见标记 '*'
                                        // 安全地获取类型简称 (S, G, F, ?)
                                        String typeChar = (info.type() != null && info.type().name().length() > 0)
                                                ? info.type().name().substring(0, 1)
                                                : "?";
                                        return String.format("%s%s(%s@%ds)", visibleMarker, enemyName, typeChar,
                                                ageSeconds);
                                    })
                                    .collect(Collectors.joining(", ")); // 用逗号和空格分隔
                        }
                    }

                    // 创建包含所有预处理信息的 AiDisplayData 对象
                    newData.add(
                            new AiDisplayData(id, name, team, health, position, pathTarget, keys, perceptionString));
                }

                // 在 AWT 事件线程上更新内部数据并通知表格
                SwingUtilities.invokeLater(() -> {
                    this.displayData = newData;
                    fireTableDataChanged(); // 这会触发 EDT 上的 getValueAt 调用
                });
            }

            // --- [修改] 极其简单的 getValueAt 方法 ---
            // 这个方法由 EDT 调用，只读取已准备好的数据
            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                // 边界检查
                if (rowIndex < 0 || rowIndex >= displayData.size())
                    return null;
                // 注意：由于 displayData 在 EDT 上更新，这里直接访问是安全的
                AiDisplayData data = displayData.get(rowIndex);
                // 安全检查
                if (data == null)
                    return null;

                // 直接返回对应列的预处理数据
                switch (columnIndex) {
                    case 0:
                        return data.id;
                    case 1:
                        return data.name;
                    case 2:
                        return data.team;
                    case 3:
                        return data.health;
                    case 4:
                        return data.position;
                    case 5:
                        return data.pathTarget;
                    case 6:
                        return data.keys;
                    case 7:
                        return data.perceptionString; // 直接返回格式化好的字符串
                    default:
                        return null;
                }
            }

            // --- 其他 TableModel 方法 ---
            @Override
            public int getRowCount() {
                return displayData.size();
            }

            @Override
            public int getColumnCount() {
                return columnNames.length;
            }

            @Override
            public String getColumnName(int column) {
                return columnNames[column];
            }

            // [修改] getAiAt 方法 - 注意线程安全风险
            public Player getAiAt(int rowIndex) {
                // 边界检查
                if (rowIndex < 0 || rowIndex >= displayData.size())
                    return null;
                // 安全检查 (虽然 displayData 在 EDT 更新，但仍需检查元素)
                AiDisplayData rowData = displayData.get(rowIndex);
                if (rowData == null)
                    return null;

                String id = rowData.id; // 获取 ID
                // 尝试从 GameServer 获取 Player 对象 - 这可能跨线程访问 GameState！
                // 只应在确保线程安全的情况下（例如在按钮事件处理中，且 GameState 是线程安全的）调用
                if (gameServer != null && gameServer.getGameState() != null) {
                    return gameServer.getGameState().getPlayerById(id);
                }
                // 如果无法获取，返回 null
                System.err.println("警告: AiTableModel.getAiAt 无法访问 GameState 来查找 Player 对象。");
                return null;
            }

            // [移除] 不再需要 setAis 方法
            // public void setAis(java.util.List<Player> ais) { ... }

        } // AiTableModel 结束
    }

    /**
     * 处理 "moveai" 命令的具体逻辑。
     * 解析参数、查找AI、调用 GameState 中的移动方法，并记录日志。
     * 
     * @param parts 解析后的命令部分列表，期望格式为 ["moveai", "<ai_name_or_id>", "<x>", "<y>"]
     */
    private void handleMoveAiCommand(java.util.List<String> parts) {
        // 参数数量检查已经在调用此方法前完成，这里可以直接使用
        String aiNameOrId = parts.get(1); // 获取AI的名字或ID
        String aiId = findAiIdByNameOrId(aiNameOrId); // 调用辅助方法查找ID

        if (aiId != null) {
            try {
                double x = Double.parseDouble(parts.get(2)); // 解析X坐标
                double y = Double.parseDouble(parts.get(3)); // 解析Y坐标

                // 检查 gameServer 和 gameState 是否可用
                if (gameServer != null && gameServer.getGameState() != null) {
                    gameServer.commandAiMoveTo(aiId, x, y);
                    // log("已向 AI ID: " + aiId + " ("+aiNameOrId+") 下达移动命令。"); // 可以在日志中同时显示名字/ID
                } else {
                    log("错误：游戏状态不可用，无法执行移动命令。");
                }
            } catch (NumberFormatException nfe) {
                // 注意：更新了错误消息中的命令格式示例
                log("命令格式错误：坐标必须是有效的数字。格式: moveai <ai_name_or_id>|\"<ai name>\" <x> <y>");
            }
        } else {
            log("找不到名为或ID为 '" + aiNameOrId + "' 的 AI。");
        }
    }

    // ====================== AI ======================
    private JButton serverStatusButton; // 添加状态窗口按钮的成员变量
    private ServerStatusWindow statusWindow = null; // 添加对状态窗口实例的引用，初始为 null

    private void showServerStatusWindow() {
        // 检查服务器是否正在运行
        if (gameServer == null) {
            JOptionPane.showMessageDialog(frame, "服务器未运行！", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 逻辑修改：
        // 如果窗口不存在，或者已经被彻底销毁（dispose），则新建
        if (statusWindow == null || !statusWindow.isDisplayable()) {
            statusWindow = new ServerStatusWindow(frame, gameServer);

            // 【重要】添加监听器：当状态窗口关闭时，手动将变量设为 null
            // 这样下次点击按钮时，程序就知道需要创建一个新的窗口
            statusWindow.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE); // 确保是销毁而不是隐藏
            statusWindow.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    statusWindow = null;
                }
            });

            statusWindow.setVisible(true);
        } else {
            // 3. 如果窗口已经存在（可能被挡住了）
            statusWindow.setVisible(true); // 确保它是可见的（不仅仅是 toFront）
            statusWindow.toFront(); // 把窗口带到最前面
            statusWindow.requestFocus(); // 尝试获取焦点
        }
    }

    /**
     * 停止正在运行的游戏和Web服务器，并重置UI。
     */
    private void stopServer() {
        log("正在关闭服务器...");
        // --- 在停止服务器逻辑之前关闭状态窗口 ---
        if (statusWindow != null) {
            statusWindow.dispose(); // 关闭窗口并释放资源
            statusWindow = null; // 清除引用
        }

        if (gameServer != null) {
            gameServer.stopServer(); // *** 关键：关闭游戏主线程和网络线程 ***
            gameServer = null;
        }
        if (webServer != null) {
            webServer.stop(0); // *** 关键：关闭 Web 服务器 ***
            webServer = null;
        }
        log("服务器已关闭。");
        setServerRunningUIState(false); // 更新UI状态
    }
}
