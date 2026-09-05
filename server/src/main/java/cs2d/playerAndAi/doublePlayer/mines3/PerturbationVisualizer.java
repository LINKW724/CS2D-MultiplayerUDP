package cs2d.playerAndAi.doublePlayer.mines3;

import cs2d.playerAndAi.doublePlayer.mines3.MineGenerator.MineCtx;
import cs2d.playerAndAi.doublePlayer.mines3.MineGenerator.StepSolveContext;
import cs2d.playerAndAi.doublePlayer.mines3.MineSolver.Perturbation; // (新增) 导入
import cs2d.playerAndAi.doublePlayer.mines3.MineSolver.Perturbations; // (新增) 导入

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;
import java.util.Iterator; // (新增) 导入
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean; // (*** 新增 ***)

/**
 // ... (注释保持不变)
 */
public class PerturbationVisualizer extends JFrame {


    // --- 配置 ---
    private static final int GRID_W = 9; // 宽度
    private static final int GRID_H = 9; // 高度
    private static final int NUM_MINES = 50; // 地雷数
    private static final int START_X = (int) Math.floor(GRID_W/2);  // 首次点击 X
    private static final int START_Y = (int) Math.floor(GRID_H/2);  // 首次点击 Y
    private static final boolean REQUIRE_UNIQUE = true;

    // --- UI 组件 ---
    private final JButton startButton;
    private final JButton nextButton;
    private final JToggleButton autoButton; // (*** 新增 ***)
    private final JLabel statusLabel;
    private final GridPanel gridPanel;
    private Timer animationTimer; // (新增) 动画计时器
    private Timer autoTimer;      // (*** 新增 ***) 自动点击计时器

    // --- 状态 ---
    private GenerationTask generationTask;
    private volatile Semaphore solverSemaphore; // 用于暂停/恢复求解器
    private volatile boolean isAutoRunning = false; // (*** 新增 ***)

    // (*** 关键修复 新增 ***)
    /** 线程安全的状态标志: true = 求解器在 "Pause 1" (等待 "Next") 时卡住 */
    private final AtomicBoolean isSolverStuck = new AtomicBoolean(false);


    // --- 存储的棋盘状态 (用于绘制) ---
    // (注意: 这些变量会被后台线程写入，并由UI线程读取)
    private volatile byte[] currentSolveGrid;
    private volatile MineCtx currentMineCtx;

    // (*** 修改 ***) 用于高亮显示正在播放动画的格子
    private volatile int animatedCellX = -1;
    private volatile int animatedCellY = -1;
    private volatile int animatedCellDelta = 0; // (*** 新增 ***) 0=无, >0=添加(红色), <0=移除(绿色)


    public PerturbationVisualizer() {
        super("Minegen 扰动可视化工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // 1. 顶部控制面板
        JPanel controlPanel = new JPanel(new FlowLayout());
        startButton = new JButton("开始生成 (Start Generation)");
        nextButton = new JButton("下一步扰动 (Next Perturbation)");
        autoButton = new JToggleButton("自动 (Auto)"); // (*** 新增 ***)
        statusLabel = new JLabel("准备就绪。");
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 14));

        controlPanel.add(startButton);
        controlPanel.add(nextButton);
        controlPanel.add(autoButton); // (*** 新增 ***)

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(controlPanel, BorderLayout.NORTH);
        topPanel.add(statusLabel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        // 2. 中央网格面板
        gridPanel = new GridPanel();
        add(gridPanel, BorderLayout.CENTER);

        // 3. 设置按钮逻辑
        startButton.addActionListener(e -> startGeneration());

        nextButton.addActionListener(e -> {
            if (solverSemaphore != null && !isAutoRunning) { // (修改) 自动模式下禁用
                // (修改) 仅在没有动画播放时才释放信号
                if (animationTimer == null || !animationTimer.isRunning()) {
                    // (*** 新增 ***) 手动点击时，也需要清除 "卡住" 标志
                    isSolverStuck.set(false);
                    solverSemaphore.release(); // 释放信号，让求解器继续
                }
            }
        });

        // (*** 新增 ***) 自动计时器逻辑
        setupAutoTimer();
        autoButton.addActionListener(e -> {
            if (autoButton.isSelected()) {
                // (点击了 "自动")
                isAutoRunning = true;
                autoButton.setText("停止 (Stop)");
                nextButton.setEnabled(false); // 自动模式下禁用手动
                autoTimer.start();

                // (如果求解器正在等待，立即触发一次)
                // (*** 修改 ***) 使用新的检查逻辑
                triggerAutoTimerAction();

            } else {
                // (点击了 "停止")
                isAutoRunning = false;
                autoButton.setText("自动 (Auto)");
                autoTimer.stop();
                // (仅在动画未播放时才重新启用 "Next")
                if (animationTimer == null || !animationTimer.isRunning()) {
                    nextButton.setEnabled(true);
                }
            }
        });


        // 4. 初始化状态
        currentSolveGrid = new byte[GRID_W * GRID_H];
        Arrays.fill(currentSolveGrid, (byte)-2);
        currentMineCtx = new MineCtx(new boolean[GRID_W * GRID_H], new boolean[GRID_W * GRID_H], GRID_W, GRID_H, START_X, START_Y, new Random());

        nextButton.setEnabled(false);
        autoButton.setEnabled(false); // (*** 新增 ***)

        pack();
        setLocationRelativeTo(null);
    }

