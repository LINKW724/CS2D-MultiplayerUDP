package cs2d.playerAndAi.doublePlayer.mines3;

import cs2d.playerAndAi.doublePlayer.mines3.MineGenerator.MineCtx;
import cs2d.playerAndAi.doublePlayer.mines3.MineGenerator.StepSolveContext; // (新增) 导入

import java.util.*;

/**
 * 扫雷求解器 (MineSolver) - 修复和合并版本
 *
 * <p>这是一个功能齐全的求解器和扰动器，基于 C 代码 (mines.c) 移植。
 * 它现在包含 minesolve, mineperturb, 和 mineopen 的静态实现。
 *
 * <p>核心功能是 {@link #minesolve_internal}，它是一个复杂的逻辑推理引擎，
 * 用于找出棋盘上可以安全打开或必须标记为地雷的方块。
 *
 * (注意: 此文件已被修改以支持 '逐步求解' 挂钩。)
 */
public class MineSolver {

    /**
     * 控制是否打印求解器内部的详细诊断日志。
     */
    private static final boolean SOLVER_DIAGNOSTICS = false; // 设置为 false 以减少日志

    //====================================================
    // 回调接口 (Callbacks)
    //====================================================

    /**
     * "打开方块" 回调接口 (C: open_cb)。
     */
    @FunctionalInterface
    public interface MineOpener {
        /**
         * @return 该方块的数字 (0-8)，如果踩雷则返回 -1。
         */
        byte open(MineGenerator.MineCtx ctx, int x, int y);
    }

    /**
     * "扰动布局" 回调接口 (C: perturb_cb)。
     */
    @FunctionalInterface
    public interface MinePerturber {
        Perturbations perturb(Object ctx, byte[] grid, int setx, int sety, int mask);
    }

    //====================================================
    // 内部数据结构 (移植自 C 和 MineGenerator)
    //====================================================

    /**
     * C: struct perturbation
     * <p>
     * 表示一次单独的地雷布局修改（扰动）。
     */
    public static class Perturbation {
        int x, y; // 坐标
        /**
         * 变化量: +1 = 变为地雷; -1 = 变为安全
         */
        int delta;
    }

    /**
     * C: struct perturbations
     * <p>
     * {@link #mineperturb} 函数返回的扰动操作的集合。
     */
    public static class Perturbations {
        int n; // 扰动的总数
        Perturbation[] changes; // 扰动数组

        public Perturbations(int n) {
            this.n = n;
            this.changes = new Perturbation[n];
            for (int i = 0; i < n; i++) {
                this.changes[i] = new Perturbation();
            }
        }
    }

    /**
     * C: struct square (用于 mineperturb)
     * <p>
     * 在 `mineperturb` 中用于对 "候选方块" (可以被修改的方块) 进行排序。
     */
    private static class Square {
        int x, y; // 坐标
        /**
         * 方块类型 (1=靠近已知, 2=未知, 3=已打开)。
         * 排序时，类型 1 (靠近已知) 的方块是首选的交换目标。
         */
        int type;
        /**
         * 随机值，用于在 type 相同时打乱排序。
         */
        int random;
    }

    /**
     * C: qsort 比较器 (用于 mineperturb)
     * <p>
     * 用于对 `Square` 列表进行排序，以决定 `mineperturb` 中最佳的交换候选者。
     */
    private static class SquareComparator implements Comparator<Square> {
        @Override
        public int compare(Square a, Square b) {
            // 1. 优先按 'type' 排序 (优先选择 type 1)
            if (a.type != b.type) {
                return a.type - b.type;
            }
            // 2. 按 'random' 排序 (随机化)
            if (a.random != b.random) {
                return Integer.compare(a.random, b.random);
            }
            // 3. 按 'y' 和 'x' 排序 (稳定性)
            if (a.y != b.y) {
                return a.y - b.y;
            }
            return a.x - b.x;
        }
    }

    /**
     * C: struct set 的 Java 表示
     * <p>
     * 这是一个 "约束集"。它是求解器中最重要的概念。
     * 它代表一个逻辑约束，例如："这 3 个未知的方块 (由 mask 定义)
     * 周围恰好有 2 颗地雷 (mines)"。
     * (x, y) 是 3x3 掩码 (mask) 的左上角坐标。
     */
    static class Set {
        int x, y; // 约束集的基准坐标 (左上角)
        /**
         * 3x3 掩码 (bitmask)，指示这个约束集包含哪些 *未知* 方块。
         * (1=包含, 0=不包含)
         */
        int mask;
        /**
         * 这个约束集 (mask) 中包含的地雷数量。
         */
        int mines;

        /**
         * 标记此集合是否在 "待处理" 链表 (todoList) 中。
         */
        boolean todo;
        Set prev, next; // (在 SetStore 的 LinkedList 实现中未使用)
    }

    /**
     * C: struct setstore 的 Java 实现
     * <p>
     * 用于存储和管理所有的 "约束集" ({@link Set})。
     * C 代码使用了一个复杂的 2-3-4 树 (tree234) 和一个自定义链表。
     * 这个 Java 版本使用 {@link TreeMap} 和 {@link LinkedList} 来模拟相同的功能。
     */
    static class SetStore {
        /**
         * C: tree234 *sets; -> Java: TreeMap
         * 存储所有的约束集，使用 "x:y:mask" 作为键 (Key)。
         */
        private TreeMap<String, Set> sets = new TreeMap<>();
        /**
         * C: struct set *todo_head, *todo_tail; -> Java: LinkedList
         * 存储所有 "待处理" 的约束集。
         */
        private LinkedList<Set> todoList = new LinkedList<>();

