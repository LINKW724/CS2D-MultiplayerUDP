package cs2d.playerAndAi.doublePlayer.mines2;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
// 新增的 imports (来自 UI 参考文件)
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.util.List;

/**
 * 完整的扫雷游戏 - 包含所有高级功能
 * (已修复 UI 样式、崩溃 Bug 和求解器逻辑)
 */
public class MinesweeperGame  extends JFrame {

    // --- 游戏配置 (来自 UI 参考文件) ---
    private enum Difficulty {
        NOVICE("新手 (Novice)", 9, 9, 10),
        EASY("简单 (Easy)", 16, 16, 40),
        NORMAL("一般 (Normal)", 18, 18, 100),
        HARD("困难 (Hard)", 25, 25, 250),
        VERY_HARD("非常困难 (Very Hard)", 30, 40, 400),
        MASTER("大师 (Master)", 35, 55, 600),
        HELL("地狱 (Hell)", 35, 50, 800),
        CUSTOM("自定义 (Custom)", 0, 0, 0); // (新增)

        String name;
        int rows, cols, mines;

        Difficulty(String name, int r, int c, int m) {
            this.name = name;
            this.rows = r; // rows (高度)
            this.cols = c; // cols (宽度)
            this.mines = m;
        }

        // (新增) 用于自定义难度
        public Difficulty setCustom(int r, int c, int m) {
            this.rows = r;
            this.cols = c;
            this.mines = m;
            this.name = String.format("自定义 (%dx%d, %d 雷)", c, r, m);
            return this;
        }
    }
    private Difficulty currentDifficulty = Difficulty.EASY; // 默认难度// --- 游戏参数 (现在由 Difficulty 控制) ---
    private int gameWidth;  // (cols)
    private int gameHeight; // (rows)
    private int gameMines;
    private boolean requireUnique = true;

    // --- 新的 UI 组件 (来自 UI 参考文件) ---
    private JPanel gridPanel; // 替代 GameBoard
    private Cell[][] cells;   // 新的 JButton 数组
    private JLabel flagsLabel;
    private JLabel timeLabel;
    private JButton smileyButton;
    private final SmileyIcon smileyIcon;
    private static final Icon FLAG_ICON = new FlagIcon();
    private static final Icon MINE_ICON = new MineIcon();

    // 各种状态的图标/文本 (来自 UI 参考文件)
    private static final int SMILEY_STATE_NORMAL = 0;
    private static final int SMILEY_STATE_WIN = 1;
    private static final int SMILEY_STATE_LOSE = 2;
    private static final int SMILEY_STATE_CLICK = 3;

    // 数字颜色 (来自 UI 参考文件)
    private final Color[] NUM_COLORS = {
            Color.BLUE, new Color(0, 128, 0), Color.RED, new Color(0, 0, 128),
            new Color(128, 0, 0), new Color(64, 224, 208), Color.BLACK, Color.GRAY
    };

    // (修复 2) 新增：3D 边框
    private static final Border BORDER_RAISED = BorderFactory.createRaisedBevelBorder();
    private static final Border BORDER_LOWERED = BorderFactory.createLoweredBevelBorder();


    // --- 保留的 UI 组件 (来自原文件) ---
    private JButton solveButton;
    private JButton hintButton;
    private JButton analyzeButton;
    private JButton undoButton; // (新增)

    // --- 后端和状态 (来自原文件) ---
    private MineGenerator.GameState gameState;
    private Random random;
    private boolean firstClick = true;
    private boolean solvingInProgress = false;
    private Timer solverTimer; // AI 求解器计时器
    private final Timer gamePlayTimer; // 游戏计时器
    private int timePassed;

    // (新增 撤回) 历史栈
    private Stack<MineGenerator.GameState> historyStack = new Stack<>();
    private Stack<Integer> timeHistory = new Stack<>();

    public MinesweeperGame() {
        super("高级扫雷游戏");
        random = new Random();

        // --- 游戏计时器 (来自 UI 参考文件) ---
        this.timePassed = 0;
        this.gamePlayTimer = new Timer(1000, e -> {
            if (gameRunning()) {
                timePassed++;
                timeLabel.setText(String.format("%03d", Math.min(timePassed, 999)));
            }
        });

        // --- 图标 (来自 UI 参考文件) ---
        smileyIcon = new SmileyIcon(32); // 初始化图标

        initializeUI();
        startGame(currentDifficulty); // 使用默认难度开始
    }

    private boolean gameRunning() {
        // (修复) 游戏运行中 = 非首次点击 且 非AI求解中 且 (后端状态非空且未结束)
        return !firstClick && !solvingInProgress && gameState != null && !gameState.dead && !gameState.won;
    }

    private void initializeUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // --- 新布局 (来自 UI 参考文件) ---
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        createMenuBar();
        createTopPanel();
        createGridPanel();
        createBottomPanel();