    // (*** 新增 ***)
    private void setupAutoTimer() {
        // (*** 修改: 500ms -> 100ms ***)
        autoTimer = new Timer(100, e -> {
            // (*** 修改 ***) 调用新的检查逻辑
            triggerAutoTimerAction();
        });
        autoTimer.setInitialDelay(100); // 第一次延迟
    }

    /**
     * (*** 新增 ***)
     * 自动计时器和 "自动" 按钮的共享逻辑
     * 检查是否可以安全地释放 "下一步" 信号
     * (*** 已修改 ***)
     */
    private void triggerAutoTimerAction() {
        // 检查 1: 必须处于自动模式, 且求解器必须存在
        if (!isAutoRunning || solverSemaphore == null) {
            return;
        }

        // 检查 2: 动画当前*不能*正在播放
        if (animationTimer != null && animationTimer.isRunning()) {
            return;
        }

        // 检查 3: (*** 关键修复 ***)
        // 求解器必须*真正*卡住
        // (我们使用 .get() 来读取线程安全的 AtomicBoolean)
        if (!isSolverStuck.get()) {
            return; // 求解器仍在计算，不要释放信号
        }

        // 所有检查通过：我们处于自动模式，没有动画在播放，并且求解器正在等待 "下一步"

        // (检查信号量，防止多次释放)
        if (solverSemaphore.getQueueLength() == 0) {
            // (*** 关键修复 ***) 在释放信号之前，清除 "卡住" 标志
            isSolverStuck.set(false);
            solverSemaphore.release(); // 释放 (用于 Pause 1: 等待 "Next")
        }
    }


    /**
     * 开始后台生成任务
     * (*** 已修改 ***)
     */
    private void startGeneration() {
        startButton.setEnabled(false);
        nextButton.setEnabled(true);
        autoButton.setEnabled(true); // (*** 新增 ***)

        // (*** 新增 ***) 重置自动按钮
        if (autoTimer != null) autoTimer.stop();
        isAutoRunning = false;
        autoButton.setText("自动 (Auto)");
        autoButton.setSelected(false);
        isSolverStuck.set(false); // (*** 关键修复 ***) 重置标志

        statusLabel.setText("正在启动生成器...");

        // (关键) 创建信号量，初始为 0 (锁定)
        // 求解器在第一次 acquire() 时会立即等待
        solverSemaphore = new Semaphore(0);

        // 创建并执行 SwingWorker
        // (*** 关键修复 ***) 将 isSolverStuck 标志传递给任务
        generationTask = new GenerationTask(GRID_W, GRID_H, NUM_MINES, START_X, START_Y, REQUIRE_UNIQUE, solverSemaphore, isSolverStuck);
        generationTask.execute();
    }

    /**
     * 内部类：用于绘制求解器状态的面板
     * (*** 已修改 ***)
     */
    private class GridPanel extends JPanel {
        private static final int CELL_SIZE = 25;
        private final Font monoFont = new Font("Monospaced", Font.BOLD, 14);

