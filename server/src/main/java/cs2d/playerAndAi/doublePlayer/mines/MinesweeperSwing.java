package cs2d.playerAndAi.doublePlayer.mines;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * 扫雷游戏的Java Swing GUI实现。
 * 它使用 MineLogic 中从C代码移植过来的可解生成器和求解器。
 */
public class MinesweeperSwing extends JFrame {

    // --- 游戏设置 ---
    private final int ROWS = 16;
    private final int COLS = 16;
    private final int MINES = 40;
    private final int CELL_SIZE = 30; // 单元格像素大小
    // -----------------

    private final JButton[][] cells;
    private final JPanel gridPanel;
    private final JLabel statusLabel;

    private boolean[] mineGrid;      // [真实的地雷布局] (true=有雷, false=无雷)
    private int[] playerGrid;        // [玩家的视角] (-2=未知, -1=旗帜, 0-8=数字)
    private boolean gameOver;
    private int cellsToOpen;

    private MineLogic.MineCtx mineCtx;
    private MineLogic.RandomState randomState;
    private final ExecutorService solverExecutor = Executors.newSingleThreadExecutor();

    // 单元格颜色
    private final Color COLOR_UNKNOWN = new Color(192, 192, 192);
    private final Color COLOR_OPENED = new Color(224, 224, 224);
    private final Color COLOR_MINE = new Color(255, 100, 100);
    private final Color[] NUM_COLORS = {
            Color.BLUE, new Color(0, 128, 0), Color.RED, new Color(0, 0, 128),
            new Color(128, 0, 0), new Color(0, 128, 128), Color.BLACK, Color.GRAY
    };

    public MinesweeperSwing() {
        setTitle("可解扫雷 (C 逻辑移植)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // 控制面板
        JPanel controlPanel = new JPanel();
        JButton newGameButton = new JButton("新游戏");
        newGameButton.addActionListener(e -> newGame());
        JButton solveButton = new JButton("自动求解");
        solveButton.addActionListener(e -> autoSolve());
        statusLabel = new JLabel("欢迎! 点击 '新游戏' 开始。");

        controlPanel.add(newGameButton);
        controlPanel.add(solveButton);
        controlPanel.add(statusLabel);
        add(controlPanel, BorderLayout.NORTH);

        // 游戏网格
        gridPanel = new JPanel(new GridLayout(ROWS, COLS));
        gridPanel.setPreferredSize(new Dimension(COLS * CELL_SIZE, ROWS * CELL_SIZE));
        cells = new JButton[ROWS][COLS];
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                cells[y][x] = new JButton();
                cells[y][x].setMargin(new Insets(0, 0, 0, 0));
                cells[y][x].setFont(new Font("Arial", Font.BOLD, 14));
                cells[y][x].addMouseListener(new CellMouseListener(x, y));
                gridPanel.add(cells[y][x]);
            }
        }
        add(gridPanel, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null); // 居中
        setVisible(true);

