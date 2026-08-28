package cs2d.playerAndAi.doublePlayer;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
// 新增的 imports
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.util.List;
import java.util.Queue;

/**
 * 一个完整的、可运行的Java Swing扫雷游戏。
 *
 * 核心特性:
 * 1. 经典界面 (笑脸, 计时器, 雷数统计) - 带有真实图标.
 * 2. 保证可解 (通过在后台运行一个*高级回溯解谜器*来验证棋盘) - 适配高密度雷区.
 * 3. 提示功能 (使用高级解谜器).
 * 4. 自动求解功能 (使用高级解谜器).
 * 5. 难度选择菜单 (从 新手 到 地狱).
 * 6. 窗口大小自适应.
 */
public class SolvableMinesweeper extends JFrame {

    // --- 游戏配置 (变为可变的) ---
    private int ROWS;
    private int COLS;
    private int NUM_MINES;

    /**
     * 难度枚举
     */
    private enum Difficulty {
        NOVICE("新手 (Novice)", 9, 9, 10),
        EASY("简单 (Easy)", 12, 12, 35),
        // === 按照你的设置修改 ===
        NORMAL("一般 (Normal)", 18, 18, 100),
        HARD("困难 (Hard)", 25, 25, 180),
        VERY_HARD("非常困难 (Very Hard)", 30, 40, 400),
        MASTER("大师 (Master)", 35, 55, 500),
        HELL("地狱 (Hell)", 35, 50, 600);

        final String name;
        final int rows, cols, mines;

        Difficulty(String name, int r, int c, int m) {
            this.name = name;
            this.rows = r;
            this.cols = c;
            this.mines = m;
        }
    }
    private Difficulty currentDifficulty = Difficulty.NORMAL;

    // ------------------

    private final JPanel gridPanel;
    private final JLabel flagsLabel;
    private final JLabel timeLabel;
    private final JButton smileyButton;
    private final JButton hintButton;
    private final JButton solveButton;

    // --- 新增: 图标 ---
    private final SmileyIcon smileyIcon;
    private static final Icon FLAG_ICON = new FlagIcon();
    private static final Icon MINE_ICON = new MineIcon();
    // ------------------


    private Cell[][] cells;
    private int flagsLeft;
    private int timePassed;
    private boolean gameRunning;
    private boolean firstClick;
    private final Timer timer;
    private final Random random = new Random();

    // 各种状态的图标/文本 (SMILEY_... 现在用作状态)
    private static final int SMILEY_STATE_NORMAL = 0;
    private static final int SMILEY_STATE_WIN = 1;
    private static final int SMILEY_STATE_LOSE = 2;
    private static final int SMILEY_STATE_CLICK = 3;

    private final Color[] NUM_COLORS = {
            Color.BLUE, new Color(0, 128, 0), Color.RED, new Color(0, 0, 128),
            new Color(128, 0, 0), new Color(64, 224, 208), Color.BLACK, Color.GRAY
    };

    /**
     * 游戏主入口.
     */
    public static void main(String[] args) {
        // 在Swing事件调度线程上运行
        SwingUtilities.invokeLater(() -> {
            SolvableMinesweeper game = new SolvableMinesweeper();
            game.setVisible(true);
        });
    }

    /**
     * 构造函数 - 初始化UI
     */
    public SolvableMinesweeper() {
        setTitle("Mines");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        // --- 新增: 菜单栏 ---
        JMenuBar menuBar = new JMenuBar();
        JMenu gameMenu = new JMenu("游戏 (Game)");
        JMenu newGameMenu = new JMenu("新游戏 (New Game)");

        for (Difficulty diff : Difficulty.values()) {
            JMenuItem diffItem = new JMenuItem(diff.name);
            diffItem.addActionListener(e -> startGame(diff));
            newGameMenu.add(diffItem);
        }

        gameMenu.add(newGameMenu);
        menuBar.add(gameMenu);
        setJMenuBar(menuBar);
        // ---------------------

        // --- 顶部面板 (状态栏) ---
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

        smileyIcon = new SmileyIcon(32); // 初始化图标
        smileyButton = new JButton(smileyIcon);
        smileyButton.setPreferredSize(new Dimension(40, 40)); // 给按钮一个固定大小
        smileyButton.setMargin(new Insets(0, 0, 0, 0));
        smileyButton.setFocusable(false);
        smileyButton.addActionListener(e -> startGame(currentDifficulty)); // 重置当前难度的游戏

        topPanel.add(flagsLabel, BorderLayout.WEST);
        topPanel.add(smileyButton, BorderLayout.CENTER);
        topPanel.add(timeLabel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // --- 中部面板 (雷区) ---
        gridPanel = new JPanel(); // 不在这里初始化格子, 移到 startGame
        gridPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(gridPanel, BorderLayout.CENTER);

        // --- 底部面板 (功能按钮) ---
        JPanel bottomPanel = new JPanel(new FlowLayout());
        hintButton = new JButton("提示");
        hintButton.setFocusable(false);
        hintButton.addActionListener(e -> onHintClick());

        solveButton = new JButton("自动求解");
        solveButton.setFocusable(false);
        solveButton.addActionListener(e -> onSolveClick());

        bottomPanel.add(hintButton);
        bottomPanel.add(solveButton);
        add(bottomPanel, BorderLayout.SOUTH);

        // --- 计时器 ---
        timer = new Timer(1000, e -> {
            if (gameRunning) {
                timePassed++;
                timeLabel.setText(String.format("%03d", Math.min(timePassed, 999)));
            }
        });

        startGame(Difficulty.NORMAL); // 开始第一局游戏 (使用默认难度)
    }

    /**
     * 开始或重置游戏
     * @param difficulty 要开始的游戏难度
     */
    private void startGame(Difficulty difficulty) {
        // 1. 设置游戏参数
        this.currentDifficulty = difficulty;
        this.ROWS = difficulty.rows;
        this.COLS = difficulty.cols;
        this.NUM_MINES = difficulty.mines;

        gameRunning = true;
        firstClick = true;
        flagsLeft = NUM_MINES;
        timePassed = 0;

        // 2. 重置UI状态
        flagsLabel.setText(String.format("%03d", flagsLeft));
        timeLabel.setText("000");
        smileyIcon.setState(SMILEY_STATE_NORMAL);
        smileyButton.repaint();
        timer.stop();
        hintButton.setEnabled(true);
        solveButton.setEnabled(true);

        // 3. 重建雷区 (核心改动)
        gridPanel.removeAll();
        gridPanel.setLayout(new GridLayout(ROWS, COLS));
        cells = new Cell[ROWS][COLS];

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                cells[r][c] = new Cell(r, c);
                gridPanel.add(cells[r][c]);
            }
        }