        GridPanel() {
            setPreferredSize(new Dimension(GRID_W * CELL_SIZE, GRID_H * CELL_SIZE));
            setBackground(Color.DARK_GRAY);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            // (读取 volatile 变量)
            byte[] solveGrid = currentSolveGrid;
            MineCtx mineCtx = currentMineCtx;
            if (solveGrid == null || mineCtx == null) return;
            boolean[] realMines = mineCtx.grid;

            for (int y = 0; y < GRID_H; y++) {
                for (int x = 0; x < GRID_W; x++) {
                    int i = y * GRID_W + x;
                    int gx = x * CELL_SIZE;
                    int gy = y * CELL_SIZE;

                    byte state = solveGrid[i];
                    boolean isMine = realMines[i];

                    // (*** 关键修改 ***) 检查是否是正在播放动画的格子
                    boolean isAnimatingCell = (x == animatedCellX && y == animatedCellY);

                    // 1. 绘制背景
                    if (isAnimatingCell && animatedCellDelta < 0) {
                        // (*** 新增 ***)
                        // 这是一个 "移动起点" (移除雷, delta < 0)
                        // 动画播放器会在 *应用* 更改 *前* 设置此状态，
                        // (不，动画播放器 *应用* 更改 *后* 才重绘)
                        // (这意味着 isMine 已经是 false 了)
                        // 我们高亮背景为绿色
                        g.setColor(Color.GREEN);

                    } else if (state == -2) { // 未知
                        g.setColor(Color.GRAY);
                    } else if (state == -1) { // 标记 (Flag)
                        g.setColor(Color.ORANGE);
                    } else if (state >= 0) { // 已打开 (数字)
                        g.setColor(Color.WHITE);
                    }
                    g.fillRect(gx, gy, CELL_SIZE - 1, CELL_SIZE - 1);

                    // 2. 绘制 "真实地雷" (仅在未知格子上显示)
                    if (isMine && state == -2) {

                        // (*** 修改 ***) 检查是否是正在播放动画的格子
                        if (isAnimatingCell) {
                            // isMine = true 且 state = -2
                            // 这意味着这是一个 "移动终点" (添加雷, delta > 0)
                            g.setColor(Color.RED); // 动画中的雷为红色
                        } else {
                            g.setColor(Color.BLACK); // 普通的雷为黑色
                        }

                        g.setFont(monoFont);
                        g.drawString("M", gx + 7, gy + 17);
                    }

                    // 3. 绘制求解器看到的数字/标记
                    g.setColor(Color.BLACK);
                    g.setFont(monoFont);
                    if (state == -1) {
                        g.setColor(Color.RED);
                        g.drawString("F", gx + 7, gy + 17);
                    } else if (state > 0) {
                        g.drawString(String.valueOf(state), gx + 7, gy + 17);
                    }
                }
            }
        }
    }