        pack();
        setLocationRelativeTo(null);
    }
    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // --- 游戏菜单 (来自 UI 参考文件) ---
        JMenu gameMenu = new JMenu("游戏 (Game)");
        JMenu newGameMenu = new JMenu("新游戏 (New Game)");

        for (Difficulty diff : Difficulty.values()) {
            if (diff == Difficulty.CUSTOM) continue; // 不在列表中显示
            JMenuItem diffItem = new JMenuItem(diff.name);
            diffItem.addActionListener(e -> startGame(diff));
            newGameMenu.add(diffItem);
        }

        // (新增 自定义)
        newGameMenu.addSeparator();
        JMenuItem customItem = new JMenuItem("自定义 (Custom)...");
        customItem.addActionListener(e -> showCustomGameDialog());
        newGameMenu.add(customItem);

        gameMenu.add(newGameMenu);
        gameMenu.addSeparator();

        // (新增 撤回)
        JMenuItem undoItem = new JMenuItem("撤回上一步 (Undo)");
        undoItem.addActionListener(e -> onUndoClick());
        gameMenu.add(undoItem);
        gameMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> System.exit(0));
        gameMenu.add(exitItem);

        // --- 求解菜单 (来自原文件) ---
        JMenu solverMenu = new JMenu("求解");
        JMenuItem solveItem = new JMenuItem("自动求解");
        JMenuItem hintItem = new JMenuItem("提示 (下一步)");
        JMenuItem analyzeItem = new JMenuItem("分析当前局面");
        JMenuItem stepSolveItem = new JMenuItem("单步求解 (执行提示)");

        solveItem.addActionListener(e -> startAutoSolve());
        hintItem.addActionListener(e -> showNextHint());
        analyzeItem.addActionListener(e -> analyzeCurrentState());
        stepSolveItem.addActionListener(e -> applyNextHint());

        solverMenu.add(solveItem);
        solverMenu.add(stepSolveItem);
        solverMenu.add(hintItem);
        solverMenu.add(analyzeItem);

        // --- 选项菜单 (来自原文件) ---
        JMenu optionsMenu = new JMenu("选项");
        JCheckBoxMenuItem uniqueItem = new JCheckBoxMenuItem("要求唯一解", requireUnique);
        uniqueItem.addActionListener(e -> {
            requireUnique = uniqueItem.isSelected();
            startGame(currentDifficulty); // 重启当前难度的游戏
        });
        optionsMenu.add(uniqueItem);

        menuBar.add(gameMenu);
        menuBar.add(solverMenu);
        menuBar.add(optionsMenu);
        setJMenuBar(menuBar);
    }

    /**
     * 新增：创建顶部状态栏 (来自 UI 参考文件)
     */
    private void createTopPanel() {
        JPanel topPanel = new JPanel(new BorderLayout());
        flagsLabel = new JLabel("000");
        flagsLabel.setFont(new Font("Monospaced", Font.BOLD, 20));
        flagsLabel.setBorder(BorderFactory.createLoweredBevelBorder());
        flagsLabel.setOpaque(true);
        flagsLabel.setBackground(Color.BLACK);
        flagsLabel.setForeground(Color.RED);

        timeLabel = new JLabel("000");
        timeLabel.setFont(new Font("Monospaced", Font.BOLD, 20));
        timeLabel.setBorder(BorderFactory.createLoweredBevelBorder());
        timeLabel.setOpaque(true);
        timeLabel.setBackground(Color.BLACK);
        timeLabel.setForeground(Color.RED);

        smileyButton = new JButton(smileyIcon);
        smileyButton.setPreferredSize(new Dimension(40, 40));
        smileyButton.setMargin(new Insets(0, 0, 0, 0));
        smileyButton.setFocusable(false);
        smileyButton.addActionListener(e -> startGame(currentDifficulty)); // 重置

        topPanel.add(flagsLabel, BorderLayout.WEST);
        topPanel.add(smileyButton, BorderLayout.CENTER);
        topPanel.add(timeLabel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);
    }

    /**
     * 新增：创建底部控制栏 (来自原文件)
     */
    private void createBottomPanel() {
        JPanel bottomPanel = new JPanel(new FlowLayout());

        solveButton = new JButton("自动求解");
        hintButton = new JButton("提示");
        analyzeButton = new JButton("分析");
        undoButton = new JButton("撤回"); // (新增)

        solveButton.addActionListener(e -> startAutoSolve());
        hintButton.addActionListener(e -> showNextHint());
        analyzeButton.addActionListener(e -> analyzeCurrentState());
        undoButton.addActionListener(e -> onUndoClick()); // (新增)

        bottomPanel.add(solveButton);
        bottomPanel.add(hintButton);
        bottomPanel.add(analyzeButton);
        bottomPanel.add(undoButton); // (新增)

        add(bottomPanel, BorderLayout.SOUTH);
    }

    /**
     * 替换：创建中央雷区 (使用 JButtons)
     */
    private void createGridPanel() {
        gridPanel = new JPanel();
        gridPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(gridPanel, BorderLayout.CENTER);
        // 注意：实际的格子在 startGame() 中创建
    }

    /**
     * (新增 自定义)
     */
    private void showCustomGameDialog() {
        JTextField widthField = new JTextField(String.valueOf(gameWidth));
        JTextField heightField = new JTextField(String.valueOf(gameHeight));
        JTextField minesField = new JTextField(String.valueOf(gameMines));

        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        panel.add(new JLabel("宽度 (Width):"));
        panel.add(widthField);
        panel.add(new JLabel("高度 (Height):"));
        panel.add(heightField);
        panel.add(new JLabel("地雷数 (Mines):"));
        panel.add(minesField);

        int result = JOptionPane.showConfirmDialog(this, panel, "自定义游戏",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            try {
                int w = Integer.parseInt(widthField.getText());
                int h = Integer.parseInt(heightField.getText());
                int m = Integer.parseInt(minesField.getText());

                // C语言的验证逻辑
                if (w < 3 || h < 3) {
                    JOptionPane.showMessageDialog(this, "宽度和高度必须至少为 3。", "输入错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (m < 1 || m > (w * h) - 9) {
                    JOptionPane.showMessageDialog(this, "地雷数必须在 1 和 (格子总数 - 9) 之间。", "输入错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 创建一个自定义难度并开始游戏
                Difficulty customDiff = Difficulty.CUSTOM.setCustom(h, w, m);
                startGame(customDiff);

            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "请输入有效的数字。", "输入错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 重构：startGame 现在由 Difficulty 驱动
     */
    private void startGame(Difficulty difficulty) {
        // 1. 设置游戏参数
        this.currentDifficulty = difficulty;
        this.gameHeight = difficulty.rows; // 注意：行 = 高度
        this.gameWidth = difficulty.cols;  // 注意：列 = 宽度
        this.gameMines = difficulty.mines;

        // 2. 重置后端状态
        firstClick = true;
        // 确保使用新的Random对象
        random = new Random();
        gameState = new MineGenerator.GameState(gameWidth, gameHeight, gameMines, requireUnique, random);
        solvingInProgress = false;

        // (新增 撤回)
        historyStack.clear();
        timeHistory.clear();
        updateUndoButtonState();

        // 3. 重置UI状态
        timePassed = 0;
        timeLabel.setText("000");
        flagsLabel.setText(String.format("%03d", gameMines));
        smileyIcon.setState(SMILEY_STATE_NORMAL);
        smileyButton.repaint();

        if (solverTimer != null) solverTimer.stop();
        gamePlayTimer.stop();

        // 4. 重建雷区 (核心改动)
        gridPanel.removeAll();
        gridPanel.setLayout(new GridLayout(gameHeight, gameWidth)); // (rows, cols)
        cells = new Cell[gameHeight][gameWidth];

        for (int r = 0; r < gameHeight; r++) { // r = row = y
            for (int c = 0; c < gameWidth; c++) { // c = col = x
                cells[r][c] = new Cell(r, c);
                gridPanel.add(cells[r][c]);
            }
        }

        // 5. 重新计算窗口大小并居中
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * 关键：新的同步方法
     * 将 `gameState.grid` (后端) 的状态同步到 `cells[][]` (前端)
     */
    private void syncFrontendToBackend() {
        if (gameState == null || cells == null) return;

        boolean isDead = gameState.dead;
        boolean[] mines = gameState.layout.mines; // 真实地雷布局

        for (int r = 0; r < gameHeight; r++) {
            for (int c = 0; c < gameWidth; c++) {
                int i = r * gameWidth + c;
                byte value = gameState.grid[i];
                boolean isMine = (mines != null) && i < mines.length && mines[i];

                cells[r][c].syncAppearance(value, isDead, isMine);
            }
        }
        // 一次性重绘
        gridPanel.repaint();
    }

    /**
     * 重构：更新游戏状态 (现在更新新的UI组件)
     */
    private void updateGameStatus() {
        if (gameState == null) return;

        // (修复 胜利的脸)
        if (gameState.won) {
            smileyIcon.setState(SMILEY_STATE_WIN);
            smileyButton.repaint();
            gamePlayTimer.stop();
            // 胜利时自动标记所有剩余地雷
            flagsLabel.setText("000");
            syncFrontendToBackend(); // 确保所有旗帜都画上
            showVictoryDialog();
            return;
        }

        if (gameState.dead) {
            smileyIcon.setState(SMILEY_STATE_LOSE);
            smileyButton.repaint();
            gamePlayTimer.stop();
            revealAllMines(); // 调用 revealAllMines 来处理 'X' 和地雷
            showGameOverDialog();
            return;
        }

        // 正常游戏状态
        int flagged = countFlags();
        int remaining = gameMines - flagged;
        flagsLabel.setText(String.format("%03d", Math.max(0, remaining)));

        // (修复 胜利的脸) 确保非胜利/失败时，脸是正常的
        if (smileyIcon.getState() != SMILEY_STATE_NORMAL && !solvingInProgress) {
            smileyIcon.setState(SMILEY_STATE_NORMAL);
            smileyButton.repaint();
        }
    }


    private int countFlags() {
        int count = 0;
        if (gameState == null || gameState.grid == null) return 0;
        for (int i = 0; i < gameWidth * gameHeight; i++) {
            if (gameState.grid[i] == -1) count++;
        }
        return count;
    }
    private int countUncovered() {
        int count = 0;
        if (gameState == null || gameState.grid == null) return 0;
        for (int i = 0; i < gameWidth * gameHeight; i++) {
            if (gameState.grid[i] >= 0) count++;
        }
        return count;
    }

    /**
     * 重构：revealAllMines 现在只修改后端状态，并调用 sync
     */
    private void revealAllMines() {
        if (gameState == null || gameState.layout == null || gameState.layout.mines == null) return;

        for (int y = 0; y < gameHeight; y++) {
            for (int x = 0; x < gameWidth; x++) {
                int i = y * gameWidth + x;
                // C: 64 = 未标记的地雷
                if (gameState.layout.mines[i] && gameState.grid[i] == -2) {
                    gameState.grid[i] = 64;
                }
            }
        }
        syncFrontendToBackend();
    }

    // --- showGameOverDialog() 和 showVictoryDialog() 保持不变 ---
    private void showGameOverDialog() {
        solvingInProgress = false;
        if (solverTimer != null) solverTimer.stop();
        // (修复 4) 确保在显示对话框之前 UI 已同步
        syncFrontendToBackend();
        JOptionPane.showMessageDialog(this, "游戏结束！你踩到地雷了！", "游戏结束",
                JOptionPane.INFORMATION_MESSAGE);
    }
    private void showVictoryDialog() {
        solvingInProgress = false;
        if (solverTimer != null) solverTimer.stop();
        JOptionPane.showMessageDialog(this, "恭喜！你成功找到了所有地雷！", "游戏胜利",
                JOptionPane.INFORMATION_MESSAGE);
    }


    // =================================================================
    // (修复 1 & 3) 求解器和提示逻辑重构
    // =================================================================

    // (新增) 提示求解器的结果类型
    private enum HintType {
        SAFE,         // 100% 安全
        MINE,         // 100% 是地雷
        WRONG_FLAG    // 玩家插了旗，但那里 100% 是安全的
    }

    // (新增) 提示求解器的结果对象
    private static class Hint {
        final int x, y;
        final HintType type;
        Hint(int x, int y, HintType type) { this.x = x; this.y = y; this.type = type; }

        @Override
        public String toString() {
            switch (type) {
                case SAFE: return String.format("(%d, %d) 是安全的", x, y);
                case MINE: return String.format("(%d, %d) 是地雷", x, y);
                case WRONG_FLAG: return String.format("(%d, %d) 处的旗帜是错的！(那里是安全的)", x, y);
                default: return "";
            }
        }
    }


    /**
     * (已替换并修改) 新函数：运行 HintSolver 找到所有逻辑步骤
     * (已修改：优先返回与已揭开区域相邻的提示)
     * @return 一个 Hint 对象的列表，如果找不到则返回空列表
     */
    private List<Hint> getHintsFromSolver() {
        if (gameState.dead || gameState.won || solvingInProgress) return Collections.emptyList();

        if (firstClick) {
            // (根据您上一轮的请求，我们不显示弹窗)
            // JOptionPane.showMessageDialog(this, ...);
            return Collections.emptyList();
        }

        try {
            // 1. 调用新的、纯粹的求解器
            byte[] hintGrid = HintSolver.solve(
                    gameWidth, gameHeight, gameMines, gameState.grid, random
            );

            // 2. 比较原始盘面和提示盘面，生成提示列表

            // --- (核心修改：创建两个列表以区分优先级) ---
            List<Hint> adjacentHints = new ArrayList<>(); // 优先
            List<Hint> isolatedHints = new ArrayList<>(); // 次要

            for (int y = 0; y < gameHeight; y++) {
                for (int x = 0; x < gameWidth; x++) {
                    int i = y * gameWidth + x;
                    byte original = gameState.grid[i];
                    byte deduced = hintGrid[i];
                    Hint currentHint = null; // 临时的提示对象

                    // a) 玩家未知的格子 (-2) -> 被推导
                    if (original == -2) {
                        if (deduced == -3) { // MARK_SAFE
                            currentHint = new Hint(x, y, HintType.SAFE);
                        } else if (deduced == -1) { // MARK_KNOWN_MINE
                            currentHint = new Hint(x, y, HintType.MINE);
                        }
                    }
                    // b) 玩家插旗的格子 (-1) -> 被推导为安全
                    else if (original == -1) {
                        if (deduced == -3) { // MARK_SAFE
                            currentHint = new Hint(x, y, HintType.WRONG_FLAG);
                        }
                    }

                    // --- (核心修改：将提示放入正确的优先级列表) ---
                    if (currentHint != null) {
                        if (isAdjacentToRevealed(x, y)) {
                            adjacentHints.add(currentHint);
                        } else {
                            isolatedHints.add(currentHint);
                        }
                    }
                }
            }

            // --- (核心修改：合并列表，adjacentHints 在前) ---
            adjacentHints.addAll(isolatedHints);
            return adjacentHints;
            // --- (修改结束) ---

        } catch (Exception ex) {
            ex.printStackTrace();
            // (根据您上一轮的请求，我们不显示弹窗)
            // JOptionPane.showMessageDialog(this, "提示生成失败: " + ex.getMessage(),
            //         "提示错误", JOptionPane.ERROR_MESSAGE);
            return Collections.emptyList();
        }
    }

    /**
     * (已替换) “提示”按钮的逻辑
     */
    private void showNextHint() {
        if (gameState.dead || gameState.won || solvingInProgress) return;

        List<Hint> hints = getHintsFromSolver();

        if (hints.isEmpty()) {
            if (!firstClick) { // 只有在游戏已开始时才显示“卡住”
                // (已注释掉) 不要弹出 "卡住" 消息
                // JOptionPane.showMessageDialog(this,
                //         "求解器卡住。没有找到基于逻辑的下一步。",
                //         "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        } else {
            // (高亮第一个找到的提示)
            Hint firstHint = hints.get(0);
            highlightCell(firstHint.x, firstHint.y);

            // (已注释掉) 不要弹出 "逻辑提示" 框框
            // (在对话框中显示所有提示)
            // StringBuilder message = new StringBuilder("求解器找到了 " + hints.size() + " 个确定步骤：\n\n");
            // // 最多显示10个
            // for (int i = 0; i < Math.min(hints.size(), 10); i++) {
            //     message.append(hints.get(i).toString()).append("\n");
            // }
            // if (hints.size() > 10) {
            //     message.append("...等 (共 ").append(hints.size()).append(" 个)\n");
            // }

            // JTextArea textArea = new JTextArea(message.toString());
            // textArea.setEditable(false);
            // JScrollPane scrollPane = new JScrollPane(textArea);
            // scrollPane.setPreferredSize(new Dimension(350, 200));
            // JOptionPane.showMessageDialog(this, scrollPane, "逻辑提示",
            //         JOptionPane.INFORMATION_MESSAGE);
        }
    }
    /**
     * (已替换) “单步求解”按钮的逻辑
     */
    private void applyNextHint() {
        if (solvingInProgress || gameState.dead || gameState.won) return;

        // 1. 获取所有提示
        List<Hint> hints = getHintsFromSolver();

        if (hints.isEmpty()) {
            if (!firstClick) {
                JOptionPane.showMessageDialog(this,
                        "求解器卡住。没有找到基于逻辑的下一步。",
                        "单步求解", JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }

        // (新增 撤回)
        pushHistory();

        // 2. 只执行列表中的第一个提示
        Hint hint = hints.get(0);
        int x = hint.x;
        int y = hint.y;
        int index = y * gameWidth + x;

        switch (hint.type) {
            case SAFE:
                // 提示是安全的: 打开它
                if (gameState.grid[index] == -2) { // 仅当它是隐藏的
                    openSquare(x, y);
                }
                break;
            case MINE:
                // 提示是地雷: 标记它
                if (gameState.grid[index] == -2) { // 仅当它是隐藏的
                    toggleFlag(x, y);
                }
                break;
            case WRONG_FLAG:
                // 旗帜是错的: 帮玩家取消标记，然后打开它
                if (gameState.grid[index] == -1) { // 仅当它被插旗
                    toggleFlag(x, y); // 取消标记
                    openSquare(x, y); // 打开
                }
                break;
        }

        // 注意： `openSquare` 和 `toggleFlag` 内部已经调用了
        // syncFrontendToBackend() 和 updateGameStatus()。
    }
    /**
     * 重构：highlightCell 现在操作 `Cell` 按钮
     */
    private void highlightCell(int x, int y) {
        if (x < 0 || x >= gameWidth || y < 0 || y >= gameHeight) return;

        Cell cell = cells[y][x];
        Color oldBg = cell.getBackground();
        Border oldBorder = cell.getBorder();

        cell.setBackground(Color.YELLOW);
        cell.setBorder(BorderFactory.createLineBorder(Color.RED, 2));

        Timer highlightTimer = new Timer(2000, e -> {
            byte value = gameState.grid[y * gameWidth + x];
            boolean isMine = (gameState.layout.mines != null) && gameState.layout.mines[y * gameWidth + x];
            cell.syncAppearance(value, gameState.dead, isMine);
        });
        highlightTimer.setRepeats(false);
        highlightTimer.start();
    }

    /**
     * (修复 1 & 3) 自动求解系统
     */
    private void startAutoSolve() {
        if (gameState.dead || gameState.won || solvingInProgress) return;
        if (firstClick) {
            openFirstClick();
        }
        solvingInProgress = true;

        solverTimer = new Timer(100, new ActionListener() {
            private int stepCount = 0;
            private final int maxSteps = gameWidth * gameHeight;
            @Override
            public void actionPerformed(ActionEvent e) {
                if (stepCount++ >= maxSteps || gameState.dead || gameState.won) {
                    ((Timer)e.getSource()).stop();
                    solvingInProgress = false;
                    updateGameStatus();
                    return;
                }

                // (新增 撤回)
                pushHistory();

                // "自动求解" 循环调用 "单步求解"
                if (!performSolveStep()) {
                    ((Timer)e.getSource()).stop();
                    solvingInProgress = false;
                    updateGameStatus();
                }

                syncFrontendToBackend();
                updateGameStatus();
            }
        });
        solverTimer.start();
    }
    // --- performSolveStep() 保持不变 ---
    // (它修改后端 gameState.grid，这是正确的)
    private boolean performSolveStep() {
        try {
            byte[] gridBefore = gameState.grid.clone();
            byte[] tempGrid = gameState.grid.clone();
            boolean[] opened = new boolean[gameWidth * gameHeight];
            for (int i = 0; i < gameWidth * gameHeight; i++) {
                opened[i] = (gameState.grid[i] >= 0);
            }
            MineGenerator.MineCtx ctx = new MineGenerator.MineCtx(
                    gameState.layout.mines, opened, gameWidth, gameHeight,
                    gameState.layout.startx, gameState.layout.starty, random
            );
            MineSolver.MineOpener openFunc = (c, x, y) -> {
                if (x < 0 || x >= gameWidth || y < 0 || y >= gameHeight) return (byte) -1;
                if (gameState.grid[y * gameWidth + x] >= 0) return gameState.grid[y * gameWidth + x];
                byte result = MineSolver.mineopen(ctx, x, y);
                if (result >= 0) {
                    gameState.grid[y * gameWidth + x] = result;
                } else {
                    gameState.grid[y * gameWidth + x] = 65;
                    gameState.dead = true;
                }
                return result;
            };
            MineSolver.MinePerturber perturbFunc = (c, grid, sx, sy, mask) -> {
                MineSolver.Perturbations perts = MineSolver.mineperturb(c, grid, sx, sy, mask);
                if (perts != null) {
                    System.arraycopy(tempGrid, 0, gameState.grid, 0, tempGrid.length);
                }
                return perts;
            };
            int result = MineSolver.minesolve(gameWidth, gameHeight, gameMines, tempGrid,
                    openFunc, perturbFunc, ctx, random);
            boolean changed = !Arrays.equals(gridBefore, gameState.grid);
            return changed && !gameState.dead && !gameState.won;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    // --- analyzeCurrentState() 保持不变 ---
    // (它只显示一个 JOptionPane，不需要 UI 同步)
    /**
     * (已修复：使用 "非作弊" 提示求解器)
     */
    /**
     * (已替换：使用 HintSolver)
     */
    private void analyzeCurrentState() {
        StringBuilder analysis = new StringBuilder();
        analysis.append("=== 游戏状态分析 ===\n\n");
        int flagged = countFlags();
        int uncovered = countUncovered();
        int unknown = gameWidth * gameHeight - uncovered;
        int remainingMines = gameMines - flagged;
        analysis.append(String.format("已揭开: %d 方块\n", uncovered));
        analysis.append(String.format("已标记: %d 地雷\n", flagged));
        analysis.append(String.format("未知 (未标记): %d 方块\n", unknown - flagged));
        analysis.append(String.format("剩余地雷 (估计): %d\n\n", remainingMines));
        if (unknown - flagged > 0) {
            double probability = (double) remainingMines / (unknown - flagged);
            analysis.append(String.format("未知方块的地雷概率: %.1f%%\n", probability * 100));
        }

        JTextArea textArea = new JTextArea(analysis.toString());
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(350, 250));
        JOptionPane.showMessageDialog(this, scrollPane, "局面分析",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // --- openFirstClick() 保持不变 ---
    private void openFirstClick() {
        if(firstClick) {
            int x = random.nextInt(gameWidth);
            int y = random.nextInt(gameHeight);
            openSquare(x, y);
        }
    }

    // --- (新增 撤回) ---
    private void pushHistory() {
        if (gameState == null) return;
        // 我们必须克隆
        historyStack.push(gameState.clone());
        timeHistory.push(timePassed);
        updateUndoButtonState();
    }

    private void onUndoClick() {
        if (historyStack.isEmpty()) return;

        // 弹出上一个状态
        this.gameState = historyStack.pop();
        this.timePassed = timeHistory.pop();

        // 检查这个状态是否是 "firstClick" 状态
        this.firstClick = (gameState.layout.mines == null);

        if (this.firstClick) {
            gamePlayTimer.stop();
            this.timePassed = 0;
        }

        // 停止任何正在进行的求解
        if (solverTimer != null) solverTimer.stop();
        solvingInProgress = false;

        // 刷新UI
        syncFrontendToBackend();
        updateGameStatus(); // 这会更新旗帜和计时器
        timeLabel.setText(String.format("%03d", Math.min(timePassed, 999))); // 确保时间正确
        updateUndoButtonState();
    }

    private void updateUndoButtonState() {
        boolean enabled = !historyStack.isEmpty() && !solvingInProgress;
        undoButton.setEnabled(enabled);
        // (在菜单中禁用)
        JMenu gameMenu = getJMenuBar().getMenu(0);
        for (Component item : gameMenu.getMenuComponents()) {
            if (item instanceof JMenuItem && "撤回上一步 (Undo)".equals(((JMenuItem)item).getText())) {
                item.setEnabled(enabled);
                break;
            }
        }
    }

    // --- openSquare() (后台工作线程逻辑) 保持不变 ---
    // (这是我们后端逻辑的核心)
    private void openSquare(int x, int y) {
        if (solvingInProgress || (gameState != null && (gameState.dead || gameState.won))) return;
        if (gameState.grid[y * gameWidth + x] == -1) return; // 不允许点击旗帜

        // (新增 撤回)
        pushHistory();

        if (firstClick) {
            firstClick = false;
            solvingInProgress = true;

            gridPanel.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            gamePlayTimer.start(); // 计时开始

            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() throws Exception {
                    int result = MineGenerator.open_square(gameState, x, y);
                    return null;
                }
                @Override
                protected void done() {
                    try {
                        get(); // 检查异常
                        syncFrontendToBackend();
                        updateGameStatus();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(MinesweeperGame.this,
                                "生成布局失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    } finally {
                        solvingInProgress = false;
                        gridPanel.setCursor(Cursor.getDefaultCursor());
                    }
                }
            };
            worker.execute();

        } else {
            // 非第一次点击
            int result = MineGenerator.open_square(gameState, x, y);
            syncFrontendToBackend();
            updateGameStatus();
        }
    }


    /**
     * 新增：右键点击的后端逻辑
     */
    private void toggleFlag(int x, int y) {
        if (solvingInProgress || gameState.dead || gameState.won) return;
        if (firstClick) return; // C 代码不允许在第一次点击前插旗

        int index = y * gameWidth + x;

        // C 代码只允许在 -1 (已标记) 和 -2 (未知) 之间切换
        if (gameState.grid[index] == -2) {
            gameState.grid[index] = -1; // 标记为地雷
        } else if (gameState.grid[index] == -1) {
            gameState.grid[index] = -2; // 取消标记
        }

        // 关键：同步 UI
        syncFrontendToBackend();
        updateGameStatus(); // 更新旗帜计数
    }

    /**
     * 新增：中键点击的后端逻辑 (来自原 GameBoard)
     */
    private void quickOpenAround(int x, int y) {
        if (solvingInProgress || gameState.dead || gameState.won) return;

        byte value = gameState.grid[y * gameWidth + x];
        if (value <= 0) return; // 只能在中键点击数字时触发

        int number = value;
        int flagCount = 0;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && nx < gameWidth && ny >= 0 && ny < gameHeight &&
                        gameState.grid[ny * gameWidth + nx] == -1) {
                    flagCount++;
                }
            }
        }

        if (flagCount == number) {
            // 标记正确，打开周围未标记的格子
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx >= 0 && nx < gameWidth && ny >= 0 && ny < gameHeight &&
                            gameState.grid[ny * gameWidth + nx] == -2) {
                        // C: 这是一个 "C" (Clear) 移动
                        // 我们通过多次调用 open_square 来模拟
                        MineGenerator.open_square(gameState, nx, ny);
                    }
                }
            }

            // 关键：同步 UI
            syncFrontendToBackend();
            updateGameStatus();
        }
    }
    private class Cell extends JButton {
        final int r, c; // r = y, c = x

        Cell(int r, int c) {
            this.r = r;
            this.c = c;

            // --- 外观 (来自 UI 参考文件) ---
            setPreferredSize(new Dimension(25, 25)); // 统一格子大小
            setFont(new Font("Monospaced", Font.BOLD, 16)); // 字体 (UI参考用24, C用20, 16更合适)
            setMargin(new Insets(0, 0, 0, 0));
            setFocusable(false);

            // (修复 2) 默认是凸起的
            setBorder(BORDER_RAISED);
            setBackground(null); // 使用默认 JButton 灰色

            // --- 输入 (重定向到我们的后端逻辑) ---
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (solvingInProgress || (gameState != null && (gameState.dead || gameState.won))) return;

                    // 按下时显示 :O 表情
                    if (e.getButton() == MouseEvent.BUTTON1 &&
                            !firstClick && gameState.grid[r * gameWidth + c] == -2) {
                        smileyIcon.setState(SMILEY_STATE_CLICK);
                        smileyButton.repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (solvingInProgress || (gameState != null && (gameState.dead || gameState.won))) return;

                    // 恢复表情
                    if (smileyIcon.getState() == SMILEY_STATE_CLICK) {
                        smileyIcon.setState(SMILEY_STATE_NORMAL);
                        smileyButton.repaint();
                    }

                    if (SwingUtilities.isLeftMouseButton(e)) {
                        // RE-WIRE: 调用我们的 main open logic
                        MinesweeperGame.this.openSquare(c, r); // (c, r) -> (x, y)
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        // RE-WIRE: 调用我们的 main flag logic
                        MinesweeperGame.this.toggleFlag(c, r); // (c, r) -> (x, y)
                    } else if (SwingUtilities.isMiddleMouseButton(e)) {
                        // RE-WIRE: 调用我们的 main quick-open logic
                        MinesweeperGame.this.quickOpenAround(c, r); // (c, r) -> (x, y)
                    }
                }
            });
        }

        /**
         * (修复 4 & 2) 关键：同步方法
         * 根据后端的 byte value 更新这个 JButton 的外观
         */
        void syncAppearance(byte value, boolean isDead, boolean isMine) {
            // value 是来自 gameState.grid:
            // 0-8: Number (已揭开)
            // -1: Flag
            // -2: Hidden
            // 64: Revealed Mine (未击中)
            // 65: Hit Mine (击中)

            setIcon(null);
            setText("");
            setForeground(Color.BLACK); // 重置
            setBackground(Color.LIGHT_GRAY); // (修复 2) 揭开的格子底色

            // (修复 4) 必须优先处理 0-8
            if (value >= 0 && value <= 8) {
                setBorder(BORDER_LOWERED); // (修复 2) 凹陷
                if (value > 0) {
                    setText(String.valueOf(value));
                    setForeground(NUM_COLORS[value - 1]);
                } else {
                    setText(""); // 0
                }
            } else if (value == -1) { // 旗帜
                setIcon(FLAG_ICON);
                setBorder(BORDER_RAISED); // (修复 2) 凸起
                setBackground(null); // 恢复默认灰色
            } else if (value == -2) { // 隐藏
                setBorder(BORDER_RAISED); // (修复 2) 凸起
                setBackground(null); // 恢复默认灰色
            } else if (value == 64) { // 游戏结束时揭开的雷
                setIcon(MINE_ICON);
                setBorder(BORDER_LOWERED); // (修复 2) 凹陷
            } else if (value == 65) { // 玩家踩到的雷
                setIcon(MINE_ICON);
                setBackground(Color.RED); // C: COL_BANG
                setBorder(BORDER_LOWERED); // (修复 2) 凹陷
            }

            // 检查 (来自 UI 参考文件): 游戏结束时标错的旗帜
            // C 代码 (66)
            if (isDead && value == -1 && !isMine) {
                setIcon(null);
                setText("X");
                setForeground(Color.RED);
                setBorder(BORDER_LOWERED); // (修复 2) 凹陷
            }
        }
    }

    // =================================================================
    // (图标类: SmileyIcon, FlagIcon, MineIcon) - (来自 UI 参考文件)
    // =================================================================
    private class SmileyIcon implements Icon {
        private final int size;
        private int state = SMILEY_STATE_NORMAL;
        SmileyIcon(int size) { this.size = size; }
        public void setState(int state) { this.state = state; }
        public int getState() { return state; }
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.YELLOW);
            g2.fillOval(x, y, size, size);
            g2.setColor(Color.BLACK);
            g2.drawOval(x, y, size, size);
            int eyeSize = size / 6;
            int eyeY = y + size / 3;
            int eyeLX = x + size / 3 - eyeSize / 2;
            int eyeRX = x + size * 2 / 3 - eyeSize / 2;
            switch (state) {
                case SMILEY_STATE_NORMAL:
                    g2.fillOval(eyeLX, eyeY, eyeSize, eyeSize);
                    g2.fillOval(eyeRX, eyeY, eyeSize, eyeSize);
                    g2.drawArc(x + size / 4, y + size / 3, size / 2, size / 3, 225, 90);
                    break;
                case SMILEY_STATE_WIN:
                    // 绘制开心的眼睛
                    g2.fillOval(eyeLX, eyeY, eyeSize, eyeSize);
                    g2.fillOval(eyeRX, eyeY, eyeSize, eyeSize);

                    // 绘制胜利的星星或王冠
                    g2.setColor(Color.YELLOW);
                    drawStar(g2, x + size/2, y + size/4, size/6); // 在头顶画个小星星

                    // 绘制开心的大笑嘴巴
                    g2.setColor(Color.BLACK);
                    g2.drawArc(x + size/4, y + size/2, size/2, size/4, 0, -180);

                    // 可选：添加腮红让表情更可爱
                    g2.setColor(new Color(255, 150, 150, 150)); // 半透明粉色
                    g2.fillOval(x + size/6, y + size/2, size/8, size/8);
                    g2.fillOval(x + size - size/6 - size/8, y + size/2, size/8, size/8);
                    break;
                case SMILEY_STATE_LOSE:
                    g2.setStroke(new BasicStroke(2));
                    g2.drawLine(eyeLX, eyeY, eyeLX + eyeSize, eyeY + eyeSize);
                    g2.drawLine(eyeLX + eyeSize, eyeY, eyeLX, eyeY + eyeSize);
                    g2.drawLine(eyeRX, eyeY, eyeRX + eyeSize, eyeY + eyeSize);
                    g2.drawLine(eyeRX + eyeSize, eyeY, eyeRX, eyeY + eyeSize);
                    g2.drawArc(x + size / 4, y + size * 2 / 3, size / 2, size / 3, 45, 90);
                    break;
                case SMILEY_STATE_CLICK:
                    g2.fillOval(eyeLX, eyeY, eyeSize, eyeSize);
                    g2.fillOval(eyeRX, eyeY, eyeSize, eyeSize);
                    g2.fillOval(x + size / 2 - eyeSize, y + size * 2 / 3 - eyeSize / 2, eyeSize, eyeSize);
                    break;
            }
            g2.dispose();
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    private void drawStar(Graphics2D g2, int centerX, int centerY, int radius) {
        int points = 5;
        int[] xPoints = new int[points * 2];
        int[] yPoints = new int[points * 2];

        for (int i = 0; i < points * 2; i++) {
            double angle = Math.PI * i / points;
            int r = (i % 2 == 0) ? radius : radius / 2;
            xPoints[i] = centerX + (int) (r * Math.sin(angle));
            yPoints[i] = centerY - (int) (r * Math.cos(angle));
        }

        g2.fillPolygon(xPoints, yPoints, points * 2);
    }
    private static class FlagIcon implements Icon {
        private final int size = 20; // 图标大小
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            // (居中绘制)
            int xOff = (c.getWidth() - size) / 2;
            int yOff = (c.getHeight() - size) / 2;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(Color.BLACK);
            // 旗杆
            g2.fillRect(xOff + size / 2 - 1, yOff + 3, 2, size - 6);
            // 底座
            g2.fillRect(xOff + size / 4, yOff + size - 6, size / 2, 3);
            // 旗帜
            g2.setColor(Color.RED);
            Polygon flag = new Polygon();
            flag.addPoint(xOff + size / 2 + 1, yOff + 3);
            flag.addPoint(xOff + size / 2 + 1, yOff + size / 2);
            flag.addPoint(xOff + size / 4, yOff + size / 2 - 3);
            g2.fill(flag);
            g2.dispose();
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    private static class MineIcon implements Icon {
        private final int size = 20; // 图标大小
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            // (居中绘制)
            int xOff = (c.getWidth() - size) / 2;
            int yOff = (c.getHeight() - size) / 2;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.BLACK);
            g2.fillOval(xOff + 4, yOff + 4, size - 8, size - 8);
            g2.setStroke(new BasicStroke(2));
            int cx = xOff + size / 2;
            int cy = yOff + size / 2;
            g2.drawLine(cx, yOff + 2, cx, yOff + size - 2);
            g2.drawLine(xOff + 2, cy, xOff + size - 2, cy);
            g2.drawLine(xOff + 4, yOff + 4, xOff + size - 4, yOff + size - 4);
            g2.drawLine(xOff + size - 4, yOff + 4, xOff + 4, yOff + size - 4);
            g2.dispose();
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    /** (新增的辅助方法)
     * 检查一个坐标 (x, y) 是否与一个已经揭开的数字(0-8)相邻。
     */
    private boolean isAdjacentToRevealed(int x, int y) {
        if (gameState == null) return false;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue; // 跳过格子本身

                int nx = x + dx;
                int ny = y + dy;

                // 检查边界
                if (nx >= 0 && nx < gameWidth && ny >= 0 && ny < gameHeight) {
                    // 检查邻居是否是一个已揭开的数字 (0-8)
                    if (gameState.grid[ny * gameWidth + nx] >= 0) {
                        return true;
                    }
                }
            }
        }
        return false; // 没有邻居是已揭开的数字
    }

    // --- main 方法 (来自原文件) ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                try {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            MinesweeperGame game = new MinesweeperGame();
            game.setVisible(true);
        });
    }



}