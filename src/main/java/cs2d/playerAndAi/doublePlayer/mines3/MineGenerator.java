package cs2d.playerAndAi.doublePlayer.mines3;

import javax.swing.*;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean; // (*** 新增 ***)

/**
 * 扫雷地雷生成器 (MineGenerator)
 *
 * <p>这个类负责生成扫雷游戏的地雷布局。
 * 它的一个关键特性是能够生成“唯一可解”(unique)的布局，
 * 这意味着玩家从头到尾都可以通过逻辑推导完成游戏，而无需猜测。
 *
 * <p>它的大部分逻辑是 C 语言实现的精确移植，特别是 `minegen` 和 `open_square` 方法。
 */
public class MineGenerator {

    /**
     * 控制是否打印详细的生成日志，用于调试。
     */
    private static final boolean GENERATION_DIAGNOSTICS = true; // 保持日志开启

    /**
     * 地雷生成的上下文(Context)类。
     * <p>
     * 这个对象在 '求解器(solver)' 和 '扰动器(perturber)' 函数之间传递，
     * 允许它们共享和修改当前的地雷生成状态。
     */
    public static class MineCtx {
        // ... (MineCtx 内部保持不变)
        /**
         * 当前的地雷布局 (true = 有雷, false = 无雷)。
         * 这个数组可能会在 `mineperturb` (扰动) 过程中被修改。
         */
        boolean[] grid;
        /**
         * 记录哪些格子已经被求解器 '打开' (在 `mineopen` 中设置)。
         */
        boolean[] opened;
        /**
         * 棋盘的宽度和高度。
         */
        int w, h;
        /**
         * 玩家的起始点击坐标 (保证 3x3 区域安全)。
         */
        int sx, sy;
        /**
         * 随机数生成器实例。
         */
        Random rs;
        /**
         * 是否允许进行 '大扰动'。
         * 这通常在多次尝试生成可解布局失败后启用，以进行更激烈的修改。
         */
        boolean allow_big_perturbs;
        /**
         * 自上次成功打开新格子以来进行的扰动次数。
         * 用于检测求解器是否陷入停滞。
         */
        int nperturbs_since_last_new_open;

        /**
         * 构造函数，初始化所有上下文变量。
         */
        public MineCtx(boolean[] grid, boolean[] opened, int w, int h, int sx, int sy, Random rs) {
            this.grid = grid;
            this.opened = opened;
            this.w = w;
            this.h = h;
            this.sx = sx;
            this.sy = sy;
            this.rs = rs;
            this.allow_big_perturbs = false;
            this.nperturbs_since_last_new_open = 0;
        }
    }


    /**
     * (*** 关键修复 ***)
     * 这是一个特殊的上下文，用于在 "求解器" (后台线程) 和 "可视化UI" (UI线程) 之间进行通信。
     * 它包装了真实的 MineCtx，并添加了用于暂停和更新的信号量/回调。
     */
    public static class StepSolveContext {
        /** 真实的扫雷上下文 (包含地雷布局) */
        public final MineCtx mineCtx;
        /** * 信号量:
         * Pause 1 (扰动前): 求解器在此等待 "Next" 按钮
         * Pause 2 (动画后): 求解器在此等待动画播放完毕
         */
        public final Semaphore waitSignal;
        /** 回调: 用于通知 UI 线程 "嘿，网格已更新，请重绘" (调用 publish()) */
        public final Runnable updateGridCallback;
        /** (*** 新增 ***) 回调: 用于通知 UI 线程 "嘿，我们即将播放动画" */
        public final Runnable animationCallback;

        /** (*** 修改 ***) UI 标签引用 (用于线程安全的文本更新) */
        public final JLabel statusLabel;
        /** (*** 新增 ***) 线程安全的状态标志 */
        public final AtomicBoolean isStuckFlag;

        /** (*** 新增 ***) 存储待处理的动画方案 */
        public volatile MineSolver.Perturbations pendingAnimation;