    /**
     * (新增) 动画播放器
     * @param perts 包含所有更改步骤的扰动方案
     * (*** 已修改 ***)
     */
    private void animatePerturbations(Perturbations perts) {
        // 1. 停止任何可能正在运行的旧动画
        if (animationTimer != null && animationTimer.isRunning()) {
            animationTimer.stop();
        }

        // 2. 在动画播放期间禁用按钮
        nextButton.setEnabled(false);
        startButton.setEnabled(false);
        // (注意: autoButton 保持原样，但 autoTimer 不会触发)

        // 3. 创建一个迭代器来遍历所有更改
        final Iterator<Perturbation> iterator = Arrays.asList(perts.changes).iterator();

        // 4. 创建计时器
        animationTimer = new Timer(200, null); // 200毫秒 播放一步
        animationTimer.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (iterator.hasNext()) {
                    // a. 获取下一步更改
                    Perturbation p = iterator.next();

                    // (*** 修改 ***) 标记这个格子 *和* 它的类型
                    animatedCellX = p.x;
                    animatedCellY = p.y;
                    animatedCellDelta = p.delta; // (*** 新增 ***)

                    // b. (关键) 将这一步更改 *应用* 到 UI 正在绘制的 grid 上
                    //    (注意: 这*不是*求解器的主 grid，这是 UI 的副本)
                    if (currentMineCtx != null) {
                        currentMineCtx.grid[p.y * GRID_W + p.x] = (p.delta > 0);
                    }

                    // c. 重绘网格以显示这一帧
                    gridPanel.repaint();

                } else {
                    // d. 动画播放完毕
                    animationTimer.stop();

                    // (*** 修改 ***) 清除所有动画标记
                    animatedCellX = -1;
                    animatedCellY = -1;
                    animatedCellDelta = 0; // (*** 新增 ***)

                    // (*** 关键修复 ***) 求解器即将运行，所以它没有卡住
                    isSolverStuck.set(false);

                    // e. 重新启用 "Next" 按钮 (它现在用于 "继续求解")
                    // (*** 修改 ***) 仅在非自动模式下启用 "Next"
                    if (!isAutoRunning) {
                        nextButton.setEnabled(true);
                    }
                    startButton.setEnabled(true); // (允许重启)

                    // f. (*** 关键 ***) 释放求解器，让它从 Pause 2 继续
                    if (solverSemaphore != null) {
                        solverSemaphore.release();
                    }

                    // (*** 新增 ***) 播放最后一次重绘，清除红色高亮
                    gridPanel.repaint();
                }
            }
        });

        // 5. 启动动画
        animationTimer.start();
    }


    /**
     * 内部类：在后台线程中运行 `minegen` 循环的 SwingWorker
     * (*** 已修改 ***)
     */
    private class GenerationTask extends SwingWorker<boolean[], GenerationTask.GenerationState> {
        private final int w, h, n, x, y;
        private final boolean unique;
        private final Random rs;
        private final Semaphore waitSignal;

        // (*** 关键修复 新增 ***)
        private final AtomicBoolean isStuckFlag; // 引用父类的线程安全标志

        // 这是 minegen 的本地变量，现在是类的成员
        private boolean[] ret;
        private byte[] solvegrid;
        private boolean[] opened;
        /** (*** 关键 ***) 这是此任务的 MineCtx 实例 */
        private MineCtx ctx;

        /** (新增) 持有 StepCtx 的引用，以便 process() 可以访问它 */
        public StepSolveContext stepCtx;

        /** (*** 新增 ***) 保存尝试次数，以便 done() 可以访问 */
        private int ntries = 0;


        // (*** 构造函数已修改 ***)
        GenerationTask(int w, int h, int n, int x, int y, boolean unique, Semaphore signal, AtomicBoolean isStuckFlag) {
            this.w = w; this.h = h; this.n = n;
            this.x = x; this.y = y;
            this.unique = unique;
            this.rs = new Random();
            this.waitSignal = signal;
            this.isStuckFlag = isStuckFlag; // (*** 关键修复 ***)

            // ... (其他初始化保持不变)
            this.ret = new boolean[w * h];
            this.solvegrid = new byte[w * h];
            this.opened = new boolean[w * h];
            this.ctx = new MineCtx(ret, opened, w, h, x, y, rs);
        }

        /**
         * 用于在 `publish()` 和 `process()` 之间传递状态
         */
        class GenerationState {
            // ... (此类未更改，保持原样)
            final byte[] solveGridState;
            final MineCtx mineCtxState;
            GenerationState(byte[] sg, MineCtx mc) {
                // 必须克隆以避免线程冲突
                this.solveGridState = sg.clone();
                // (MineCtx 包含对 ret 和 opened 的引用，它们在后台线程中被修改，
                // 但我们接受这种轻微的竞争条件以换取性能。)
                this.mineCtxState = mc; // (传递引用)
            }
        }

        /**
         * (核心) 这就是 `minegen` 方法的逻辑，在后台线程运行
         * (*** 已修改 ***)
         */
        @Override
        protected boolean[] doInBackground() throws Exception {

            boolean success;
            // (*** 修改 ***) ntries 已经是成员变量
            // int ntries = 0; 

            // `minegen` 的 `do-while` 循环
            do {
                success = false;
                ntries++;

                // (*** 关键修复 ***)
                // 必须使用 invokeLater 来安全地更新 UI
                final String tryText = "第 " + ntries + " 次尝试";
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(tryText + ": 正在放置初始地雷...");
                });


                // 1. 清空地雷布局
                Arrays.fill(ret, false);

                // 2. 初始地雷放置 (与 minegen 完全一致)
                {
                    int[] tmp = new int[w * h];
                    int i, j, k;
                    k = 0;
                    for (i = 0; i < h; i++) {
                        for (j = 0; j < w; j++) {
                            if (Math.abs(i - y) > 1 || Math.abs(j - x) > 1) {
                                tmp[k++] = i * w + j;
                            }
                        }
                    }
                    for (int mine = 0; mine < n && k > 0; mine++) {
                        i = rs.nextInt(k);
                        ret[tmp[i]] = true;
                        tmp[i] = tmp[--k];
                    }
                }

                // 3. 可解性验证 (与 minegen 完全一致)
                if (unique) {

                    // (创建我们新的、可暂停的上下文)
                    Runnable updateCallback = () -> publish(new GenerationState(solvegrid, ctx));

                    // (*** 关键修复 ***)
                    // 将 statusLabel 和 isStuckFlag 传递给 StepSolveContext
                    this.stepCtx = new StepSolveContext(ctx, waitSignal, updateCallback, statusLabel, isStuckFlag);

                    int solveret;
                    int prevret = -2;

                    Arrays.fill(opened, false);
                    ctx.allow_big_perturbs = (ntries > 100);
                    ctx.nperturbs_since_last_new_open = 0;

                    // `minegen` 的求解器循环
                    while (true) {

                        // (检查 SwingWorker 是否被取消)
                        if (isCancelled()) return null;

                        // (*** 关键修复 ***)
                        // StepSolveContext 现在将使用 invokeLater 来安全地更新标签
                        stepCtx.reportSolving(tryText); // "第 N 次尝试: 正在运行求解器..."


                        Arrays.fill(solvegrid, (byte) -2); // 重置求解器网格
                        solvegrid[y * w + x] = MineSolver.mineopen(ctx, x, y);
                        assert (solvegrid[y * w + x] == 0);

                        // (发布当前状态，以便 UI 刷新)
                        publish(new GenerationState(solvegrid, ctx));

                        // 定义回调

                        // (*** 关键修复 ***)
                        // lambda 表达式 (openFunc) 必须显式捕获
                        // `GenerationTask.this.ctx` (保证非 null)，
                        MineSolver.MineOpener openFunc = (c, ox, oy) ->
                                MineSolver.mineopen(GenerationTask.this.ctx, ox, oy);
                        // (*** 修复结束 ***)

                        MineSolver.MinePerturber perturbFunc = (c, grid, sx, sy, mask) ->
                                MineSolver.mineperturb(c, grid, sx, sy, mask);

                        /*
                         * (关键) 调用被修改过的 `minesolve`
                         */
                        solveret = MineSolver.minesolve(w, h, n, solvegrid, openFunc, perturbFunc, this.stepCtx, rs);

                        // (发布求解器运行后的状态)
                        publish(new GenerationState(solvegrid, ctx));

                        if (solveret < 0 || (prevret >= 0 && solveret >= prevret)) {
                            // 求解失败或停滞
                            success = false;
                            break;
                        }
                        else if (solveret == 0) {
                            // 完美求解
                            success = true;
                            break;
                        }
                        prevret = solveret;
                        // (solveret > 0, 循环继续)
                    }
                } else {
                    success = true; // 不要求 unique
                }
            } while (!success && !isCancelled()); // 结束 `minegen` 的 do-while

            if (success) {
                return ret; // 返回成功的布局
            } else {
                return null; // 生成失败或被取消
            }
        }

        /**
         * (UI 线程) 处理 `publish()` 发送的数据
         * (*** 已修改以触发动画 ***)
         */
        @Override
        protected void process(List<GenerationState> chunks) {
            // 只处理最新的状态
            GenerationState latestState = chunks.get(chunks.size() - 1);

            // (关键) 将后台状态复制到UI线程的变量中
            PerturbationVisualizer.this.currentSolveGrid = latestState.solveGridState;
            PerturbationVisualizer.this.currentMineCtx = latestState.mineCtxState;

            // (*** 新增 ***) 检查是否有待处理的动画
            // (我们必须从 generationTask 实例中获取 stepCtx)
            if (generationTask != null && generationTask.stepCtx != null &&
                    generationTask.stepCtx.pendingAnimation != null) {

                // (调用动画播放器)
                animatePerturbations(generationTask.stepCtx.pendingAnimation);

            } else {
                // (没有动画，只是一个常规的网格刷新)
                gridPanel.repaint();
            }
        }

        /**
         * (UI 线程) 当 `doInBackground` 完成时调用
         * (*** 已修改 ***)
         */
        @Override
        protected void done() {
            try {
                boolean[] finalLayout = get(); // 获取 doInBackground 的返回值
                if (finalLayout != null) {
                    // (*** 修复 ***) 现在 ntries 是成员变量，可以访问了
                    statusLabel.setText("生成成功！ (共 " + ntries + " 次尝试)");
                } else {
                    statusLabel.setText("生成失败或被取消。");
                }
            } catch (Exception e) {
                statusLabel.setText("发生错误: " + e.getMessage());
                e.printStackTrace();
            } finally {
                startButton.setEnabled(true);
                nextButton.setEnabled(false);

                // (*** 新增 ***) 停止并重置自动按钮
                if (autoTimer != null) autoTimer.stop();
                isAutoRunning = false;
                autoButton.setText("自动 (Auto)");
                autoButton.setSelected(false);
                autoButton.setEnabled(false);
                isSolverStuck.set(false); // (*** 关键修复 ***)

                // (确保动画计时器停止)
                if (animationTimer != null) {
                    animationTimer.stop();
                }
            }
        }
    }


    /**
     * 主函数
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new PerturbationVisualizer().setVisible(true);
        });
    }
}