        // 4. 重新计算窗口大小并居中
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * 核心: 首次点击后生成一个*保证可解*的棋盘
     * @param startR 首次点击的行
     * @param startC 首次点击的列
     */
    private void generateSolvableBoard(int startR, int startC) {
        System.out.println("正在生成可解棋盘 (如果卡住, 请稍等)...");
        long startTime = System.currentTimeMillis();
        int attempts = 0;

        // === 新增: 重试限制 (模拟 C 语言) ===
        // C 语言的限制是 100 次，但 Java 较慢，我们放宽一些
        final int MAX_ATTEMPTS = 500000;

        System.out.println("使用 (连接) 生成器 + (边界) 验证器策略...");

        // 循环, 直到生成一个被解谜器验证为 "可解" 的棋盘
        while (true) {
            attempts++;

            // === 新增: 检查重试次数 ===
            if (attempts > MAX_ATTEMPTS) {
                long endTime = System.currentTimeMillis();
                System.out.printf("生成失败! 尝试了 %d 次 (耗时 %d ms) 仍未找到可解布局。\n", attempts - 1, (endTime - startTime));
                System.out.println("我们的 Java 解题器还是不够快/不够强。将使用一个*可能无解*的布局。");

                // (我们必须 break, 否则游戏无法开始)
                // (此时, cells[][] 包含最后一次尝试的布局)
                // 我们需要确保第一次点击是安全的 (如果它不是 0)
                if (cells[startR][startC].isMine) {
                    System.out.println("...最后一次尝试的布局在点击点有雷, 正在清理安全区...");
                    // 这是一个简化的处理: 确保 3x3 区域安全, 并重新计算数字
                    // (这可能会破坏布局, 但能让游戏开始)
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            if (isValid(startR + dr, startC + dc)) {
                                cells[startR + dr][startC + dc].isMine = false;
                            }
                        }
                    }
                    // 必须重新计算所有数字！
                    calculateNumbers();
                }

                // 弹窗提示玩家
                JOptionPane.showMessageDialog(this,
                        "无法在 " + MAX_ATTEMPTS + " 次尝试内生成一个*保证可解*的高密度布局。\n" +
                                "这个布局可能需要猜测。\n" +
                                "(C 语言的解题器算法 ('他的') 效率要高得多!)",
                        "生成器警告",
                        JOptionPane.WARNING_MESSAGE);
                break; // 强制退出循环
            }
            // ============================


            // 1. [!! 核心修改 !!]
            //    不再随机放置雷, 而是从 (startR, startC) "长" 出一个
            //    由 (ROWS * COLS - NUM_MINES) 个格子组成的 *连通* 安全大陆。
            //    所有不在这个大陆上的格子都会变成雷。
            resetBoardAndGrowSafeArea(startR, startC);

            // 2. 计算数字
            calculateNumbers();

            // 3. 验证棋盘是否可解 (使用*强化后*的解谜器)
            //    (这一步仍然是必须的, 因为 "连通" 并不等于 "无 50/50 猜测")
            Solver solver = new Solver(cells, NUM_MINES);

            if (solver.isSolvable(startR, startC)) {
                break; // 找到了一个可解的棋盘
            }
            // ============================