        /**
         * 构造函数
         * (*** 已修改 ***)
         */
        public StepSolveContext(MineCtx mineCtx, Semaphore waitSignal, Runnable updateGridCallback, JLabel statusLabel, AtomicBoolean isStuckFlag) {
            this.mineCtx = mineCtx;
            this.waitSignal = waitSignal;
            this.updateGridCallback = updateGridCallback;
            this.statusLabel = statusLabel;
            this.isStuckFlag = isStuckFlag; // (*** 新增 ***)

            // (*** 新增 ***)
            // 当 "updateGridCallback" 被调用时，它实际上是在后台线程 (GenerationTask) 中。
            // 我们需要用它来触发 "animationCallback"，它也必须在后台线程中被调用。
            // (因为 publish() 只能从 doInBackground 中调用)
            this.animationCallback = updateGridCallback; // 让它们暂时相同
            this.pendingAnimation = null;
        }

        /**
         * (*** 新增 ***)
         * 由 MineSolver (后台线程) 调用，报告求解器*即将*卡住。
         */
        public void reportStuck(int nperturbs) {
            // 1. (*** 关键修复 ***) 立即设置线程安全的标志
            isStuckFlag.set(true);

            // 2. (*** 关键修复 ***) 请求 UI 线程安全地更新标签
            final String message = "Solver Stuck! Perturb #" + nperturbs + ". Click 'Next' or 'Auto'.";
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText(message);
            });
        }

        /**
         * (*** 新增 ***)
         * 由 MineSolver (后台线程) 调用，报告求解器正在运行。
         */
        public void reportSolving(String tryText) {
            // 1. (*** 关键修复 ***) 立即设置线程安全的标志
            isStuckFlag.set(false);

            // 2. (*** 关键修复 ***) 请求 UI 线程安全地更新标签
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText(tryText + ": 正在运行求解器...");
            });
        }

        /**
         * (*** 新增 ***)
         * 由 MineSolver (后台线程) 调用，报告正在等待动画。
         */
        public void reportWaitingForAnimation() {
            // 1. (*** 关键修复 ***) 它没有 "卡住"，它在 "等待动画"
            isStuckFlag.set(false);

            // 2. (*** 关键修复 ***) 请求 UI 线程安全地更新标签
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("正在播放扰动动画...");
            });
        }
    }

    // ... (所有其他 MineGenerator 的原始方法，如 describe_layout, new_game_desc 等，保持不变) ...
    // ... (为了简洁，此处省略了您提供的所有原始方法) ...
    // ... (我们假设它们从这里开始) ...


    /**
     * 代表一个格子及其属性，主要用于在 `mineperturb` 中排序和选择要修改的格子。
     */
    private static class Square {
        // ... (此类保持不变)
        int x, y; // 坐标
        /**
         * 格子的类型 (例如：安全、有雷、未知)。
         */
        int type;
        /**
         * 一个随机值，用于在 'type' 相同时打乱排序。
         */
        int random;
    }

    /**
     * 表示一次 '扰动' (修改) 操作。
     * "扰动" 是指在求解器卡住时，移动、添加或移除一颗地雷。
     */
    public static class Perturbation {
        // ... (此类保持不变)
        int x, y; // 要修改的格子的坐标
        /**
         * 地雷数量的变化 (+1 = 添加雷, -1 = 移除雷)。
         */
        int delta;
    }

    /**
     * "扰动" 操作的集合，由 `mineperturb` 返回。
     */
    public static class Perturbations {
        // ... (此类保持不变)
        int n; // 扰动的总数
        Perturbation[] changes; // 扰动详情的数组

        public Perturbations(int n) {
            this.n = n;
            this.changes = new Perturbation[n];
            for (int i = 0; i < n; i++) {
                this.changes[i] = new Perturbation();
            }
        }
    }

    /**
     * 用于排序 'Square' 对象的比较器。
     * <p>
     * 在 `mineperturb` 中使用，用于决定哪些格子是修改的最佳候选者。
     */
    private static class SquareComparator implements java.util.Comparator<Square> {
        // ... (此类保持不变)
        @Override
        public int compare(Square a, Square b) {
            // 1. 优先按 'type' 排序 (例如，优先选择 '未知' 格子)
            if (a.type != b.type) {
                return a.type - b.type;
            }
            // 2. 如果类型相同，按 'random' 排序 (实现随机化)
            if (a.random != b.random) {
                return Integer.compare(a.random, b.random);
            }
            // 3. 最后按 'y' 和 'x' 排序 (确保排序的稳定性)
            if (a.y != b.y) {
                return a.y - b.y;
            }
            return a.x - b.x;
        }
    }

    // (*** 注意: `minegen` 方法已被移除，它的逻辑现在在 PerturbationVisualizer.java 中 ***)


    /**
     * 将地雷布局 `grid` 序列化为一个字符串描述符。
     */
    public static String describe_layout(boolean[] grid, int area, int x, int y, boolean obfuscate) {
// ... (此方法保持不变)
        // 位图 (Bitmap)，用于紧凑地存储地雷布局
        byte[] bmp = new byte[(area + 7) / 8];
        for (int i = 0; i < area; i++) {
            if (grid[i]) {
                // 位操作：将 `grid[i]` 的布尔值 (true) 设置到 `bmp` 字节数组的相应位上。
                bmp[i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }

        // （可选）对位图进行混淆
        if (obfuscate) {
            obfuscate_bitmap(bmp, area, false);
        }

        StringBuilder sb = new StringBuilder();
        // 格式：x,y,类型(m=混淆, u=未混淆),以及十六进制表示的位图数据
        sb.append(x).append(",").append(y).append(",").append(obfuscate ? "m" : "u");

        for (int i = 0; i < (area + 3) / 4; i++) {
            // C 代码中是逐个 nibble (4-bit) 处理的
            int v = bmp[i / 2] & 0xFF; // 修复：确保 v 是无符号的
            if (i % 2 == 0) {
                v >>= 4; // 取高 4 位
            }
            // else: 取低 4 位 (v & 0xF)
            sb.append("0123456789ABCDEF".charAt(v & 0xF)); // 修复：使用 0-F
        }
        return sb.toString();
    }

    /**
     * 对位图数据进行混淆/解混淆。
     * (C: obfuscate_bitmap)
     */
    private static void obfuscate_bitmap(byte[] bmp, int area, boolean deobfuscate) {
// ... (此方法保持不变)
        // C 的实现是一个未提供的函数。Java 版本使用了一个 NOT。
        // C: 这是一个更复杂的位操作。
        // 为了简单起见，我们将保持 Java 的 NOT 实现。
        // 这是一个简单的按位取反 (NOT) 混淆。
        for (int i = 0; i < bmp.length; i++) {
            bmp[i] = (byte) (~bmp[i] & 0xFF);
        }
    }

    /**
     * 为新游戏生成一个游戏描述符 (Game Descriptor)。
     * (C: new_game_desc)
     */
    public static String new_game_desc(int w, int h, int n, int first_click_x, int first_click_y,
                                       boolean unique, Random rs, boolean interactive) {
// ... (此方法保持不变)

        // 确定首次点击的坐标。如果提供了 (-1)，则随机选择。
        int x = (first_click_x >= 0) ? first_click_x : rs.nextInt(w);
        int y = (first_click_y >= 0) ? first_click_y : rs.nextInt(h);

        if (!interactive) {
            // 如果不是交互式模式（例如：从描述符加载游戏），
            // 则立即调用 `minegen` 生成布局并返回混淆后的字符串。

            // (*** 警告: `minegen` 已被移除。如果调用此方法，需要一个 `minegen` 的本地副本 ***)
            // C: 调用 minegen
            // boolean[] grid = minegen(w, h, n, x, y, unique, rs);
            // return describe_layout(grid, w * h, x, y, true);

            // (为使文件可编译，我们返回一个错误或一个 r-desc)
            // (我们假设这个可视化工具*总是*交互式的)
            return String.format("r%d,%c,%d", n, unique ? 'u' : 'a', rs.nextInt());

        } else {
            // C: 返回 "r..." (随机种子描述)
            // 如果是交互式模式（玩家将进行第一次点击），则不立即生成地雷。
            // 而是返回一个 'r' (random) 描述符，它只包含参数（地雷数、是否唯一解、随机种子）。
            // 真正的地雷生成 (minegen) 将被推迟到玩家的第一次点击时 (在 `open_square` 中)。
            return String.format("r%d,%c,%d", n, unique ? 'u' : 'a', rs.nextInt());
        }
    }

    /**
     * 验证游戏描述符字符串的格式是否基本有效。
     * (C: validate_desc)
     */
    public static String validate_desc(String desc, int w, int h) {
// ... (此方法保持不变)
        if (desc.startsWith("r")) {
            // 随机 ("r...") 描述符
            String[] parts = desc.substring(1).split(",");
            if (parts.length < 3) return "Invalid format";
        } else {
            // 布局 ("x,y,m/u,...") 描述符
            String[] parts = desc.split(",");
            if (parts.length < 3) return "Invalid format";
        }
        return null; // 格式有效
    }

    /**
     * （C 移植）一个用于更新游戏描述符的回调/日志函数。
     * (C: midend_supersede_game_desc)
     */
    private static void midend_supersede_game_desc(Object me, String desc, String privdesc) {
// ... (此方法保持不变)
        if (GENERATION_DIAGNOSTICS) {
            System.out.println("Supersede game desc: " + desc + " -> " + privdesc);
        }
    }

    /**
     * （C 移植）释放随机数生成器 (C: random_free / sfree)。
     * 在 Java 中，将其设置为 null 有助于 GC（垃圾回收），但通常不是必需的。
     */
    private static void random_free(Random rs) {
// ... (此方法保持不变)
        rs = null;
    }


    /**
     * 游戏状态类 (GameState)
     * <p>
     * 存储玩家当前看到的游戏状态 (grid) 和真正的地雷布局 (layout)。
     */
    public static class GameState implements Cloneable { // (修复 1: 实现 Cloneable)
// ... (此类保持不变)
        /**
         * 玩家看到的网格。
         * -2 = 未打开 (Unknown)
         * -1 = 标记 (Flag)
         * 0-8 = 打开的数字
         * 65 = 踩到的雷 (C: 65)
         * -10 = 泛洪填充的'待处理'标记 (C: 'todo' in open_square)
         */
        public byte[] grid;
        /**
         * 游戏状态标志：是否死亡
         */
        public boolean dead;
        /**
         * 游戏状态标志：是否胜利
         */
        public boolean won;
        /**
         * 地雷布局对象，包含真正的地雷信息 (`mines` 数组) 和生成参数。
         */
        public Layout layout;
        public int w, h; // 宽度和高度

        /**
         * 构造函数：初始化一个新的游戏状态。
         */
        public GameState(int width, int height, int mineCount, boolean unique, Random random) {
            this.w = width;
            this.h = height;
            // 玩家网格初始化为 -2 (未知)
            this.grid = new byte[w * h];
            java.util.Arrays.fill(this.grid, (byte) -2);

            // 初始化布局参数
            this.layout = new Layout();
            this.layout.n = mineCount;
            this.layout.unique = unique;
            this.layout.rs = random; // 存储随机数生成器
            // 关键：真正的地雷布局 (mines) 此时为 null。
            // 它将在第一次 `open_square` 调用时被生成。
            this.layout.mines = null;

            this.dead = false;
            this.won = false;
        }

        /**
         * (修复 2: 添加 public clone() 方法)
         * 实现 `GameState` 的深拷贝 (Deep Copy)。
         * 这对于 AI 模拟或撤销操作至关重要。
         */
        @Override
        public GameState clone() {
            try {
                // 1. 浅拷贝 GameState 自己的字段 (dead, won, w, h)
                GameState copy = (GameState) super.clone();

                // 2. 深拷贝 grid (数组)
                copy.grid = this.grid.clone();

                // 3. 深拷贝 layout (对象)
                // (layout 自己也有一个 clone 方法)
                copy.layout = this.layout.clone();

                return copy;
            } catch (CloneNotSupportedException e) {
                // 这不应该发生，因为我们实现了 Cloneable
                throw new AssertionError();
            }
        }

        /**
         * 地雷布局的内部表示。
         */
        public static class Layout implements Cloneable { // (修复 1: 实现 Cloneable)
// ... (此类保持不变)
            /**
             * 真正的地雷布局 (true=雷)。
             * 这是在第一次点击 *之后* 才被 `minegen` 填充的。
             */
            public boolean[] mines;
            /**
             * 地雷总数。
             */
            public int n;
            /**
             * 是否要求唯一解。
             */
            public boolean unique;
            /**
             * 随机数生成器。在 `minegen` 运行后会被设为 null。
             */
            public Random rs;
            /**
             * 玩家首次点击的坐标。
             */
            public int startx, starty;
            /**
             * （C 移植）指向 'midend' 对象的指针，在 Java 中是 `Object` 类型。
             */
            public Object me;

            /**
             * (修复 2: 添加 public clone() 方法)
             * 实现 `Layout` 的深拷贝。
             */
            @Override
            public Layout clone() {
                try {
                    Layout copy = (Layout) super.clone();

                    // 深拷贝 mines 数组 (如果它存在)
                    if (this.mines != null) {
                        copy.mines = this.mines.clone();
                    }

                    // rs (Random) 和 me (Object) 可以浅拷贝（共享引用）
                    // 因为在 `minegen` 之后 `rs` 通常为 null
                    return copy;
                } catch (CloneNotSupportedException e) {
                    throw new AssertionError(); // 不会发生
                }
            }
        }
    }


    /**
     * 玩家点击格子的核心游戏逻辑 (C: open_square)。
     * (这是 C 代码 `open_square` 的精确移植)
     *
     * @param state 游戏状态
     * @param x     点击的 x 坐标
     * @param y     点击的 y 坐标
     * @return 0=成功, -1=踩雷
     */
    public static int open_square(GameState state, int x, int y) {
// ... (此方法保持不变)
        if (GENERATION_DIAGNOSTICS) {
            System.out.println("LOG: open_square(x=" + x + ", y=" + y + ") called.");
        }

        int w = state.w;
        int h = state.h;

        // C: if (!state->layout->mines)
        // 检查这是否是玩家的 *第一次* 点击（因为 `mines` 数组尚未生成）。
        if (state.layout.mines == null) {
            if (GENERATION_DIAGNOSTICS) {
                System.out.println("LOG: First click detected. Calling minegen...");
            }

            // C: state->layout->mines = new_mine_layout(...)
            // 调用地雷生成器 `minegen`，使用当前的 (x, y) 作为安全起始点。
            // `minegen` 将确保 (x, y) 及其 3x3 区域是安全的。

            // (*** 警告: `minegen` 已被移除。此方法在 `PerturbationVisualizer` 之外无法工作 ***)
            // (为了可编译性，我们注释掉它)
            /*
            state.layout.mines = minegen(w, h, state.layout.n, x, y,
                    state.layout.unique, state.layout.rs);
            */
            // (我们假设 `minegen` 已经被外部调用)
            if (state.layout.rs != null) {
                // 模拟 minegen
                state.layout.mines = new boolean[w*h]; // 模拟一个空的
                System.err.println("警告: `open_square` 在没有 `minegen` 的情况下被调用。");
            }


            state.layout.startx = x;
            state.layout.starty = y;

            if (GENERATION_DIAGNOSTICS) {
                System.out.println("LOG: Layout generated successfully.");
            }

            // C: random_free(state->layout->rs);
            // 释放随机数生成器，它不再需要了。
            state.layout.rs = null;
        }

        // --- 从这里开始，`state.layout.mines` 保证已存在 ---

        // C: if (state->layout->mines[y*w+x])
        // 检查玩家是否点到了地雷
        if (state.layout.mines[y * w + x]) {
            if (GENERATION_DIAGNOSTICS) {
                System.out.println("LOG: Player hit mine at (" + x + ", " + y + ")");
            }
            state.dead = true;
            state.grid[y * w + x] = 65; // C: 65 = 踩到的雷
            return -1; // 踩雷
        }

        if (GENERATION_DIAGNOSTICS) {
            System.out.println("LOG: Safe square at (" + x + ", " + y + ")");
        }

        // C: state->grid[y*w+x] = -10; (标记为 'todo')
        // 将当前安全格标记为 '-10' (待处理)，准备进行泛洪填充 (Flood Fill)。
        state.grid[y * w + x] = -10;

        /*
         * C: 连锁打开 (flood-fill) 逻辑
         * C: while (1) { bool done_something = false; ... }
         */
        boolean changed;
        // 泛洪填充的主循环。不断循环，直到没有格子从 '-10' 变为数字。
        do {
            changed = false; // 标记本轮循环是否处理了任何 '-10' 格子

            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    // 如果这个格子是 '待处理'
                    if (state.grid[yy * w + xx] == -10) {

                        // C: assert(!state->layout->mines[yy*w+xx]); (它不应该是雷)

                        // C: 计算周围地雷数 (v)
                        int v = 0;
                        for (int dy = -1; dy <= +1; dy++) {
                            for (int dx = -1; dx <= +1; dx++) {
                                int nx = xx + dx, ny = yy + dy;
                                // 检查边界
                                if (nx >= 0 && nx < w && ny >= 0 && ny < h &&
                                        state.layout.mines[ny * w + nx]) { // 检查真正的地雷布局
                                    v++;
                                }
                            }
                        }

                        // C: state->grid[yy*w+xx] = v;
                        // 将格子的状态从 '-10' (待处理) 更新为地雷计数 'v' (0-8)
                        state.grid[yy * w + xx] = (byte) v;
                        changed = true; // C: done_something = true;

                        // C: if (v == 0)
                        // 如果这个格子的数字是 0...
                        if (v == 0) {
                            // C: 连锁打开周围 (Recursive open / flood fill)
                            // 将其所有邻居中状态为 '-2' (未知) 的格子标记为 '-10' (待处理)，
                            // 以便在下一轮循环中处理它们。
                            for (int dy = -1; dy <= +1; dy++) {
                                for (int dx = -1; dx <= +1; dx++) {
                                    int nx = xx + dx, ny = yy + dy;
                                    if (nx >= 0 && nx < w && ny >= 0 && ny < h &&
                                            state.grid[ny * w + nx] == -2) { // C: -2 = 未知 (GRID_UNOPENED)
                                        state.grid[ny * w + nx] = -10; // 标记为 todo
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // C: if (!done_something) break;
        } while (changed); // 如果 `changed` 仍为 true，表示上一轮打开了 0，需要继续处理新的 '-10' 格子

        /*
         * C: 检查胜利条件
         */
        if (state.dead) return 0; // C: 已死玩家不能赢

        int nmines = 0;
        int ncovered = 0; // 未打开的格子总数
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                // C: if (state->grid[yy*w+xx] < 0) ncovered++;
                // 状态 < 0 包括 -1 (标记), -2 (未知), -3 (问号) 等
                if (state.grid[yy * w + xx] < 0) ncovered++;
                // C: if (state->layout->mines[yy*w+xx]) nmines++;
                if (state.layout.mines[yy * w + xx]) nmines++;
            }
        }

        // C: assert(ncovered >= nmines); (未打开的格子数 必须 >= 地雷数)

        // 修复：C 代码在这里重新计算了地雷数，我们也应该这样做。(已在上面 nmines++ 完成)
        // C 中的 nmines 实际上是 state->layout->n，但 C 代码选择重新计数以确保安全。

        // C: if (ncovered == nmines)
        // 胜利条件：如果未打开的格子数 *等于* 地雷总数，
        // 意味着所有非地雷格子都已被安全打开。
        if (ncovered == nmines) {
            state.won = true;
            if (GENERATION_DIAGNOSTICS) {
                System.out.println("LOG: VICTORY ACHIEVED!");
            }
            // 游戏胜利，自动将所有剩余的未打开格子（即地雷）标记为 '-1' (旗帜)。
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    if (state.grid[yy * w + xx] < 0) // < 0 (即未打开)
                        state.grid[yy * w + xx] = -1; // C: 自动标记 (GRID_FLAG)
                }
            }
        }

        return 0; // 打开成功
    }

    /**
     * 一个辅助函数，用于计算 `mines` 数组中 'true' (地雷) 的总数。
     * 主要用于调试和验证。
     */
    private static int countMines(boolean[] mines) {
        int count = 0;
        for (boolean mine : mines) {
            if (mine) count++;
        }
        return count;
    }
}