        newGame();
    }

    /**
     * 开始一个新游戏
     */
    private void newGame() {
        statusLabel.setText("正在生成可解棋盘...");
        // 使用一个新线程来生成，避免GUI卡顿
        Executors.newSingleThreadExecutor().execute(() -> {
            randomState = new MineLogic.RandomState(System.currentTimeMillis());

            // 1. 调用移植的 minegen 来创建保证可解的布局
            // 我们从 (0, 0) 开始点击，minegen 会确保 (0, 0) 及其周围是安全的
            int startX = 0;
            int startY = 0;
            mineGrid = MineLogic.minegen(COLS, ROWS, MINES, startX, startY, true, randomState);

            // 2. 初始化玩家视角
            playerGrid = new int[ROWS * COLS];
            Arrays.fill(playerGrid, -2); // -2 = 未知

            // 3. 设置游戏状态
            gameOver = false;
            cellsToOpen = ROWS * COLS - MINES;

            // 4. 创建求解器上下文 (C代码中的 minectx)
            // 'opened' 数组用于 mineopen 回调
            mineCtx = new MineLogic.MineCtx(mineGrid, new boolean[ROWS * COLS], COLS, ROWS, startX, startY, randomState);

            // 5. 在GUI线程上模拟第一次点击
            SwingUtilities.invokeLater(() -> {
                openCell(startX, startY); // 模拟第一次点击
                updateGUI();
                statusLabel.setText("游戏开始! " + MINES + " 个地雷。");
            });
        });
    }

    /**
     * 自动求解（调用移植的 minesolve）
     */
    private void autoSolve() {
        if (gameOver) return;
        statusLabel.setText("自动求解中...");

        // 求解器必须在单独的线程上运行，因为它是一个长时任务
        // 它会通过 SwingUtilities.invokeLater 回调来更新GUI
        solverExecutor.execute(() -> {

            // 复制一份 playerGrid 供求解器使用
            // 求解器会直接修改这个数组，就像C代码修改 grid 参数一样
            int[] solverViewGrid = Arrays.copyOf(playerGrid, playerGrid.length);

            // 1. 实现 OpenCallback 接口 (C代码中的 open_cb)
            // 这是求解器'打开'一个它确定是安全的格子的方式
            MineLogic.OpenCallback solverOpenCallback = (ctx, x, y) -> {
                // mineopen 检查真实的地雷布局
                int num = MineLogic.mineopen(mineCtx, x, y);
                int index = y * COLS + x;

                if (num >= 0 && solverViewGrid[index] == -2) {
                    // 在GUI线程上更新视图
                    SwingUtilities.invokeLater(() -> {
                        playerGrid[index] = num;
                        cellsToOpen--;
                        updateGUI();

                        // 检查胜利
                        if (cellsToOpen == 0) {
                            winGame();
                        }
                    });

                    // 减速以便观察
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return num; // 返回周围地雷数
            };

            // 2. 调用移植的 minesolve
            // 我们传递 null 作为 perturb_cb，因为玩家求解器不应该修改布局
            int result = MineLogic.minesolve(COLS, ROWS, MINES, solverViewGrid,
                    solverOpenCallback, null, mineCtx, randomState);

            // 3. 处理结果
            SwingUtilities.invokeLater(() -> {
                if (result == 0) {
                    statusLabel.setText("求解完成!");
                    winGame();
                } else if (result == -1) {
                    statusLabel.setText("求解器卡住了 (需要猜测)。");
                }
            });
        });
    }

    /**
     * 处理玩家点击
     */
    private class CellMouseListener extends MouseAdapter {
        private final int x, y;

        CellMouseListener(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (gameOver) return;
            int index = y * COLS + x;

            if (SwingUtilities.isRightMouseButton(e)) {
                // 右键：插旗
                if (playerGrid[index] == -2) {
                    playerGrid[index] = -1; // 标记为旗帜
                } else if (playerGrid[index] == -1) {
                    playerGrid[index] = -2; // 取消旗帜
                }
            } else if (SwingUtilities.isLeftMouseButton(e)) {
                // 左键：打开
                openCell(x, y);
            }

            updateGUI();

            // 检查胜利条件
            if (!gameOver && cellsToOpen == 0) {
                winGame();
            }
        }
    }

    /**
     * 递归地打开一个单元格
     *
     * @param x 坐标x
     * @param y 坐标y
     */
    private void openCell(int x, int y) {
        if (gameOver || x < 0 || x >= COLS || y < 0 || y >= ROWS) return;

        int index = y * COLS + x;
        if (playerGrid[index] != -2) return; // 已经打开或已插旗

        // 调用 mineopen 检查真实地雷
        int num = MineLogic.mineopen(mineCtx, x, y);

        if (num == -1) {
            // 踩到地雷！
            gameOver = true;
            playerGrid[index] = -3; // -3 表示爆炸的地雷
            revealAllMines();
            statusLabel.setText("游戏结束!");
        } else {
            // 安全
            playerGrid[index] = num;
            cellsToOpen--;

            if (num == 0) {
                // 如果是0，递归打开周围的格子
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx != 0 || dy != 0) {
                            openCell(x + dx, y + dy);
                        }
                    }
                }
            }
        }
    }

    private void winGame() {
        gameOver = true;
        statusLabel.setText("你赢了!");
        // 自动标记所有剩余的雷
        for (int i = 0; i < playerGrid.length; i++) {
            if (mineGrid[i] && playerGrid[i] == -2) {
                playerGrid[i] = -1;
            }
        }
        updateGUI();
    }

    /**
     * 游戏结束时显示所有地雷
     */
    private void revealAllMines() {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int index = y * COLS + x;
                if (mineGrid[index] && playerGrid[index] != -3) {
                    // 显示地雷
                    playerGrid[index] = -4; // -4 = 未爆炸的地雷
                } else if (!mineGrid[index] && playerGrid[index] == -1) {
                    // 标记错误
                    playerGrid[index] = -5; // -5 = 错插的旗
                }
            }
        }
        updateGUI();
    }

    /**
     * 根据 playerGrid 刷新整个GUI
     */
    private void updateGUI() {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                JButton cell = cells[y][x];
                int state = playerGrid[y * COLS + x];

                cell.setText("");
                cell.setEnabled(true);
                cell.setBackground(COLOR_UNKNOWN);
                cell.setForeground(Color.BLACK);

                switch (state) {
                    case -5: // 错插的旗
                        cell.setText("X");
                        cell.setBackground(COLOR_OPENED);
                        cell.setForeground(Color.RED);
                        break;
                    case -4: // 未爆炸的地雷
                        cell.setText("*");
                        cell.setBackground(COLOR_OPENED);
                        break;
                    case -3: // 爆炸的地雷
                        cell.setText("*");
                        cell.setBackground(COLOR_MINE);
                        break;
                    case -2: // 未知
                        // 保持默认
                        break;
                    case -1: // 旗帜
                        cell.setText("F");
                        cell.setForeground(Color.RED);
                        break;
                    case 0: // 空白
                        cell.setEnabled(false);
                        cell.setBackground(COLOR_OPENED);
                        break;
                    default: // 数字 1-8
                        cell.setText(String.valueOf(state));
                        cell.setEnabled(false);
                        cell.setBackground(COLOR_OPENED);
                        cell.setForeground(NUM_COLORS[state - 1]);
                        break;
                }
            }
        }
    }

    public static void main(String[] args) {
        // 确保GUI在事件分发线程上创建
        SwingUtilities.invokeLater(MinesweeperSwing::new);
    }
}