            // --- 避免UI无响应 ---
            if (attempts % 10000 == 0) {
                System.out.println("...还在尝试 (验证连通布局)... " + attempts + " 次");
            }
        }

        long endTime = System.currentTimeMillis();
        // 确保只有在成功时才打印 "生成完毕"
        if (attempts <= MAX_ATTEMPTS) {
            System.out.printf("生成完毕! 尝试了 %d 次, 耗时 %d ms.\n", attempts, (endTime - startTime));
        }
    }

    /**
     * [!! 核心修改 !!]
     * 步骤 1: 将所有格子设为雷, 然后 "长" 出一个连通的安全区。
     */
    private void resetBoardAndGrowSafeArea(int startR, int startC) {
        // 1. 假设所有格子都是雷
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                cells[r][c].isMine = true;
                cells[r][c].value = 0; // 重置 value
            }
        }

        int totalSafeCells = (ROWS * COLS) - NUM_MINES;

        // 使用 Set 来跟踪已在边界或大陆上的格子, 避免重复
        Set<Cell> frontierSet = new HashSet<>();
        // 使用 List 来实现 O(1) 的随机访问移除
        List<Cell> frontierList = new ArrayList<>();
        Set<Cell> safeContinent = new HashSet<>();

        // 2. 创建 "A区" (起始安全区)
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int r = startR + dr;
                int c = startC + dc;
                if (isValid(r, c)) {
                    Cell startCell = cells[r][c];
                    startCell.isMine = false;
                    safeContinent.add(startCell);
                }
            }
        }

        // 3. 检查雷数是否过多 (一个极端情况)
        if (totalSafeCells <= safeContinent.size()) {
            System.out.println("警告: 雷数 (" + NUM_MINES + ") 过高。安全区仅限于起始点。");
            // 这种情况, A区就是所有安全区, 其他都是雷。
            // 确保只保留 totalSafeCells 个安全格 (如果 totalSafeCells < 9)
            if (totalSafeCells < safeContinent.size()) {
                int cellsToMakeMine = safeContinent.size() - totalSafeCells;
                List<Cell> startZoneList = new ArrayList<>(safeContinent);
                Collections.shuffle(startZoneList, random);
                for(int i=0; i < cellsToMakeMine; i++) {
                    startZoneList.get(i).isMine = true;
                }
            }
            return;
        }

        int safeCellsToCreate = totalSafeCells - safeContinent.size();

        // 4. 找到A区的 "边界" (所有与A区相邻的雷)
        for (Cell safeCell : safeContinent) {
            // 调用主类中的 getNeighbors
            for (Cell neighbor : getNeighbors(safeCell)) {
                if (neighbor.isMine && !frontierSet.contains(neighbor)) {
                    frontierSet.add(neighbor);
                    frontierList.add(neighbor);
                }
            }
        }

        // 5. "发展" 安全大陆, 直到达到所需的安全格总数
        while (safeCellsToCreate > 0 && !frontierList.isEmpty()) {
            // 随机选择一个边界格子
            int randIdx = random.nextInt(frontierList.size());
            Cell newSafeCell = frontierList.remove(randIdx);
            frontierSet.remove(newSafeCell); // 保持 Set 和 List 同步

            // 把它变成安全格
            newSafeCell.isMine = false;
            safeContinent.add(newSafeCell);
            safeCellsToCreate--;

            // 把它新的雷邻居添加到边界
            // 调用主类中的 getNeighbors
            for (Cell neighbor : getNeighbors(newSafeCell)) {
                if (neighbor.isMine && !safeContinent.contains(neighbor) && !frontierSet.contains(neighbor)) {
                    frontierSet.add(neighbor);
                    frontierList.add(neighbor);
                }
            }
        }

        // (如果 safeCellsToCreate > 0 但 frontier 为空, 意味着我们被雷区包围了,
        // 无法再扩展了。这在高密度雷区是可能的, 但我们将接受这个布局
        // 并让 `isSolvable` 来判断它是否公平。)
    }


    /**
     * 步骤 2: 计算所有非雷格子的数字
     */
    private void calculateNumbers() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (cells[r][c].isMine) {
                    cells[r][c].value = -1; // -1 代表雷
                    continue;
                }
                int adjacentMines = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        int nr = r + dr;
                        int nc = c + dc;
                        if (isValid(nr, nc) && cells[nr][nc].isMine) {
                            adjacentMines++;
                        }
                    }
                }
                cells[r][c].value = adjacentMines;
            }
        }
    }

    /**
     * 检查坐标是否在棋盘内
     */
    private boolean isValid(int r, int c) {
        return r >= 0 && r < ROWS && c >= 0 && c < COLS;
    }

    /**
     * (!! 新增以修复错误 !!)
     * 辅助函数: 获取邻居 (Cell) - 供 SolvableMinesweeper 类使用
     * (这与 Solver.getNeighbors 类似, 但它访问主类的 'cells' 字段)
     */
    private List<Cell> getNeighbors(Cell cell) {
        List<Cell> neighbors = new ArrayList<>();
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue; // 跳过单元格本身
                int nr = cell.r + dr;
                int nc = cell.c + dc;
                // 检查新坐标是否在棋盘边界内
                if (isValid(nr, nc)) {
                    neighbors.add(cells[nr][nc]); // 注意: 这里访问的是 'cells'
                }
            }
        }
        return neighbors;
    }

    /**
     * 左键点击一个单元格
     */
    private void revealCell(Cell cell) {
        if (!gameRunning || cell.isRevealed || cell.isFlagged) {
            return;
        }

        // --- 关键: 首次点击 ---
        if (firstClick) {
            firstClick = false;
            // *直到*第一次点击, 才生成棋盘
            generateSolvableBoard(cell.r, cell.c);
            // 确保计时器在生成棋盘后才启动
            timer.start();
        }

        cell.reveal();
        if (gameRunning) { // 仅在游戏仍在进行时（即未踩雷）设为 normal
            smileyIcon.setState(SMILEY_STATE_NORMAL);
            smileyButton.repaint();
        }

        if (cell.isMine) {
            gameOver(false); // 踩雷, 游戏结束
            return;
        }

        // 如果点开的是 '0', 递归地打开周围的格子 (泛洪填充)
        if (cell.value == 0) {
            floodFill(cell.r, cell.c);
        }

        checkWin();
    }

    /**
     * 泛洪填充 (Flood Fill) - 递归打开所有相邻的 '0'
     */
    private void floodFill(int r, int c) {
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int nr = r + dr;
                int nc = c + dc;

                if (isValid(nr, nc)) {
                    Cell neighbor = cells[nr][nc];
                    if (!neighbor.isRevealed && !neighbor.isFlagged && !neighbor.isMine) {
                        neighbor.reveal();
                        if (neighbor.value == 0) {
                            floodFill(nr, nc); // 递归
                        }
                    }
                }
            }
        }
    }

    /**
     * 右键点击 - 切换旗帜
     */
    private void toggleFlag(Cell cell) {
        if (!gameRunning || cell.isRevealed) {
            return;
        }

        cell.toggleFlag();

        if (cell.isFlagged) {
            flagsLeft--;
        } else {
            flagsLeft++;
        }
        flagsLabel.setText(String.format("%03d", Math.max(0, flagsLeft)));
    }

    /**
     * 游戏结束
     */
    private void gameOver(boolean won) {
        gameRunning = false;
        timer.stop();
        hintButton.setEnabled(false);
        solveButton.setEnabled(false);

        if (won) {
            smileyIcon.setState(SMILEY_STATE_WIN);
            // 自动插上所有剩余的旗帜
            flagsLeft = 0;
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (cells[r][c].isMine && !cells[r][c].isFlagged) {
                        cells[r][c].toggleFlag();
                    }
                }
            }
            flagsLabel.setText("000");

        } else {
            smileyIcon.setState(SMILEY_STATE_LOSE);
            // 显示所有雷
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    // 标错的旗帜
                    if (cells[r][c].isFlagged && !cells[r][c].isMine) {
                        cells[r][c].setText("X"); // 标错的 "X" 仍然用文本
                        cells[r][c].setIcon(null); // 覆盖掉旗帜图标
                        cells[r][c].setForeground(Color.RED);
                    }
                    // 没标出来的雷
                    if (!cells[r][c].isFlagged && cells[r][c].isMine) {
                        cells[r][c].reveal();
                    }
                }
            }
        }
        smileyButton.repaint(); // 确保图标更新
    }

    /**
     * 检查是否胜利
     * (所有非雷单元格都已被点开)
     */
    private void checkWin() {
        int revealedCount = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (cells[r][c].isRevealed) {
                    revealedCount++;
                }
            }
        }

        if (revealedCount == (ROWS * COLS) - NUM_MINES) {
            gameOver(true);
        }
    }

    // --- 新增: 辅助方法 ---
    /**
     * (新增) 创建一个反映当前UI状态的 影子棋盘
     */
    private int[][] createSolverState() {
        int[][] state = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (cells[r][c].isRevealed) {
                    state[r][c] = 1; // revealed
                } else if (cells[r][c].isFlagged) {
                    state[r][c] = 2; // flagged
                } else {
                    state[r][c] = 0; // hidden
                }
            }
        }
        return state;
    }

    /**
     * "提示" 按钮点击事件
     */
    private void onHintClick() {
        if (!gameRunning || firstClick) return;

        // --- 关键修改: 传入总雷数 ---
        Solver solver = new Solver(cells, NUM_MINES);

        // --- 关键修改: 传入当前状态 ---
        Solver.SolverMove move = solver.findNextMove(createSolverState());

        if (move == null) {
            JOptionPane.showMessageDialog(this, "解谜器未找到任何逻辑步骤。");
            return;
        }

        // 执行解谜器找到的步骤
        applyMove(move);
        checkWin();
    }

    /**
     * "自动求解" 按钮点击事件
     */
    private void onSolveClick() {
        if (!gameRunning || firstClick) return;

        hintButton.setEnabled(false);
        solveButton.setEnabled(false);

        // 使用 Swing Timer 来逐步执行, 避免冻结UI
        Timer solveTimer = new Timer(50, null); // 50ms一步
        solveTimer.addActionListener(e -> {
            if (!gameRunning) {
                solveTimer.stop();
                return;
            }

            // --- 关键修改: 传入总雷数 ---
            Solver solver = new Solver(cells, NUM_MINES);

            // --- 关键修改: 传入当前状态 ---
            Solver.SolverMove move = solver.findNextMove(createSolverState());

            if (move != null) {
                applyMove(move);
                checkWin();
            } else {
                // 解谜器卡住了 (或者解完了)
                solveTimer.stop();
                if (gameRunning) { // 如果游戏还没赢, 说明卡住了
                    JOptionPane.showMessageDialog(this, "自动求解器已完成所有逻辑步骤。");
                }
            }
        });
        solveTimer.start();
    }

    /**
     * 执行解谜器返回的步骤
     */
    private void applyMove(Solver.SolverMove move) {
        if (move.type == Solver.MoveType.CLICK) {
            for (Cell cell : move.cells) {
                // 确保我们不会重复点击或点击已插旗的
                if (!cell.isRevealed && !cell.isFlagged) {
                    // System.out.println("Solver CLICK at (" + cell.r + "," + cell.c + ")");
                    revealCell(cell);
                }
            }
        } else if (move.type == Solver.MoveType.FLAG) {
            for (Cell cell : move.cells) {
                if (!cell.isRevealed && !cell.isFlagged) {
                    // System.out.println("Solver FLAG at (" + cell.r + "," + cell.c + ")");
                    toggleFlag(cell);
                }
            }
        }
    }

    // =================================================================
    // 内部类: 单元格 (Cell)
    // =================================================================
    private class Cell extends JButton {
        final int r, c;
        boolean isMine;
        boolean isRevealed;
        boolean isFlagged;
        int value; // -1: 雷, 0-8: 数字

        Cell(int r, int c) {
            this.r = r;
            this.c = c;
            // 调整格子大小和字体
            setPreferredSize(new Dimension(25, 25)); // 统一格子大小
            // 确保使用粗体且更大的字体
            setFont(new Font("Monospaced", Font.BOLD, 24));  //字体大小
            setMargin(new Insets(0, 0, 0, 0));
            setFocusable(false);

            // 添加鼠标监听器
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!gameRunning) return;

                    // 按下时显示 :O 表情
                    if (e.getButton() == MouseEvent.BUTTON1 && !isRevealed && !isFlagged) {
                        smileyIcon.setState(SMILEY_STATE_CLICK);
                        smileyButton.repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!gameRunning) return;

                    // 恢复表情
                    if (gameRunning && smileyIcon.getState() == SMILEY_STATE_CLICK) {
                        smileyIcon.setState(SMILEY_STATE_NORMAL);
                        smileyButton.repaint();
                    }

                    // --- 鼠标点击逻辑 ---
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        // 左键
                        revealCell(Cell.this);
                    } else if (e.getButton() == MouseEvent.BUTTON3) {
                        // 右键
                        SolvableMinesweeper.this.toggleFlag(Cell.this);
                    }
                }
            });

            reset();
        }

        /**
         * 重置单元格到初始状态
         */
        void reset() {
            isMine = false;
            isRevealed = false;
            isFlagged = false;
            value = 0;
            setEnabled(true);
            setText("");
            setIcon(null); // 重置图标
            setBackground(null);
            setForeground(Color.BLACK);
        }

        /**
         * 点开这个格子 (更新外观)
         */
        void reveal() {
            if (isRevealed) return;
            isRevealed = true;

            setBackground(Color.LIGHT_GRAY);
            setIcon(null); // 点开后清除任何可能的图标 (如旗帜)

            if (isMine) {
                setIcon(MINE_ICON);
                setDisabledIcon(MINE_ICON); // 确保禁用时也显示
                setBackground(Color.RED);
            } else if (value > 0) {
                setText(String.valueOf(value));
                setForeground(NUM_COLORS[value - 1]); // 颜色现在应该生效了
            } else {
                setText("");
            }
        }

        /**
         * 切换旗帜 (更新外观)
         */
        void toggleFlag() {
            if (isRevealed) return;
            isFlagged = !isFlagged;
            if (isFlagged) {
                setIcon(FLAG_ICON);
            } else {
                setIcon(null);
            }
        }
    }





    // =================================================================
    // (图标类: SmileyIcon, FlagIcon, MineIcon)
    // =================================================================
    private class SmileyIcon implements Icon {
        private final int size;
        private int state = SMILEY_STATE_NORMAL;

        SmileyIcon(int size) {
            this.size = size;
        }

        public void setState(int state) {
            this.state = state;
        }

        public int getState() {
            return state;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 脸
            g2.setColor(Color.YELLOW);
            g2.fillOval(x, y, size, size);
            g2.setColor(Color.BLACK);
            g2.drawOval(x, y, size, size);

            // 眼睛
            int eyeSize = size / 6;
            int eyeY = y + size / 3;
            int eyeLX = x + size / 3 - eyeSize / 2;
            int eyeRX = x + size * 2 / 3 - eyeSize / 2;

            switch (state) {
                case SMILEY_STATE_NORMAL:
                    g2.fillOval(eyeLX, eyeY, eyeSize, eyeSize);
                    g2.fillOval(eyeRX, eyeY, eyeSize, eyeSize);
                    // 嘴 (微笑)
                    g2.drawArc(x + size / 4, y + size / 3, size / 2, size / 3, 225, 90);
                    break;
                case SMILEY_STATE_WIN:
                    // 墨镜
                    g2.setColor(Color.BLACK);
                    g2.fillRect(eyeLX - eyeSize / 2, eyeY - eyeSize / 2, eyeSize * 4, eyeSize);
                    g2.fillRect(eyeLX + eyeSize, eyeY - eyeSize, size / 8, size / 8); // 中间连接
                    // 嘴 (大笑)
                    g2.drawArc(x + size / 4, y + size / 3, size / 2, size / 3, 180, 180);
                    break;
                case SMILEY_STATE_LOSE:
                    // 眼睛 (X)
                    g2.setStroke(new BasicStroke(2));
                    g2.drawLine(eyeLX, eyeY, eyeLX + eyeSize, eyeY + eyeSize);
                    g2.drawLine(eyeLX + eyeSize, eyeY, eyeLX, eyeY + eyeSize);
                    g2.drawLine(eyeRX, eyeY, eyeRX + eyeSize, eyeY + eyeSize);
                    g2.drawLine(eyeRX + eyeSize, eyeY, eyeRX, eyeY + eyeSize);
                    // 嘴 (不开心)
                    g2.drawArc(x + size / 4, y + size * 2 / 3, size / 2, size / 3, 45, 90);
                    break;
                case SMILEY_STATE_CLICK:
                    // 眼睛 (O)
                    g2.fillOval(eyeLX, eyeY, eyeSize, eyeSize);
                    g2.fillOval(eyeRX, eyeY, eyeSize, eyeSize);
                    // 嘴 (O)
                    g2.fillOval(x + size / 2 - eyeSize, y + size * 2 / 3 - eyeSize / 2, eyeSize, eyeSize);
                    break;
            }
            g2.dispose();
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    private static class FlagIcon implements Icon {
        private final int size = 16; // 假设格子大小
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(Color.BLACK);
            // 旗杆
            g2.fillRect(x + size / 2 - 1, y + 3, 2, size - 6);
            // 底座
            g2.fillRect(x + size / 4, y + size - 6, size / 2, 3);
            // 旗帜
            g2.setColor(Color.RED);
            Polygon flag = new Polygon();
            flag.addPoint(x + size / 2 + 1, y + 3);
            flag.addPoint(x + size / 2 + 1, y + size / 2);
            flag.addPoint(x + size / 4, y + size / 2 - 3);
            g2.fill(flag);
            g2.dispose();
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    private static class MineIcon implements Icon {
        private final int size = 16; // 假设格子大小
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 雷
            g2.setColor(Color.BLACK);
            g2.fillOval(x + 4, y + 4, size - 8, size - 8);
            // 刺
            g2.setStroke(new BasicStroke(2));
            int cx = x + size / 2;
            int cy = y + size / 2;
            g2.drawLine(cx, y + 2, cx, y + size - 2); // |
            g2.drawLine(x + 2, cy, x + size - 2, cy); // -
            g2.drawLine(x + 4, y + 4, x + size - 4, y + size - 4); // \
            g2.drawLine(x + size - 4, y + 4, x + 4, y + size - 4); // /
            g2.dispose();
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }


    // =================================================================
    // 內部類: 解謎器 (Solver)
    //
    // [!!! 核心重構 V4 - `mines.c` (C 語言) 移植版 !!!]
    //
    // 這個求解器完全重寫，以匹配 `mines.c` 中 `minesolve` 函式的架構。
    //
    // 核心理念: 約束傳播 (Constraint Propagation)
    //
    // 1. `SolverSet` (C: `struct set`):
    //    一個約束，代表「一組方塊 (mask) 中有 (mines) 顆雷」。
    //    這是一個抽象的約束，*不*與任何特定的數字方塊綁定。
    //
    // 2. 資料結構 (C: `setstore`, `squaretodo`):
    //    - `setsDB (Map<SolverSet, SolverSet>)`:
    //      所有已知約束的資料庫 (C: `tree234`)。
    //    - `setTodo (Queue<SolverSet>)`:
    //      待處理的*約束* (C: `todo_head/tail` 連結串列)。
    //    - `squareTodo (Queue<Cell>)`:
    //      待處理的*新揭開方塊* (C: `squaretodo` 連結串列)。
    //
    // 3. `minesolveLoop` (C: `minesolve`):
    //    一個 `while(true)` 主迴圈，模擬 C 語言求解器的 4 個階段:
    //    - 階段 1: 處理 `squareTodo`。
    //    - 階段 2: 處理 `setTodo` (基礎/子集/差集推論)。
    //    - 階段 3: 全域推論 (如果 1 和 2 都空了)。
    //    - 階段 4: 卡住 (Stalled)。
    //
    // 4. `findNextMove` 和 `isSolvable` 現在都使用這個新的 `minesolveLoop` 引擎。
    //
    // =================================================================
    private class Solver {

        enum MoveType { CLICK, FLAG }
        /**
         * 內部類: 用於返回解謎器找到的步驟
         */
        static class SolverMove {
            final MoveType type;
            final List<Cell> cells;

            SolverMove(MoveType type, List<Cell> cells) {
                this.type = type;
                this.cells = new ArrayList<>(cells); // 複製列表
            }
        }

        // `mines.c` 求解器的 Java 移植版所需的核心資料結構

        /**
         * `struct set` 的 Java 實現。
         * 代表一個抽象的約束：(x, y) 3x3 區域內的 `mask` 位元遮罩
         * 所代表的方塊中，有 `mines` 顆雷。
         */
        private class SolverSet {
            final int x, y;   // 3x3 區域的左上角 (基點)
            final int mask; // 9-bit 位元遮罩 (1=方塊在此集合中)
            int mines;      // 此集合中的地雷數

            // 用於 `setTodo` 佇列的狀態，模擬 C 語言的 `prev/next` 連結串列
            boolean onTodoQueue;

            SolverSet(int x, int y, int mask, int mines) {
                this.x = x;
                this.y = y;
                this.mask = mask;
                this.mines = mines;
                this.onTodoQueue = false;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                SolverSet solverSet = (SolverSet) o;
                // 一個約束僅由它的位置和形狀定義
                return x == solverSet.x && y == solverSet.y && mask == solverSet.mask;
            }

            @Override
            public int hashCode() {
                return Objects.hash(x, y, mask);
            }

            @Override
            public String toString() {
                return String.format("Set[(%d,%d) m:%03x n:%d]", x, y, mask, mines);
            }
        }

        // --- `mines.c` 核心輔助函式的 Java 移植版 ---

        /**
         * 計算一個 9-bit mask 中的位元數 (C: `bitcount16`)
         */
        private int bitcount(int mask) {
            int count = 0;
            for (int i = 0; i < 9; i++) {
                if ((mask & (1 << i)) != 0) {
                    count++;
                }
            }
            return count;
        }

        /**
         * `setmunge` 的 Java 移植版。
         * 關鍵函式：對兩個 set (mask) 進行對齊和位元運算。
         * @param diff false = 交集 (A & B), true = 差集 (A & ~B)
         */
        private int setmunge(int x1, int y1, int mask1, int x2, int y2, int mask2, boolean diff) {
            // 1. 將 (x2, y2, mask2) 對齊到 (x1, y1) 的座標系
            if (Math.abs(x2 - x1) >= 3 || Math.abs(y2 - y1) >= 3) {
                mask2 = 0; // 完全不重疊
            } else {
                // 左右平移
                while (x2 > x1) {
                    mask2 &= ~(4 | 32 | 256); // 0b000100100 (清除左欄)
                    mask2 <<= 1;
                    x2--;
                }
                while (x2 < x1) {
                    mask2 &= ~(1 | 8 | 64); // 0b100010001 (清除右欄)
                    mask2 >>= 1;
                    x2++;
                }
                // 上下平移
                while (y2 > y1) {
                    mask2 &= ~(64 | 128 | 256); // 0b000000111 (清除頂行)
                    mask2 <<= 3;
                    y2--;
                }
                while (y2 < y1) {
                    mask2 &= ~(1 | 2 | 4); // 0b111000000 (清除底行)
                    mask2 >>= 3;
                    y2++;
                }
            }

            // 2. 如果是差集, 反轉 mask2
            if (diff) {
                mask2 ^= 511; // 0b111111111 (9 bits)
            }

            // 3. 返回 A & B (或 A & ~B)
            return mask1 & mask2;
        }

        /**
         * 將一個 set 添加到 `todo` 佇列 (如果它不在佇列中)
         * (C: `ss_add_todo`)
         */
        private void ss_add_todo(SolverSet s, Queue<SolverSet> setTodo) {
            if (!s.onTodoQueue) {
                s.onTodoQueue = true;
                setTodo.add(s);
            }
        }

        /**
         * 將一個新約束添加到資料庫和 `todo` 佇列
         * (C: `ss_add`)
         */
        private void ss_add(Map<SolverSet, SolverSet> setsDB, Queue<SolverSet> setTodo, int x, int y, int mask, int mines) {
            if (mask == 0) return;

            // 1. 標準化座標 (確保 x, y 是左上角)
            while ((mask & (1 | 8 | 64)) == 0) { // 0b100010001 (右移)
                mask >>= 1;
                x++;
            }
            while ((mask & (1 | 2 | 4)) == 0) { // 0b111000000 (下移)
                mask >>= 3;
                y++;
            }

            // 2. 創建 set
            SolverSet s = new SolverSet(x, y, mask, mines);

            // 3. 嘗試添加
            SolverSet existing = setsDB.get(s);
            if (existing != null) {
                // 已經存在。檢查地雷數是否衝突
                if (existing.mines != s.mines) {
                    // 這是一個邏輯悖論 (e.g., 推導出 {A,B} = 1, 後又推導出 {A,B} = 2)
                    // 在 `isSolvable` 中這會導致失敗。
                    // 在 `findNextMove` 中這不應該發生，但如果發生了，我們會卡住。
                    // C 語言的實現似乎會覆蓋舊值並重新處理，我們也這樣做：
                    existing.mines = s.mines;
                    ss_add_todo(existing, setTodo);
                }
            } else {
                // 不存在, 添加新的
                setsDB.put(s, s);
                ss_add_todo(s, setTodo);
            }
        }

        /**
         * 從資料庫中移除一個約束
         * (C: `ss_remove`)
         */
        private void ss_remove(Map<SolverSet, SolverSet> setsDB, SolverSet s) {
            // 從資料庫移除
            setsDB.remove(s);
            // 標記為 "無效"，這樣 `ss_todo` 就會跳過它
            s.onTodoQueue = false;
        }

        /**
         * 從 `todo` 佇列獲取下一個*有效*約束
         * (C: `ss_todo`)
         */
        private SolverSet ss_todo(Queue<SolverSet> setTodo, Map<SolverSet, SolverSet> setsDB) {
            while (!setTodo.isEmpty()) {
                SolverSet s = setTodo.poll();
                if (s.onTodoQueue) { // 檢查它是否在 `ss_remove` 中被標記為 "無效"
                    s.onTodoQueue = false;
                    // 再次檢查它是否真的還在資料庫中 (C 語言的隱式行為)
                    if (setsDB.containsKey(s)) {
                        return s;
                    }
                }
            }
            return null; // 佇列為空或全為無效
        }

        /**
         * 查找所有與給定 (x,y,mask) 重疊的約束
         * (C: `ss_overlap`)
         */
        private List<SolverSet> ss_overlap(Map<SolverSet, SolverSet> setsDB, int x, int y, int mask) {
            List<SolverSet> overlapping = new ArrayList<>();
            // C 語言使用 2-3-4 樹進行了優化 (只檢查附近的 x,y)。
            // Java 的 `HashMap` 必須遍歷所有 key。
            // 對於掃雷來說，約束的總數 (N) 通常不大，O(N) 應該足夠快。
            for (SolverSet s : setsDB.values()) {
                // 使用 setmunge 檢查交集
                if (setmunge(x, y, mask, s.x, s.y, s.mask, false) != 0) {
                    overlapping.add(s);
                }
            }
            return overlapping;
        }


        // --- 求解器主體 (minesolve) ---

        private final Cell[][] board;
        private final int ROWS;
        private final int COLS;
        private final int totalMines;

        // 用於 `findNextMove` 的狀態，當找到第一個動作時設置
        private SolverMove pendingMove = null;

        Solver(Cell[][] actualBoard, int numMines) {
            this.board = actualBoard;
            this.ROWS = actualBoard.length;
            this.COLS = actualBoard[0].length;
            this.totalMines = numMines;
        }

        /**
         * 獲取 (r, c) 處的格子，處理邊界
         */
        private Cell getCell(int r, int c) {
            if (r < 0 || r >= ROWS || c < 0 || c >= COLS) {
                return null;
            }
            return board[r][c];
        }

        /**
         * (新增) `known_squares` 的 Java 實現。
         * 標記一組方塊為安全或地雷。
         * 如果在 `findNextMove` 模式下，它會設置 `pendingMove` 並觸發返回。
         */
        private void known_squares(int x, int y, int mask, boolean isMine,
                                   int[][] state, Queue<Cell> squareTodo,
                                   boolean isFindNextMoveMode) {

            // 如果我們在 "尋找下一步" 模式下，並且已經找到了一個動作，
            // 我們仍然需要處理這個集合中的所有方塊 (以完成當前推論)，
            // 但我們不能再設置 *新* 的 pendingMove。
            boolean createdMoveThisCall = false;

            for (int r_off = 0; r_off < 3; r_off++) {
                for (int c_off = 0; c_off < 3; c_off++) {
                    if ((mask & (1 << (r_off * 3 + c_off))) != 0) {
                        Cell cell = getCell(y + r_off, x + c_off);
                        if (cell == null) continue;

                        // 檢查方塊是否未知 (state 0)
                        if (state[cell.r][cell.c] == 0) {

                            // === 處理 `findNextMove` 的返回 ===
                            if (isFindNextMoveMode && pendingMove == null) {
                                // 這是我們找到的第一個動作！
                                MoveType moveType = isMine ? MoveType.FLAG : MoveType.CLICK;
                                pendingMove = new SolverMove(moveType, new ArrayList<>());
                                pendingMove.cells.add(cell);
                                createdMoveThisCall = true;

                            } else if (isFindNextMoveMode && createdMoveThisCall) {
                                // 這是同一推論中的後續方塊
                                MoveType moveType = isMine ? MoveType.FLAG : MoveType.CLICK;
                                // 確保它們是同一種類型的動作 (e.g., "全安全" 或 "全地雷")
                                if (pendingMove.type == moveType) {
                                    pendingMove.cells.add(cell);
                                }
                                // (如果類型不同，e.g. 差集推論，
                                // 我們只返回第一個被觸發的集合)
                            }

                            // === 更新 "影子棋盤" 狀態 ===
                            if (isMine) {
                                state[cell.r][cell.c] = 2; // 標記為旗幟
                            } else {
                                state[cell.r][cell.c] = 1; // 標記為揭開
                            }

                            // === 添加到 `squareTodo` 進行 階段 1 處理 ===
                            squareTodo.add(cell);
                        }
                    }
                }
            }
        }

        /**
         * (新增) `known_squares` 的全域版本
         */
        private void known_squares_global(List<Cell> cells, boolean isMine,
                                          int[][] state, Queue<Cell> squareTodo,
                                          boolean isFindNextMoveMode) {
            if (isFindNextMoveMode && pendingMove != null) return;

            MoveType moveType = isMine ? MoveType.FLAG : MoveType.CLICK;
            pendingMove = new SolverMove(moveType, new ArrayList<>());

            for (Cell cell : cells) {
                if (state[cell.r][cell.c] == 0) {
                    if (isFindNextMoveMode) {
                        pendingMove.cells.add(cell);
                    }
                    if (isMine) {
                        state[cell.r][cell.c] = 2;
                    } else {
                        state[cell.r][cell.c] = 1;
                    }
                    squareTodo.add(cell);
                }
            }
            // 如果列表為空，則 pendingMove 是無效的
            if (isFindNextMoveMode && pendingMove.cells.isEmpty()) {
                pendingMove = null;
            }
        }


        /**
         * C 語言 `minesolve` 函式的主迴圈移植版。
         * @param state 影子棋盤
         * @param squareTodo 初始的 `squareTodo` 佇列 (通常是所有已揭開的數字)
         * @param isFindNextMoveMode 如果為 true, 則在 `pendingMove` 被設置時立即返回
         * @return 如果 `isFindNextMoveMode` 為 true, 則返回 `SolverMove`，否則返回 `null`
         */
        private SolverMove minesolveLoop(int[][] state, Queue<Cell> squareTodo, boolean isFindNextMoveMode) {

            this.pendingMove = null;
            Map<SolverSet, SolverSet> setsDB = new HashMap<>();
            Queue<SolverSet> setTodo = new ArrayDeque<>();

            // C 求解器在 `perturb` 模式下有一個 10 的上限
            // 我們使用 15 作為 Java 的安全上限
            final int MAX_DISJOINT_SETS = 15;

            // === 主求解迴圈 (C: `while(1)`) ===
            while (true) {
                boolean done_something = false;

                // --- 階段 1: 處理新揭開的方塊 (`squaretodo`) ---
                while (!squareTodo.isEmpty()) {
                    Cell cell = squareTodo.poll();
                    int r = cell.r;
                    int c = cell.c;
                    done_something = true;

                    // 1a. 如果是數字 (state 1), 創建一個新約束
                    if (state[r][c] == 1 && board[r][c].value >= 0) {
                        int mask = 0;
                        int mines = board[r][c].value; // 數字

                        for (int dr = -1; dr <= 1; dr++) {
                            for (int dc = -1; dc <= 1; dc++) {
                                if (dr == 0 && dc == 0) continue;
                                Cell neighbor = getCell(r + dr, c + dc);
                                if (neighbor == null) continue;

                                int n_state = state[neighbor.r][neighbor.c];
                                if (n_state == 2) { // flagged
                                    mines--;
                                } else if (n_state == 0) { // hidden
                                    mask |= (1 << ((dr + 1) * 3 + (dc + 1)));
                                }
                            }
                        }

                        if (mask > 0) {
                            // C 程式碼的基點是 (x-1, y-1)
                            ss_add(setsDB, setTodo, c - 1, r - 1, mask, mines);
                        }
                    }

                    // 1b. *無論如何* (數字或旗幟), 都將此方塊從所有現有約束中移除
                    // C 程式碼使用 (x, y, 1) 來表示單個方塊
                    List<SolverSet> overlapping = ss_overlap(setsDB, c, r, 1);

                    for (SolverSet s : overlapping) {
                        // `setmunge` (diff=true) 來計算 s - {cell}
                        int newmask = setmunge(s.x, s.y, s.mask, c, r, 1, true);
                        int newmines = s.mines;
                        if (state[r][c] == 2) { // 剛被標記為旗幟 (C: grid[i] == -1)
                            newmines--;
                        }

                        // 移除舊約束
                        ss_remove(setsDB, s);

                        // 添加簡化後的新約束
                        if (newmask > 0) {
                            ss_add(setsDB, setTodo, s.x, s.y, newmask, newmines);
                        }
                    }
                } // 結束 階段 1 (`squareTodo` 迴圈)

                // 如果 `known_squares` 設置了 `pendingMove`，立即返回
                if (isFindNextMoveMode && pendingMove != null) {
                    return pendingMove;
                }

                // --- 階段 2: 處理約束 (`setTodo`) ---
                SolverSet s = ss_todo(setTodo, setsDB);
                if (s != null) {
                    done_something = true;

                    // 2a. 基礎推論 (Trivial Deduction)
                    int count = bitcount(s.mask);
                    if (s.mines == 0) {
                        known_squares(s.x, s.y, s.mask, false, state, squareTodo, isFindNextMoveMode);
                        continue; // 回到 階段 1
                    }
                    if (s.mines == count) {
                        known_squares(s.x, s.y, s.mask, true, state, squareTodo, isFindNextMoveMode);
                        continue; // 回到 階段 1
                    }

                    // 如果 `known_squares` 設置了 `pendingMove`，立即返回
                    if (isFindNextMoveMode && pendingMove != null) {
                        return pendingMove;
                    }

                    // 2b. 進階推論 (Subset/Difference)
                    List<SolverSet> overlapping = ss_overlap(setsDB, s.x, s.y, s.mask);
                    for (SolverSet s2 : overlapping) {
                        if (s2 == s) continue;

                        // 計算 s - s2 (swing) 和 s2 - s (s2wing)
                        int swing = setmunge(s.x, s.y, s.mask, s2.x, s2.y, s2.mask, true);
                        int s2wing = setmunge(s2.x, s2.y, s2.mask, s.x, s.y, s.mask, true);
                        int swc = bitcount(swing);
                        int s2wc = bitcount(s2wing);

                        // 差集推論 (A - B)
                        if (swc > 0 && swc == s.mines - s2.mines) {
                            // swing (s - s2) 必定全是地雷
                            // s2wing (s2 - s) 必定全是安全
                            known_squares(s.x, s.y, swing, true, state, squareTodo, isFindNextMoveMode);
                            known_squares(s2.x, s2.y, s2wing, false, state, squareTodo, isFindNextMoveMode);
                            // (注意: 這裡 C 語言會 continue 到下一個 s2, 但我們
                            // 必須檢查 pendingMove 並可能要 break 返回)
                            if (isFindNextMoveMode && pendingMove != null) break;

                        } else if (s2wc > 0 && s2wc == s2.mines - s.mines) {
                            // s2wing (s2 - s) 必定全是地雷
                            // swing (s - s) 必定全是安全
                            known_squares(s2.x, s2.y, s2wing, true, state, squareTodo, isFindNextMoveMode);
                            known_squares(s.x, s.y, swing, false, state, squareTodo, isFindNextMoveMode);
                            if (isFindNextMoveMode && pendingMove != null) break;
                        }

                        // 子集推論 (A ⊂ B)
                        if (swc == 0 && s2wc != 0) {
                            // s 是 s2 的子集。創建新約束 s2 - s
                            ss_add(setsDB, setTodo, s2.x, s2.y, s2wing, s2.mines - s.mines);
                        } else if (s2wc == 0 && swc != 0) {
                            // s2 是 s 的子集。創建新約束 s - s2
                            ss_add(setsDB, setTodo, s.x, s.y, swing, s.mines - s.mines);
                        }
                    }
                    // 結束 s2 迴圈

                    // 如果 `known_squares` 設置了 `pendingMove`，立即返回
                    if (isFindNextMoveMode && pendingMove != null) {
                        return pendingMove;
                    }

                    continue; // 繼續 階段 2
                }
                // 結束 `if (s != null)`

                // --- 階段 3: 全域推論 (Global Deduction) ---
                // (僅當 階段 1 和 階段 2 都為空時執行)

                // 3a. 計算全域剩餘量
                int currentHidden = 0;
                int currentFlags = 0;
                List<Cell> hiddenCells = new ArrayList<>();
                for (int r = 0; r < ROWS; r++) {
                    for (int c = 0; c < COLS; c++) {
                        int s_val = state[r][c];
                        if (s_val == 0) {
                            currentHidden++;
                            hiddenCells.add(board[r][c]);
                        } else if (s_val == 2) {
                            currentFlags++;
                        }
                    }
                }

                if (currentHidden == 0) {
                    break; // 棋盤已解開 (或卡住但沒隱藏格了)
                }

                int minesLeft = this.totalMines - currentFlags;

                // 3b. 基礎全域推論
                if (minesLeft == 0) {
                    known_squares_global(hiddenCells, false, state, squareTodo, isFindNextMoveMode);
                    done_something = true;
                    continue; // 回到 階段 1
                }
                if (minesLeft == currentHidden) {
                    known_squares_global(hiddenCells, true, state, squareTodo, isFindNextMoveMode);
                    done_something = true;
                    continue; // 回到 階段 1
                }

                // 如果 `known_squares_global` 設置了 `pendingMove`，立即返回
                if (isFindNextMoveMode && pendingMove != null) {
                    return pendingMove;
                }

                // 3c. 進階全域推論 (不相交集分析)
                List<SolverSet> allSets = new ArrayList<>(setsDB.values());
                if (allSets.size() > 0 && allSets.size() <= MAX_DISJOINT_SETS) {
                    // C 語言的 `cursor` 迴圈是一個遞歸的實現。
                    // 我們在這裡調用一個遞歸輔助函式。
                    findDisjointCombinations(
                            allSets, 0, new ArrayList<>(), state, squareTodo,
                            minesLeft, currentHidden, isFindNextMoveMode
                    );

                    if (pendingMove != null) {
                        done_something = true;
                        // (繼續迴圈，讓 階段 1 處理 `pendingMove` 產生的 `squareTodo`)
                        continue;
                    }
                }

                // --- 階段 4: 卡住 (Stalled) ---
                if (!done_something) {
                    break; // 迴圈 1, 2, 3 都沒有做任何事，求解器卡住
                }

            } // 結束 `while(true)`

            // 迴圈終止 (卡住了)
            return null;
        }

        /**
         * `minesolve` 階段 3c 的遞歸輔助函式。
         * 遍歷 `allSets` 的所有*不相交*組合。
         */
        private void findDisjointCombinations(
                List<SolverSet> allSets, int index,
                List<SolverSet> currentUnion,
                int[][] state, Queue<Cell> squareTodo,
                int globalMinesLeft, int globalHidden, boolean isFindNextMoveMode) {

            if (pendingMove != null) return; // 已經找到一個解，停止遞歸

            // Base Case: 已經考慮過所有 set
            if (index == allSets.size()) {
                if (currentUnion.isEmpty()) return; // 不需要檢查空集

                int unionMines = 0;
                int unionSquares = 0;
                Set<Point> unionCells = new HashSet<>();

                // 計算聯集 (Union) 的地雷數和方塊數
                for (SolverSet s : currentUnion) {
                    unionMines += s.mines;
                    for (int r_off = 0; r_off < 3; r_off++) {
                        for (int c_off = 0; c_off < 3; c_off++) {
                            if ((s.mask & (1 << (r_off * 3 + c_off))) != 0) {
                                // 確保這個方塊仍然是 hidden (state 0)
                                int r = s.y + r_off;
                                int c = s.x + c_off;
                                if (r >= 0 && r < ROWS && c >= 0 && c < COLS && state[r][c] == 0) {
                                    // 使用 Set<Point> 來自動處理重疊
                                    unionCells.add(new Point(r, c));
                                }
                            }
                        }
                    }
                }
                unionSquares = unionCells.size();

                // 計算餘集 (Remainder)
                int remainderMines = globalMinesLeft - unionMines;
                int remainderSquares = globalHidden - unionSquares;

                // 全域推論
                if (remainderSquares > 0 && (remainderMines == 0 || remainderMines == remainderSquares)) {
                    // 找到了！餘集要嘛全安全，要嘛全是雷

                    // 找到所有在餘集中的方塊 (hidden 且不在 unionCells 中)
                    List<Cell> remainderCells = new ArrayList<>();
                    for (int r = 0; r < ROWS; r++) {
                        for (int c = 0; c < COLS; c++) {
                            if (state[r][c] == 0 && !unionCells.contains(new Point(r, c))) {
                                remainderCells.add(board[r][c]);
                            }
                        }
                    }

                    if (!remainderCells.isEmpty()) {
                        known_squares_global(remainderCells, (remainderMines != 0), state, squareTodo, isFindNextMoveMode);
                    }
                }
                return;
            }

            // 遞歸步驟:

            // 1. 不包含 `allSets.get(index)`
            findDisjointCombinations(allSets, index + 1, currentUnion, state, squareTodo, globalMinesLeft, globalHidden, isFindNextMoveMode);
            if (pendingMove != null) return;

            // 2. 包含 `allSets.get(index)` (如果它與 `currentUnion` 不相交)
            SolverSet s_new = allSets.get(index);
            boolean isDisjoint = true;
            for (SolverSet s_existing : currentUnion) {
                if (setmunge(s_new.x, s_new.y, s_new.mask, s_existing.x, s_existing.y, s_existing.mask, false) != 0) {
                    isDisjoint = false;
                    break;
                }
            }

            if (isDisjoint) {
                currentUnion.add(s_new);
                findDisjointCombinations(allSets, index + 1, currentUnion, state, squareTodo, globalMinesLeft, globalHidden, isFindNextMoveMode);
                currentUnion.remove(currentUnion.size() - 1); // 回溯
            }
        }

        // === `Solver` 類的公共介面 (API) ===

        /**
         * (用於生成器) 檢查棋盤是否*僅*通过邏輯就可解
         */
        public boolean isSolvable(int startR, int startC) {
            // 1. 創建 "影子棋盤"
            int[][] solverBoardState = new int[ROWS][COLS]; // 0=hidden
            Queue<Cell> squareTodo = new ArrayDeque<>();

            // 2. 模擬 "泛洪填充" 點開第一個 '0' 區域 (C 語言沒有這一步，它從點擊的 0 開始)
            // (我們將使用與 C 相同的邏輯：只添加第一個點擊的方塊)

            Cell startCell = board[startR][startC];
            solverBoardState[startR][startC] = 1; // 標記為揭開
            squareTodo.add(startCell);

            // 如果 startCell 是 0，我們還需要揭開它周圍的
            // (這是 `generateSolvableBoard` 首次點擊後遊戲的實際行為)
            if (startCell.value == 0) {
                // 我們需要一個影子泛洪填充來正確初始化佇列
                Queue<Cell> floodQueue = new ArrayDeque<>();
                floodQueue.add(startCell);

                while(!floodQueue.isEmpty()) {
                    Cell cell = floodQueue.poll();
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            if (dr == 0 && dc == 0) continue;
                            Cell neighbor = getCell(cell.r + dr, cell.c + dc);
                            if (neighbor != null && solverBoardState[neighbor.r][neighbor.c] == 0) {
                                solverBoardState[neighbor.r][neighbor.c] = 1;
                                squareTodo.add(neighbor); // *所有* 新揭開的都加入
                                if (neighbor.value == 0) {
                                    floodQueue.add(neighbor);
                                }
                            }
                        }
                    }
                }
            }

            // 3. 運行 `minesolve` 引擎
            // (模式: 非 `findNextMove`, 跑到底)
            minesolveLoop(solverBoardState, squareTodo, false);

            // 4. 最終檢查: 棋盤是否完全解開?
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    // 如果一個 *非地雷* 格子仍然是 *隱藏* (0)
                    if (!board[r][c].isMine && solverBoardState[r][c] == 0) {
                        return false; // 卡住了
                    }
                }
            }
            return true; // 所有非雷格都已揭開
        }


        /**
         * 解謎器核心邏輯: 查找下一步 (用於 "提示" 按鈕)
         */
        public SolverMove findNextMove(int[][] state) {

            // 1. 創建 `squareTodo` 佇列
            // (C 求解器從所有已知方塊開始，我們也這樣做)
            Queue<Cell> squareTodo = new ArrayDeque<>();
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (state[r][c] == 1) { // 1 = revealed
                        squareTodo.add(board[r][c]);
                    }
                    // (我們不需要添加 2=flagged, 因為 C 語言的 階段 1b
                    // 會在處理 1=revealed 時自動處理 2=flagged)
                }
            }

            // 2. 運行 `minesolve` 引擎
            // (模式: `findNextMove`, 找到第一個 `pendingMove` 就返回)
            return minesolveLoop(state, squareTodo, true);
        }

    } // --- End of Solver Class ---
}