        /**
         * 为 Set 生成唯一的 TreeMap 键。
         */
        private String getKey(int x, int y, int mask) {
            return x + ":" + y + ":" + mask;
        }
        private String getKey(Set s) {
            return getKey(s.x, s.y, s.mask);
        }

        /**
         * C: ss_add
         * 添加一个新的约束集 (Set)。
         */
        public void add(int x, int y, int mask, int mines) {
            Set s = new Set();
            s.x = x; s.y = y; s.mask = mask; s.mines = mines; s.todo = false;

            String key = getKey(s);
            if (sets.containsKey(key)) {
                return; // C: add234 发现重复，释放
            }
            sets.put(key, s); // 存入 TreeMap
            addTodo(s); // C: add234 成功，添加到todo
        }

        /**
         * C: ss_remove
         * 移除一个约束集。
         */
        public void remove(Set s) {
            if (s.todo) {
                todoList.remove(s); // C: 从 todo 链表移除
                s.todo = false;
            }
            sets.remove(getKey(s)); // C: del234 (从树中删除)
        }

        /**
         * C: ss_add_todo
         * 将一个 (通常是已存在的) 集合添加到 "待处理" 列表。
         */
        public void addTodo(Set s) {
            if (!s.todo) {
                s.todo = true;
                todoList.addLast(s); // C: 添加到 todo_tail
            }
        }

        /**
         * C: ss_todo
         * 从 "待处理" 列表中获取并移除下一个集合。
         */
        public Set todo() {
            if (todoList.isEmpty()) {
                return null; // C: todo_head == NULL
            }
            Set s = todoList.removeFirst(); // C: 从 todo_head 移除
            s.todo = false;
            return s;
        }

        /**
         * C: ss_overlap
         * 查找并返回所有与给定区域 (x, y, mask) *重叠* (有交集) 的约束集。
         */
        public List<Set> overlap(int x, int y, int mask) {
            List<Set> result = new ArrayList<>();
            // (这是 O(N) 的实现。C 的 tree234 是 O(log N + k)
            //  但对于可视化工具来说足够快了)
            for (Set s : sets.values()) {
                if (setmunge(x, y, mask, s.x, s.y, s.mask, false) != 0) {
                    result.add(s);
                }
            }
            return result;
        }

        /** C: count234 (获取集合总数) */
        public int countSets() {
            return sets.size();
        }

        /** C: index234 (按索引获取集合 - 效率低下) */
        public Set getSetByIndex(int index) {
            if (index < 0 || index >= sets.size()) {
                return null;
            }
            // 效率低下 (O(n))，但功能正确
            return new ArrayList<>(sets.values()).get(index);
        }

        /** C: delpos234 (按索引删除集合 - 效率低下) */
        public Set deleteSetAtPosition(int index) {
            Set s = getSetByIndex(index);
            if (s != null) {
                remove(s);
            }
            return s;
        }

        /** C: freetree234 (清空所有集合) */
        public void freeTree() {
            sets.clear();
            todoList.clear();
        }
    }

    /**
     * C: struct squaretodo
     * <p>
     * 这是一个 "待处理的已知方块" 列表。
     */
    static class SquareTodo {
        int[] next; // C: int *next (next[i] 指向下一个元素的索引)
        int head, tail; // 链表的头和尾索引

        public void initialize(int size) {
            this.next = new int[size];
            this.head = -1;
            this.tail = -1;
        }

        /**
         * C: std_add
         * 将一个方块的 *索引* (i) 添加到待处理链表的末尾。
         */
        public void add(int i) {
            next[i] = -1; // 成为新的尾部，所以 next 是 -1
            if (tail != -1) {
                next[tail] = i; // 旧的尾部指向 i
            } else {
                head = i; // 列表为空，设置头部
            }
            tail = i; // i 成为新的尾部
        }
    }

    //====================================================
    // 核心 C 函数的 Java 移植
    //====================================================

    /**
     * C: bitcount16
     * 计算一个 16 位掩码中有多少个 '1'。
     */
    private static int bitcount16(int mask) {
        // Java 的 Integer.bitCount 效率更高
        return Integer.bitCount(mask & 0xFFFF);
    }

    /**
     * C: setmunge (核心算法)
     * <p>
     * "集合修改/混合" 函数。这是 C 求解器中最核心和最复杂的位操作函数。
     */
    private static int setmunge(int x1, int y1, int mask1, int x2, int y2, int mask2, boolean diff) {
        // C 逻辑：将 (x2,y2,mask2) 坐标平移到 (x1,y1)

        // 1. 检查：如果两个 3x3 集合相距太远 (>=3)，它们肯定没有交集。
        if (Math.abs(x2-x1) >= 3 || Math.abs(y2-y1) >= 3) {
            mask2 = 0; // 视为无交集
        } else {
            // 2. 平移 X 坐标
            while (x2 > x1) { mask2 &= ~(4|32|256); mask2 <<= 1; x2--; }
            while (x2 < x1) { mask2 &= ~(1|8|64); mask2 >>= 1; x2++; }
            // 3. 平移 Y 坐标
            while (y2 > y1) { mask2 &= ~(64|128|256); mask2 <<= 3; y2--; }
            while (y2 < y1) { mask2 &= ~(1|2|4); mask2 >>= 3; y2++; }
        }

        // 4. 计算差集或交集
        if (diff) {
            // 差集 (s1 - s2) 在位操作中 = s1 & (~s2)
            mask2 ^= 511; // 511 = 0b111111111 (即 ~mask2)
        }

        // C: return mask1 & mask2;
        return mask1 & mask2; // 返回 s1 & (平移后的 mask2)
    }

    /**
     * C: known_squares (辅助函数)
     * <p>
     * 当一个约束集 (mask) 被完全解开时调用此函数。
     */
    private static void known_squares(int w, int h, SquareTodo std, byte[] grid,
                                      MineOpener open, MineGenerator.MineCtx ctx,
                                      int x, int y, int mask, boolean is_mine,
                                      boolean isHintMode) // (新增)
    {
        int bit = 1;

        // C: 3x3 循环 (遍历掩码 mask)
        for (int yy = 0; yy < 3; yy++) {
            for (int xx = 0; xx < 3; xx++) {
                if ((mask & bit) != 0) { // 如果掩码的这一位是 '1'
                    int nx = x + xx;
                    int ny = y + yy;

                    // 检查边界
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        int i = ny * w + nx;

                        // C: if (grid[i] == -2)
                        if (grid[i] == -2) { // 关键：只处理*未知*方块
                            if (is_mine) {
                                grid[i] = -1; // 标记为地雷 (Flag)
                                std.add(i); // (修复) 标记为雷后，应加入队列以便进一步推导
                            } else {
                                // --- (核心修改) ---
                                // 这个方块是安全的
                                if (isHintMode) {
                                    // (新增) 提示模式 (Hint Mode)
                                    grid[i] = -3; // 标记为 "安全-未揭开" (GRID_SAFE)
                                } else {
                                    // 自动求解模式 (原始"作弊"逻辑)
                                    // (用于 minegen 验证)

                                    // (*** 关键修复 ***)
                                    // 检查 ctx 是否为 null (例如在 hint 模式下)
                                    if (ctx == null) {
                                        // 理论上不应该在 !isHintMode 时发生，但作为安全检查
                                        grid[i] = 0; // 假设为 0
                                    } else {
                                        grid[i] = open.open(ctx, nx, ny); // 调用回调，获取数字
                                    }
                                    assert(grid[i] != -1); // 不应该是雷
                                    std.add(i); // 加入队列以进行链式反应
                                }
                                // --- (修改结束) ---
                            }
                        }
                    }
                }
                bit <<= 1; // 移动到下一位
            }
        }
    }
    /**
     * C: random_upto
     * 返回 [0, n-1] 范围内的一个随机整数。
     */
    private static int random_upto(Random rs, int n) {
        if (n <= 0) return 0;
        return rs.nextInt(n); // Java 的 nextInt(n) 返回 [0, n-1]
    }


    /**
     * C: mineopen (递归打开)
     * <p>
     * 这是求解器 *模拟* 打开时使用的函数 ({@link MineOpener} 回调的实现)。
     * (移植自 C: mineopen)
     *
     * @param ctx 包含真实地雷布局 (ctx.grid) 的上下文
     * @return 该方块的数字 (0-8)，如果踩雷则返回 -1。
     */
    public static byte mineopen(MineGenerator.MineCtx ctx, int x, int y) {

        // (*** 关键修复 ***)
        // 检查 ctx 是否为 null。这在 PerturbationVisualizer 的 lambda 表达式中
        // 曾导致 NullPointerException
        if (ctx == null) {
            System.err.println("错误: mineopen 接收到了 null 上下文！");
            return 0; // 返回一个安全值
        }
        // (*** 修复结束 ***)

        if (x < 0 || x >= ctx.w || y < 0 || y >= ctx.h) {
            return -1; // 越界
        }

        // C: if (ctx->grid[y * ctx->w + x]) return -1; /* *bang* */
        if (ctx.grid[y * ctx.w + x]) {
            return -1; // 踩到地雷
        }

        // C: if (!ctx->opened[y * ctx->w + x]) { ... }
        if (!ctx.opened[y * ctx.w + x]) {
            ctx.opened[y * ctx.w + x] = true;
            ctx.nperturbs_since_last_new_open = 0;
        }

        // C: 计算周围地雷数 (n)
        int n = 0;
        for (int i = -1; i <= +1; i++) {
            if (x + i < 0 || x + i >= ctx.w) continue;
            for (int j = -1; j <= +1; j++) {
                if (y + j < 0 || y + j >= ctx.h) continue;
                if (i == 0 && j == 0) continue; // 不计算自己
                if (ctx.grid[(y + j) * ctx.w + (x + i)]) {
                    n++;
                }
            }
        }

        // C: return n;
        return (byte) n;
    }


    /**
     * C: mineperturb (地雷布局扰动)
     * <p>
     * (*** 已修改: 此版本*只计算*扰动方案，*不应用*它们 ***)
     * (应用步骤已被移至 minesolve_internal)
     */
    public static Perturbations mineperturb(Object vctx, byte[] grid,
                                            int setx, int sety, int mask) {

        // (*** 修改 ***) 提取 MineCtx，无论 vctx 是 StepSolveContext 还是 MineCtx
        MineGenerator.MineCtx ctx;
        if (vctx instanceof StepSolveContext) {
            ctx = ((StepSolveContext) vctx).mineCtx;
        } else {
            ctx = (MineGenerator.MineCtx) vctx;
        }
        // (*** 修改结束 ***)

        Square[] sqlist;
        int x, y, dx, dy, i, n;
        int nfull, nempty; // "卡住" 区域的 (地雷数, 安全数)
        Square[] tofill, toempty, todo; // "候选" 区域的 (可填充=安全, 可清空=地雷)
        int ntofill, ntoempty, ntodo; // 候选区的数量
        int dtodo, dset; // 变化方向 (+1 或 -1)
        Perturbations ret;
        int[] setlist;

        /*
         * 第一阶段：扰动条件检查
         */
        if (mask == 0 && !ctx.allow_big_perturbs) {
            if (SOLVER_DIAGNOSTICS) { System.out.println("LOG: big perturbs forbidden on this run"); }
            return null; // 不允许大扰动
        }
        if (ctx.nperturbs_since_last_new_open++ > ctx.w ||
                ctx.nperturbs_since_last_new_open++ > ctx.h) {
            if (SOLVER_DIAGNOSTICS) { System.out.println("LOG: too many perturb attempts without opening a new square"); }
            return null;
        }

        /*
         * 第二阶段：构建候选方块列表
         */
        sqlist = new Square[ctx.w * ctx.h];
        n = 0;
        for (y = 0; y < ctx.h; y++) {
            for (x = 0; x < ctx.w; x++) {
                // 1. 不能修改起始点 3x3 区域
                if (Math.abs(y - ctx.sy) <= 1 && Math.abs(x - ctx.sx) <= 1) continue;
                // 2. 不能修改 "卡住" 的集合自身
                if ((mask == 0 && grid[y * ctx.w + x] == -2) ||
                        (x >= setx && x < setx + 3 && y >= sety && y < sety + 3 && (mask & (1 << ((y - sety) * 3 + (x - setx)))) != 0))
                    continue;

                // 3. 将其添加到候选列表
                sqlist[n] = new Square();
                sqlist[n].x = x; sqlist[n].y = y;
                // 4. 分配类型 (Type)
                if (grid[y * ctx.w + x] != -2) {
                    sqlist[n].type = 3; // Type 3: 已打开的方块 (最不优先)
                } else {
                    sqlist[n].type = 2; // Type 2: 远离的未知方块
                    // 检查是否靠近任何已打开的方块
                    for (dy = -1; dy <= +1; dy++) {
                        for (dx = -1; dx <= +1; dx++) {
                            if (x + dx >= 0 && x + dx < ctx.w && y + dy >= 0 && y + dy < ctx.h &&
                                    grid[(y + dy) * ctx.w + (x + dx)] != -2) {
                                sqlist[n].type = 1; // Type 1: 靠近已知 (最优先)
                                break;
                            }
                        }
                        if (sqlist[n].type == 1) break;
                    }
                }
                sqlist[n].random = ctx.rs.nextInt();
                n++;
            }
        }
        // C: qsort(sqlist, n, sizeof(struct square), squarecmp);
        Arrays.sort(sqlist, 0, n, new SquareComparator());


        /*
         * 第三阶段：分析 "卡住" 的扰动集合
         */
        if (SOLVER_DIAGNOSTICS) { System.out.print("LOG: perturb wants to fill or empty these squares:"); }
        nfull = nempty = 0;

        if (mask != 0) {
            // C: 遍历 "卡住" 集合 (mask)
            for (dy = 0; dy < 3; dy++) {
                for (dx = 0; dx < 3; dx++) {
                    if ((mask & (1 << (dy * 3 + dx))) != 0) {
                        if (SOLVER_DIAGNOSTICS) { System.out.printf(" (%d,%d)", setx + dx, sety + dy); }
                        assert (setx + dx < ctx.w); assert (sety + dy < ctx.h);
                        if (ctx.grid[(sety + dy) * ctx.w + (setx + dx)]) nfull++; // "卡住" 区域的地雷
                        else nempty++; // "卡住" 区域的安全格
                    }
                }
            }
        } else {
            // C: mask == 0 (大扰动)，检查所有未知方块
            for (y = 0; y < ctx.h; y++) {
                for (x = 0; x < ctx.w; x++) {
                    if (grid[y * ctx.w + x] == -2) { // 玩家视角的未知方块
                        if (SOLVER_DIAGNOSTICS) { System.out.printf(" (%d,%d)", x, y); }
                        if (ctx.grid[y * ctx.w + x]) nfull++;
                        else nempty++;
                    }
                }
            }
        }
        if (SOLVER_DIAGNOSTICS) { System.out.printf("\nLOG: perturb set includes %d full, %d empty\n", nfull, nempty); }

        /*
         * 第四阶段：寻找交换候选
         */
        ntofill = ntoempty = 0;
        if (mask != 0) {
            tofill = new Square[9]; toempty = new Square[9];
        } else {
            tofill = new Square[ctx.w * ctx.h]; toempty = new Square[ctx.w * ctx.h];
        }

        // 遍历排序后的候选列表 (sqlist)
        for (i = 0; i < n; i++) {
            Square sq = sqlist[i];
            if (ctx.grid[sq.y * ctx.w + sq.x]) {
                if (ntoempty < toempty.length) toempty[ntoempty++] = sq;
            } else {
                if (ntofill < tofill.length) tofill[ntofill++] = sq;
            }
            // (遵循 C 代码的 `||` 逻辑)
            if (ntofill == nfull || ntoempty == nempty)
                break;
        }

        if (SOLVER_DIAGNOSTICS) { System.out.printf("LOG: can fill %d (of %d) or empty %d (of %d)\n", ntofill, nfull, ntoempty, nempty); }

        /*
         * 第五阶段：处理部分交换情况 (Partial Swap)
         */
        if (ntofill != nfull && ntoempty != nempty) {
            if (ntoempty == 0) {
                if (SOLVER_DIAGNOSTICS) { System.out.println("LOG: mineperturb failed: Not enough candidates (ntoempty=0)."); }
                return null;
            }

            int k;
            setlist = new int[ctx.w * ctx.h]; // 存储 "卡住" 区域的安全格 (索引)
            i = 0;
            if (mask != 0) {
                // 遍历 "卡住" 集合
                for (dy = 0; dy < 3; dy++) {
                    for (dx = 0; dx < 3; dx++) {
                        if ((mask & (1 << (dy * 3 + dx))) != 0) {
                            if (!ctx.grid[(sety + dy) * ctx.w + (setx + dx)]) {
                                setlist[i++] = (sety + dy) * ctx.w + (setx + dx);
                            }
                        }
                    }
                }
            } else {
                // (大扰动) 遍历所有未知方块
                for (y = 0; y < ctx.h; y++) {
                    for (x = 0; x < ctx.w; x++) {
                        if (grid[y * ctx.w + x] == -2) {
                            if (!ctx.grid[y * ctx.w + x]) { // 真实布局是安全格
                                setlist[i++] = y * ctx.w + x;
                            }
                        }
                    }
                }
            }
            if (i <= ntoempty) {
                if (SOLVER_DIAGNOSTICS) { System.out.println("LOG: mineperturb failed: C assert(i > ntoempty) failed. (i=" + i + ", ntoempty=" + ntoempty + ")"); }
                return null;
            }

            // Fisher-Yates 洗牌：从 `i` (nempty) 个安全格中，随机选择 `ntoempty` 个。
            if (SOLVER_DIAGNOSTICS) { System.out.print("LOG: doing a partial fill:"); }
            for (k = 0; k < ntoempty; k++) {
                int index = k + ctx.rs.nextInt(i - k); // 随机选择
                int tmp = setlist[k]; setlist[k] = setlist[index]; setlist[index] = tmp;
                if (SOLVER_DIAGNOSTICS) { System.out.printf(" (%d,%d)", setlist[k] % ctx.w, setlist[k] / ctx.w); }
            }
            if (SOLVER_DIAGNOSTICS) { System.out.println(); }
        } else {
            setlist = null; // C: setlist = NULL (表示完全交换，非部分)
        }

        /*
         * 第六阶段：构建扰动操作列表 (ret)
         */
        if (ntofill == nfull) {
            todo = tofill; ntodo = ntofill; dtodo = +1; dset = -1;
        } else {
            todo = toempty; ntodo = ntoempty; dtodo = -1; dset = +1;
        }

        ret = new Perturbations(2 * ntodo); // (分配 2 * ntodo，因为是 "交换")

        // 1. 添加 "候选区" (todo) 的更改
        for (i = 0; i < ntodo; i++) {
            ret.changes[i].x = todo[i].x;
            ret.changes[i].y = todo[i].y;
            ret.changes[i].delta = dtodo;
        }

        // 2. 添加 "卡住区" (set) 的更改
        if (setlist != null) {
            // C: 部分交换
            int j; assert (todo == toempty);
            for (j = 0; j < ntoempty; j++) {
                ret.changes[i].x = setlist[j] % ctx.w;
                ret.changes[i].y = setlist[j] / ctx.w;
                ret.changes[i].delta = dset; // dset = +1
                i++;
            }
        } else if (mask != 0) {
            // C: 完全交换 (小扰动)
            for (dy = 0; dy < 3; dy++) {
                for (dx = 0; dx < 3; dx++) {
                    if ((mask & (1 << (dy * 3 + dx))) != 0) {
                        int currval = (ctx.grid[(sety + dy) * ctx.w + (setx + dx)] ? +1 : -1);
                        if (dset == -currval) {
                            ret.changes[i].x = setx + dx;
                            ret.changes[i].y = sety + dy;
                            ret.changes[i].delta = dset;
                            i++;
                        }
                    }
                }
            }
        } else {
            // C: 完全交换 (大扰动)
            for (y = 0; y < ctx.h; y++) {
                for (x = 0; x < ctx.w; x++) {
                    if (grid[y * ctx.w + x] == -2) { // 遍历所有未知方块
                        int currval = (ctx.grid[y * ctx.w + x] ? +1 : -1);
                        if (dset == -currval) {
                            ret.changes[i].x = x;
                            ret.changes[i].y = y;
                            ret.changes[i].delta = dset;
                            i++;
                        }
                    }
                }
            }
        }
        assert (i == ret.n);

        /*
         * 第七阶段：(*** 已移除 ***)
         *
         * 在这个版本中，`mineperturb` *不*应用更改。
         * 它只返回 `ret` (扰动方案)。
         *
         * `minesolve_internal` 将负责在动画播放后应用这些更改。
         */

        return ret;
    }


    /**
     * C: 更新 `mineperturb` 中玩家视角的辅助函数
     * <p>
     * (*** 注意: 此函数现在由 minesolve_internal 在动画*之后*调用 ***)
     */
    private static void updateGridAfterPerturbation(MineGenerator.MineCtx ctx, byte[] grid, int x, int y, int delta) {

        // C: 遍历 3x3 区域 (包括中心点)
        for (int dy = -1; dy <= +1; dy++) {
            for (int dx = -1; dx <= +1; dx++) {
                int nx = x + dx;
                int ny = y + dy;

                // 检查边界 且 只更新已知的方块
                if (nx >= 0 && nx < ctx.w && ny >= 0 && ny < ctx.h &&
                        grid[ny * ctx.w + nx] != -2) { // C: 只更新已知格子

                    if (dx == 0 && dy == 0) {
                        // C: 中心方块 (x,y) 自身
                        if (delta > 0) {
                            // (变为地雷)
                            grid[y * ctx.w + x] = -1; // C: 放置地雷 -> 标记
                        } else {
                            // (变为安全)
                            // C: 移除地雷 -> 重新计算数字
                            grid[y * ctx.w + x] = mineopen(ctx, x, y);
                        }
                    }
                    else {
                        // C: 相邻方块 (nx, ny)
                        if (grid[ny * ctx.w + nx] >= 0) {
                            // 修复：不能简单地 += delta。必须重新计算。
                            grid[ny * ctx.w + nx] = mineopen(ctx, nx, ny);
                        }
                    }
                }
            }
        }
    }


    /**
     * 扫雷求解器主入口点 (C: minesolve)
     * (这是 `minesolve_full` 的修复和重命名版本)
     */
    public static int minesolve(int w, int h, int n, byte[] grid,
                                MineOpener open,
                                MinePerturber perturb,
                                Object ctx, // (保持 Object 类型以接收 StepSolveContext)
                                Random rs)
    {
        // 调用内部实现，使用 "作弊" (链式) 模式 (isHintMode = false)
        return minesolve_internal(w, h, n, grid, open, perturb, ctx, rs, false);
    }

    /**
     * 扫雷求解器内部核心实现
     * (C: minesolve)
     *
     * (*** 已修改以支持暂停、动画和线程修复 ***)
     */
    private static int minesolve_internal(int w, int h, int n, byte[] grid,
                                          MineOpener open,
                                          MinePerturber perturb,
                                          Object ctx, // (ctx 可能是 MineCtx 或 StepSolveContext)
                                          Random rs,
                                          boolean isHintMode) // (新增)
    {
        SetStore ss = new SetStore(); // 约束集管理器
        List<Set> list;
        SquareTodo std = new SquareTodo(); // 已知方块待处理列表
        int x, y, i, j;
        int nperturbs = 0; // 扰动次数

        // --- (*** 关键修复 ***) ---
        // 提取真正的 MineCtx，无论它是否被包装
        final MineCtx realMineCtx;
        if (ctx instanceof StepSolveContext) {
            realMineCtx = ((StepSolveContext) ctx).mineCtx;
        } else if (ctx instanceof MineCtx) {
            realMineCtx = (MineCtx) ctx;
        } else {
            // isHintMode=true (ctx 为 null) 或发生未知错误
            realMineCtx = null;
        }
        // --- (*** 修复结束 ***) ---


        /*
         * 第一阶段：初始化待处理方块列表 (std)
         */
        std.initialize(w * h);
        for (y = 0; y < h; y++) {
            for (x = 0; x < w; x++) {
                i = y * w + x;
                if (grid[i] != -2) { // -2 = 未知
                    std.add(i);
                }
            }
        }

        /*
         * 第二阶段：主推理循环
         */
        while (true) {
            boolean done_something = false; // 标记本轮循环是否取得了进展
            Set s;

            /*
             * 步骤2.1：处理待处理列表中的已知方块 (std)
             */
            while (std.head != -1) {
                // 1. 从 `std` 队列中取出一个方块
                i = std.head;
                if (SOLVER_DIAGNOSTICS) { System.out.printf("known square at %d,%d [%d]\n", i % w, i / w, grid[i]); }
                std.head = std.next[i];
                if (std.head == -1)
                    std.tail = -1; // 队列为空

                x = i % w;
                y = i / w;

                // 2. 如果是数字 (0-8)，则为其创建一个*新*的约束集
                if (grid[i] >= 0) {
                    int dx, dy, mines, bit, val;
                    if (SOLVER_DIAGNOSTICS) { System.out.println("creating set around this square"); }
                    mines = grid[i]; // 约束集的地雷数
                    bit = 1;
                    val = 0; // 约束集的掩码 (mask)

                    // C: 3x3 循环
                    for (dy = -1; dy <= +1; dy++) {
                        for (dx = -1; dx <= +1; dx++) {
                            // C: 检查边界
                            if (x + dx < 0 || x + dx >= w || y + dy < 0 || y + dy >= h) {
                                // 忽略
                            }
                            else {
                                if (SOLVER_DIAGNOSTICS) { System.out.printf("grid %d,%d = %d\n", x + dx, y + dy, grid[i + dy * w + dx]); }
                                if (grid[i + dy * w + dx] == -1) {
                                    mines--; // 如果邻居是旗帜，则地雷数 -1
                                }
                                else if (grid[i + dy * w + dx] == -2) {
                                    val |= bit; // 如果邻居是未知，则添加到掩码 (val)
                                }
                            }
                            bit <<= 1;
                        }
                    }

                    if (val != 0) {
                        ss.add(x - 1, y - 1, val, mines);
                    }
                }

                /*
                 * 步骤2.1.1：更新包含此方块的*现有*约束集
                 */
                {
                    if (SOLVER_DIAGNOSTICS) { System.out.printf("finding sets containing known square %d,%d\n", x, y); }
                    // 查找所有包含 (x,y) 的约束集
                    list = ss.overlap(x, y, 1);

                    for (Set s_j : list) {
                        int newmask, newmines;
                        s = s_j;

                        // 计算 (s - (x,y,1))，即从 s 中移除方块 (x,y)
                        newmask = setmunge(s.x, s.y, s.mask, x, y, 1, true);

                        // 更新地雷数：如果 (x,y) 被标记为雷，则 s 的地雷数 -1
                        newmines = s.mines - (grid[i] == -1 ? 1 : 0);

                        if (newmask != 0) {
                            // 如果 s 仍然包含其他未知方块，则添加这个缩小的集合
                            ss.add(s.x, s.y, newmask, newmines);
                        }
                        ss.remove(s); // 移除旧的 (较大的) 集合
                    }
                }
                done_something = true;
            } // 结束 while(std.head != -1) (处理已知方块)

            /*
             * 步骤2.2：处理约束集待办列表 (ss.todo)
             */
            s = ss.todo(); // C: s = ss_todo(ss)
            if (s != null) {
                // 我们有一个待处理的约束集 's'
                if (SOLVER_DIAGNOSTICS) { System.out.printf("set to do: %d,%d %03x %d\n", s.x, s.y, s.mask, s.mines); }

                // 1. "简单" 推理：
                if (s.mines == 0 || s.mines == bitcount16(s.mask)) {
                    if (SOLVER_DIAGNOSTICS) { System.out.println("easy"); }
                    // (*** 修复 ***) 使用 realMineCtx
                    known_squares(w, h, std, grid, open, realMineCtx,
                            s.x, s.y, s.mask, (s.mines != 0), isHintMode);
                    continue; // (我们取得了进展，返回主循环)
                }

                // 2. "重叠" 推理：
                list = ss.overlap(s.x, s.y, s.mask);

                for (Set s2 : list) {
                    int swing, s2wing;
                    int swc, s2wc;

                    swing = setmunge(s.x, s.y, s.mask, s2.x, s2.y, s2.mask, true);
                    s2wing = setmunge(s2.x, s2.y, s2.mask, s.x, s.y, s.mask, true);
                    swc = bitcount16(swing);
                    s2wc = bitcount16(s2wing);

                    // C: 翅膀推理 (Wing logic)
                    if (swc == s.mines - s2.mines || s2wc == s2.mines - s.mines) {
                        // (*** 修复 ***) 使用 realMineCtx
                        known_squares(w, h, std, grid, open, realMineCtx,
                                s.x, s.y, swing, (swc == s.mines - s2.mines), isHintMode);
                        // (*** 修复 ***) 使用 realMineCtx
                        known_squares(w, h, std, grid, open, realMineCtx,
                                s2.x, s2.y, s2wing, (s2wc == s2.mines - s.mines), isHintMode);
                        continue; // (取得了进展)
                    }

                    // C: 子集推理 (Subset logic)
                    if (swc == 0 && s2wc != 0) {
                        assert(s2.mines > s.mines);
                        ss.add(s2.x, s2.y, s2wing, s2.mines - s.mines);
                    }
                    else if (s2wc == 0 && swc != 0) {
                        assert(s.mines > s2.mines);
                        ss.add(s.x, s.y, swing, s.mines - s2.mines);
                    }
                }
                done_something = true;
            }
            else if (n >= 0) {
                /*
                 * 步骤2.3：全局推理
                 */
                int minesleft, squaresleft;
                int nsets, cursor;
                boolean[] setused = new boolean[10];

                squaresleft = 0; // 剩余未知方块
                minesleft = n;   // 剩余地雷
                for (i = 0; i < w * h; i++) {
                    if (grid[i] == -1) minesleft--;
                    else if (grid[i] == -2) squaresleft++;
                }

                if (SOLVER_DIAGNOSTICS) { System.out.printf("global deduction time: squaresleft=%d minesleft=%d\n", squaresleft, minesleft); }

                // 2.3.1 "简单" 全局推理
                if (squaresleft == 0) {
                    assert(minesleft == 0);
                    break; // 成功 (没有剩余方块)
                }
                if (minesleft == 0 || minesleft == squaresleft) {
                    for (i = 0; i < w * h; i++)
                        if (grid[i] == -2)
                            // (*** 修复 ***) 使用 realMineCtx
                            known_squares(w, h, std, grid, open, realMineCtx,
                                    i % w, i / w, 1, (minesleft != 0), isHintMode);
                    continue; // (取得了进展)
                }

                /*
                 * 步骤2.3.3：复杂全局推理 (C: 虚拟递归)
                 */
                nsets = ss.countSets();
                if (nsets <= setused.length) {
                    Set[] sets = new Set[setused.length];
                    for (i = 0; i < nsets; i++) {
                        sets[i] = ss.getSetByIndex(i);
                    }
                    cursor = 0;
                    while (true) {
                        if (cursor < nsets) {
                            boolean ok = true;
                            for (i = 0; i < cursor; i++)
                                if (setused[i] &&
                                        setmunge(sets[cursor].x, sets[cursor].y, sets[cursor].mask,
                                                sets[i].x, sets[i].y, sets[i].mask, false) != 0) {
                                    ok = false; break;
                                }
                            if (ok) {
                                minesleft -= sets[cursor].mines;
                                squaresleft -= bitcount16(sets[cursor].mask);
                            }
                            setused[cursor++] = ok;
                        }
                        else {
                            /* 到达末尾，检查 */
                            if (squaresleft > 0 &&
                                    (minesleft == 0 || minesleft == squaresleft)) {
                                for (i = 0; i < w * h; i++)
                                    if (grid[i] == -2) {
                                        boolean outside = true; y = i / w; x = i % w;
                                        for (j = 0; j < nsets; j++)
                                            if (setused[j] &&
                                                    setmunge(sets[j].x, sets[j].y, sets[j].mask,
                                                            x, y, 1, false) != 0) {
                                                outside = false; break;
                                            }
                                        if (outside) {
                                            // (*** 修复 ***) 使用 realMineCtx
                                            known_squares(w, h, std, grid, open, realMineCtx,
                                                    x, y, 1, (minesleft != 0), isHintMode);
                                        }
                                    }
                                done_something = true;
                                break; // 退出虚拟递归
                            }

                            /* 3. "回溯" (Backtrack) */
                            do { cursor--;
                            } while (cursor >= 0 && !setused[cursor]);
                            if (cursor >= 0) {
                                assert(setused[cursor]);
                                minesleft += sets[cursor].mines;
                                squaresleft += bitcount16(sets[cursor].mask);
                                setused[cursor++] = false;
                            } else {
                                break;
                            }
                        }
                    }
                }
            } // 结束 步骤2.3 (全局推理)

            /*
             * 步骤2.4：检查进展
             */
            if (done_something)
                continue; // 如果我们取得了任何进展 (std 或 ss.todo)，返回主循环顶部

            /*
             * 步骤2.5：推理停滞
             */
            if (SOLVER_DIAGNOSTICS) { System.out.printf("solver ran out of steam, ret=%d, grid:\n", nperturbs); }

            /*
             * 步骤2.6：调用扰动函数 (Perturb)
             *
             * (*** 逐步求解挂钩 ***)
             */
            if (perturb != null && !isHintMode) {
                Perturbations perts;
                nperturbs++;

                // --- (逐步求解挂钩) ---
                if (ctx instanceof StepSolveContext) {
                    StepSolveContext stepCtx = (StepSolveContext) ctx;

                    try {
                        // 1. (*** 关键修复 ***) 报告 "卡住" (这会设置 AtomicBoolean 并安全地更新 UI)
                        stepCtx.reportStuck(nperturbs);

                        // 2. (*** 关键修复 ***) 在报告 "卡住" 后，刷新一次网格
                        // (这确保了 UI 显示的是 *卡住时* 的状态，而不是卡住 *前* 的状态)
                        stepCtx.updateGridCallback.run(); // (这会调用 publish())

                        // 3. 等待 "Next" 按钮 (Semaphore.acquire())
                        stepCtx.waitSignal.acquire();

                        // 4. 恢复 (在 StepSolveContext 中安全地更新 UI)
                        stepCtx.reportSolving("Perturb #" + nperturbs); // "正在运行求解器..."

                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // --- 挂钩结束 ---

                if (ss.countSets() == 0) {
                    if (SOLVER_DIAGNOSTICS) System.out.println("perturbing on entire unknown set");
                    perts = perturb.perturb(ctx, grid, 0, 0, 0); // (传递完整的 ctx)
                } else {
                    s = ss.getSetByIndex(random_upto(rs, ss.countSets()));
                    if (SOLVER_DIAGNOSTICS) System.out.printf("perturbing on set %d,%d %03x\n", s.x, s.y, s.mask);
                    perts = perturb.perturb(ctx, grid, s.x, s.y, s.mask); // (传递完整的 ctx)
                }

                if (perts != null) {

                    // --- (新增) 动画挂钩 - Pause 2: 播放动画 ---
                    if (ctx instanceof StepSolveContext) {
                        StepSolveContext stepCtx = (StepSolveContext) ctx;

                        // (*** 修复 ***) 确保 realMineCtx 存在
                        if (realMineCtx == null) break;

                        try {
                            // 1. 将 'perts' 方案交给 UI 线程
                            stepCtx.pendingAnimation = perts;

                            // 2. (*** 关键修复 ***) 报告 "等待动画" (设置 AtomicBoolean=false)
                            stepCtx.reportWaitingForAnimation();

                            // 3. 告诉 UI 线程播放动画 (animationCallback 会调用 publish())
                            stepCtx.animationCallback.run();

                            // 4. 等待动画完成 (动画 Timer 会 release 信号)
                            stepCtx.waitSignal.acquire();

                            // 5. 清理
                            stepCtx.pendingAnimation = null;

                            // 6. (*** 关键 ***)
                            // 动画已播完，现在*手动*将更改应用到 *真实* grid
                            for (i = 0; i < perts.n; i++) {
                                int delta = perts.changes[i].delta;
                                x = perts.changes[i].x;
                                y = perts.changes[i].y;
                                assert ((delta < 0) == (realMineCtx.grid[y * w + x]));
                                realMineCtx.grid[y * w + x] = (delta > 0);
                                // (我们也必须更新求解器视图，否则它会出错)
                                updateGridAfterPerturbation(realMineCtx, grid, x, y, delta);
                            }

                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    // --- 动画挂钩 2 结束 ---

                    // (更新 `std` 列表以反映扰动)
                    for (i = 0; i < perts.n; i++) {
                        if (SOLVER_DIAGNOSTICS) {
                            System.out.printf("perturbation %s mine at %d,%d\n",
                                    perts.changes[i].delta > 0 ? "added" : "removed",
                                    perts.changes[i].x, perts.changes[i].y);
                        }
                        if (perts.changes[i].delta < 0 &&
                                grid[perts.changes[i].y * w + perts.changes[i].x] != -2) {
                            std.add(perts.changes[i].y * w + perts.changes[i].x);
                        }
                        list = ss.overlap(perts.changes[i].x, perts.changes[i].y, 1);
                        for (Set s_j : list) {
                            s_j.mines += perts.changes[i].delta;
                            ss.addTodo(s_j);
                        }
                    }
                    continue; // 返回主循环
                }
            }

            /*
             * 步骤2.7：彻底失败
             */
            break;
        } // 结束 while(true)

        /*
         * 第三阶段：最终结果检查
         */
        for (y = 0; y < h; y++)
            for (x = 0; x < w; x++)
                if (grid[y * w + x] == -2) {
                    nperturbs = -1; // 失败
                    break;
                }

        /*
         * 第四阶段：清理
         */
        {
            Set s_cleanup;
            while ((s_cleanup = ss.deleteSetAtPosition(0)) != null) {
                // Java GC handles s_cleanup
            }
            ss.freeTree();
            // Java GC handles ss, std
        }

        // C: return nperturbs;
        return nperturbs;
    }

    /**
     * (新增) 扫雷求解器 "提示" (Hint) 入口点
     * <p>
     * 这是一个 "非作弊" 模式，它不使用 `open`/`perturb`/`ctx` 回调。
     * 它只读取玩家视角的 `grid`，并尝试进行逻辑推导。
     */
    public static int minesolve_hint(int w, int h, int n, byte[] grid, Random rs)
    {
        // 调用内部实现，isHintMode = true
        // 并且 "open", "perturb", "ctx" 均为 null，
        return minesolve_internal(w, h, n, grid, null, null, null, rs, true);
    }

}

