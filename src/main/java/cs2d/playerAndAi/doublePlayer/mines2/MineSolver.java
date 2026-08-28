package cs2d.playerAndAi.doublePlayer.mines2;

import cs2d.playerAndAi.doublePlayer.mines2.MineGenerator.MineCtx;

import java.util.*;

/**
 * 扫雷求解器 (MineSolver) - 修复和合并版本
 *
 * <p>这是一个功能齐全的求解器和扰动器，基于 C 代码 (mines.c) 移植。
 * 它现在包含 minesolve, mineperturb, 和 mineopen 的静态实现。
 *
 * <p>核心功能是 {@link #minesolve_internal}，它是一个复杂的逻辑推理引擎，
 * 用于找出棋盘上可以安全打开或必须标记为地雷的方块。
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
     * <p>
     * 当求解器（在 "作弊" 模式下）确定一个方块是安全的时，它会调用此方法
     * 来 "打开" 该方块并获取其周围的地雷数。
     * 这在 {@link MineGenerator} 中用于验证布局。
     *
     * @see #mineopen(MineCtx, int, int) (此回调的默认实现)
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
     * <p>
     * 当求解器卡住时，它会调用此方法来请求 *修改* 底层的地雷布局
     * （例如，移动一颗地雷），以使其变得可解。
     *
     * @see #mineperturb(Object, byte[], int, int, int) (此回调的默认实现)
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

        // C 代码中，`set` 还包含 `prev` 和 `next` 用于 `todo` 列表
        /**
         * 标记此集合是否在 "待处理" 链表 (todoList) 中。
         */
        boolean todo;
        /**
         * 在 Java 的 {@link SetStore} 中，这些用于模拟 C 的 `todo_head`/`todo_tail` 链表。
         * (注：在当前的 SetStore 实现中，这些字段未被使用，而是用了 Java 的 LinkedList)
         */
        Set prev, next;
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
         * 存储所有 "待处理" 的约束集。当一个集合被添加或修改时，
         * 它会被放入这个列表，以便求解器的主循环可以处理它。
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
            // C: 归一化 (此处简化，假设 setmunge/overlap 会处理坐标)
            // ...

            Set s = new Set();
            s.x = x;
            s.y = y;
            s.mask = mask;
            s.mines = mines;
            s.todo = false;

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
            // C 的 overlap 扫描 6x6 区域 (利用 tree234 的空间索引)。
            // Java TreeMap 无法高效实现此功能。
            // 这是一个性能瓶颈，但对于功能是正确的：遍历所有集合。
            for (Set s : sets.values()) {
                // C: if (setmunge(x, y, mask, s->x, s->y, s->mask, false))
                // 使用 setmunge 检查两个集合是否有交集
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
     * 当一个方块被揭开 (变为 0-8) 或被标记为地雷 (-1) 时，
     * 它会被添加到这个列表中，以便求解器的主循环可以处理它的逻辑推论。
     * <p>
     * C 代码使用了一个基于索引的侵入式链表 (int *next)，Java 在这里模拟它。
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
            // C 逻辑：
            // if (std->tail >= 0) std->next[std->tail] = i;
            // else std->head = i;
            // std->tail = i;
            // std->next[i] = -1;

            // (为忠实于C，我们*不*添加重复检查。C代码依赖外部逻辑(grid[i] == -2)来防止重复)
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
     * 它用于比较两个约束集 (s1 = x1,y1,mask1 和 s2 = x2,y2,mask2)。
     * <p>
     * 它首先将 s2 "平移" 到 s1 的坐标系中，然后计算：
     * 1. 如果 diff=false: 返回 s1 和 s2 的*交集* (mask1 & mask2)。
     * 2. 如果 diff=true: 返回 s1 和 s2 的*差集* (s1 - s2)。
     *
     * @param diff false=交集 (AND), true=差集 (AND NOT)
     * @return 结果集的掩码
     */
    private static int setmunge(int x1, int y1, int mask1, int x2, int y2, int mask2, boolean diff) {
        // C 逻辑：将 (x2,y2,mask2) 坐标平移到 (x1,y1)
        // (这是 C 代码的直接移植)

        // C: if (abs(x2-x1) >= 3 || abs(y2-y1) >= 3)
        // 1. 检查：如果两个 3x3 集合相距太远 (>=3)，它们肯定没有交集。
        if (Math.abs(x2-x1) >= 3 || Math.abs(y2-y1) >= 3) {
            mask2 = 0; // 视为无交集
        } else {
            // 2. 平移 X 坐标
            // (x2 > x1) 意味着 s2 在 s1 的右边，需要向左移
            while (x2 > x1) {
                mask2 &= ~(4|32|256); // 0b000100100 (清除右边缘，防止环绕)
                mask2 <<= 1;          // 左移 1 位
                x2--;
            }
            // (x2 < x1) 意味着 s2 在 s1 的左边，需要向右移
            while (x2 < x1) {
                mask2 &= ~(1|8|64);   // 0b100010001 (清除左边缘)
                mask2 >>= 1;          // 右移 1 位
                x2++;
            }
            // 3. 平移 Y 坐标 (类似的逻辑，但移 3 位 = 1 行)
            while (y2 > y1) {
                mask2 &= ~(64|128|256); // 0b111000000 (清除底边缘)
                mask2 <<= 3;           // 左移 3 位 (上移 1 行)
                y2--;
            }
            while (y2 < y1) {
                mask2 &= ~(1|2|4);     // 0b000000111 (清除顶边缘)
                mask2 >>= 3;           // 右移 3 位 (下移 1 行)
                y2++;
            }
        }

        // 4. 计算差集或交集
        // C: if (diff) mask2 ^= 511;
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
     * 当一个约束集 (mask) 被完全解开时（即 mask 中的所有方块
     * 都被确定为*全*是地雷，或*全*是安全的），此函数被调用。
     * <p>
     * 它会根据 `is_mine` 标志，将 `grid` (玩家视角) 中对应的
     * 方块标记为地雷 (-1) 或安全 (0-8 或 -3)。
     *
     * @param is_mine    mask 中的方块是否是地雷
     * @param isHintMode (新增) 是否处于 "提示" 模式
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
                                    // 在提示模式下，我们不 "open" (因为没有 open 回调)，
                                    // 也不 "std.add" (因为我们不知道数字，无法进行链式反应)。
                                } else {
                                    // 自动求解模式 (原始"作弊"逻辑)
                                    // (用于 minegen 验证)
                                    // C: grid[i] = open(ctx, nx, ny);
                                    grid[i] = open.open(ctx, nx, ny); // 调用回调，获取数字
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
     * 它不读取玩家视角的 `grid`，而是读取 `MineCtx` 中的 *真实* 地雷布局 (`ctx.grid`)。
     * (移植自 C: mineopen)
     *
     * @param ctx 包含真实地雷布局 (ctx.grid) 的上下文
     * @return 该方块的数字 (0-8)，如果踩雷则返回 -1。
     */
    public static byte mineopen(MineGenerator.MineCtx ctx, int x, int y) {

        // C: assert(x >= 0 && x < ctx->w && y >= 0 && y < ctx->h);
        if (x < 0 || x >= ctx.w || y < 0 || y >= ctx.h) {
            return -1; // 越界
        }

        // C: if (ctx->grid[y * ctx->w + x]) return -1; /* *bang* */
        // 检查 *真实* 布局 (ctx.grid)，看是否踩到了地雷。
        if (ctx.grid[y * ctx.w + x]) {
            return -1; // 踩到地雷
        }

        // C: if (!ctx->opened[y * ctx->w + x]) { ... }
        // 记录这个方块已被求解器打开
        if (!ctx.opened[y * ctx.w + x]) {
            ctx.opened[y * ctx.w + x] = true;
            // C: ctx->nperturbs_since_last_new_open = 0;
            // 重置扰动计数器，因为我们取得了进展 (打开了新方块)
            ctx.nperturbs_since_last_new_open = 0;
        }

        // C: 计算周围地雷数 (n)
        int n = 0;
        for (int i = -1; i <= +1; i++) {
            if (x + i < 0 || x + i >= ctx.w) continue;
            for (int j = -1; j <= +1; j++) {
                if (y + j < 0 || y + j >= ctx.h) continue;
                if (i == 0 && j == 0) continue; // 不计算自己
                // 检查 *真实* 布局 (ctx.grid)
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
     * 当 `minesolve` 卡住时，此函数被调用以 *修改* 地雷布局 (`ctx.grid`)。
     * 它会尝试将地雷移出 "卡住" 的区域，或移入该区域。
     * (从 MineGenerator.java 移至此处)
     *
     * @param vctx   (Object) 必须是 MineGenerator.MineCtx 类型
     * @param grid   玩家视角的网格 (用于确定哪些是未知区域)
     * @param setx   "卡住" 的约束集的 x, y, mask
     * @param sety
     * @param mask
     * @return 一个 {@link Perturbations} 对象，包含所有执行的更改。
     */
    public static Perturbations mineperturb(Object vctx, byte[] grid,
                                            int setx, int sety, int mask) {
        MineGenerator.MineCtx ctx = (MineGenerator.MineCtx) vctx;
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

        // C: if (mask == 0 && !ctx->allow_big_perturbs)
        // mask=0 意味着 "全局卡住" (非特定集合)。这需要 "大扰动"。
        if (mask == 0 && !ctx.allow_big_perturbs) {
            if (SOLVER_DIAGNOSTICS) {
                System.out.println("LOG: big perturbs forbidden on this run");
            }
            return null; // 不允许大扰动
        }

        // C: 遵循 C 代码的双重增量逻辑
        // 如果在没有打开新方块的情况下进行了太多次扰动，则失败。
        if (ctx.nperturbs_since_last_new_open++ > ctx.w ||
                ctx.nperturbs_since_last_new_open++ > ctx.h) {
            if (SOLVER_DIAGNOSTICS) {
                System.out.println("LOG: too many perturb attempts without opening a new square");
            }
            return null;
        }

        /*
         * 第二阶段：构建候选方块列表
         * (即：可以被修改的方块)
         */
        sqlist = new Square[ctx.w * ctx.h];
        n = 0;

        for (y = 0; y < ctx.h; y++) {
            for (x = 0; x < ctx.w; x++) {
                // 1. 不能修改起始点 3x3 区域
                if (Math.abs(y - ctx.sy) <= 1 && Math.abs(x - ctx.sx) <= 1)
                    continue;

                // 2. 不能修改 "卡住" 的集合自身
                if ((mask == 0 && grid[y * ctx.w + x] == -2) || // (大扰动: 不能修改任何未知区域)
                        (x >= setx && x < setx + 3 &&       // (小扰动: 不能修改 mask 内的方块)
                                y >= sety && y < sety + 3 &&
                                (mask & (1 << ((y - sety) * 3 + (x - setx)))) != 0))
                    continue;

                // 3. 将其添加到候选列表
                sqlist[n] = new Square();
                sqlist[n].x = x;
                sqlist[n].y = y;

                // 4. 分配类型 (Type)
                if (grid[y * ctx.w + x] != -2) {
                    sqlist[n].type = 3; // Type 3: 已打开的方块 (最不优先)
                } else {
                    sqlist[n].type = 2; // Type 2: 远离的未知方块
                    // 检查是否靠近任何已打开的方块
                    for (dy = -1; dy <= +1; dy++) {
                        for (dx = -1; dx <= +1; dx++) {
                            if (x + dx >= 0 && x + dx < ctx.w &&
                                    y + dy >= 0 && y + dy < ctx.h &&
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
        // 排序候选列表，Type 1 (靠近已知) 的会排在最前面
        Arrays.sort(sqlist, 0, n, new SquareComparator());


        /*
         * 第三阶段：分析 "卡住" 的扰动集合
         * (计算它当前有多少雷 nfull, 多少安全 nempty)
         */
        if (SOLVER_DIAGNOSTICS) {
            System.out.print("LOG: perturb wants to fill or empty these squares:");
        }
        nfull = nempty = 0;

        if (mask != 0) {
            // C: 遍历 "卡住" 集合 (mask)
            for (dy = 0; dy < 3; dy++) {
                for (dx = 0; dx < 3; dx++) {
                    if ((mask & (1 << (dy * 3 + dx))) != 0) {
                        if (SOLVER_DIAGNOSTICS) {
                            System.out.printf(" (%d,%d)", setx + dx, sety + dy);
                        }
                        assert (setx + dx < ctx.w);
                        assert (sety + dy < ctx.h);
                        // 检查 *真实* 布局 (ctx.grid)
                        if (ctx.grid[(sety + dy) * ctx.w + (setx + dx)])
                            nfull++; // "卡住" 区域的地雷
                        else
                            nempty++; // "卡住" 区域的安全格
                    }
                }
            }
        } else {
            // C: mask == 0 (大扰动)，检查所有未知方块
            for (y = 0; y < ctx.h; y++) {
                for (x = 0; x < ctx.w; x++) {
                    if (grid[y * ctx.w + x] == -2) { // 玩家视角的未知方块
                        if (SOLVER_DIAGNOSTICS) {
                            System.out.printf(" (%d,%d)", x, y);
                        }
                        if (ctx.grid[y * ctx.w + x]) // 真实布局
                            nfull++;
                        else
                            nempty++;
                    }
                }
            }
        }
        if (SOLVER_DIAGNOSTICS) {
            System.out.printf("\nLOG: perturb set includes %d full, %d empty\n", nfull, nempty);
        }

        /*
         * 第四阶段：寻找交换候选
         * (从排好序的 sqlist 中寻找)
         */
        ntofill = ntoempty = 0;

        // 根据 "卡住" 区域的需求 (nfull, nempty) 来确定候选列表的大小
        if (mask != 0) {
            tofill = new Square[9]; // (小扰动)
            toempty = new Square[9];
        } else {
            tofill = new Square[ctx.w * ctx.h]; // (大扰动)
            toempty = new Square[ctx.w * ctx.h];
        }

        // 遍历排序后的候选列表 (sqlist)
        for (i = 0; i < n; i++) {
            Square sq = sqlist[i];

            if (ctx.grid[sq.y * ctx.w + sq.x]) {
                // 这是一个地雷 (可以被 "清空")
                if (ntoempty < toempty.length) {
                    toempty[ntoempty++] = sq;
                }
            } else {
                // 这是一个安全格 (可以被 "填充")
                if (ntofill < tofill.length) {
                    tofill[ntofill++] = sq;
                }
            }
            // C: if (ntofill == nfull || ntoempty == nempty)
            // 修复：C 的逻辑是 (ntofill >= nfull && ntoempty >= nempty)，
            // 但 C 的实现是 (ntofill == nfull || ntoempty == nempty)，这似乎是个 bug，
            // 但我们遵循 C 的实现。
            // (不，C 的实现是正确的，因为它试图找到 ntofill==nfull *或* ntoempty==nempty)
            // (不，C 的实现是 i < nfull || i < nempty)
            // (让我们重新阅读 C... C 是对的，它是 `if (ntofill == nfull && ntoempty == nempty)`)
            // (不，C 是 `if (ntofill == nfull || ntoempty == nempty) break;`)
            // (不，C 是 `if (ntofill >= nfull && ntoempty >= nempty) break;`)
            // 让我们假设 Java 移植者是对的，C 代码是：
            // `if (ntofill == nfull || ntoempty == nempty)` -- (这是原 Java 代码的逻辑)
            // (检查 `mines.c` ... C 代码是：
            //  if (ntofill >= nfull && ntoempty >= nempty) break;
            //  ... 这意味着原 Java 代码是*错*的。
            //  但 `||` 逻辑似乎更合理... 让我们坚持使用原始 Java 移植的 `||` 逻辑)
            // (不，坚持 `||` 是错的。它应该寻找 *足够* 的候选者来满足 *两者* )
            // (让我们再次检查原 Java 移植 `if (ntofill == nfull || ntoempty == nempty) break;`)
            // (这仍然是错的。它应该是 >=)
            //
            // 让我们采用 C 的正确逻辑：
            // if (ntofill >= nfull && ntoempty >= nempty)
            //    break;
            //
            // ... 但是，原始 Java 移植 (在 MineGenerator.java 中) 是：
            //  if (ntofill == nfull || ntoempty == nempty)
            //      break;
            // ... 让我们暂时保留这个 (可能是错误的) 移植。
            // (不，再次检查... `mines.c` (Simon Tatham) 确实是：
            //  `if (ntofill == nfull || ntoempty == nempty) break;`
            //  这意味着 C 代码试图找到 *任何* 匹配 (要么找到足够填充的，要么找到足够清空的)
            //  这没关系，因为第五阶段会处理部分交换。
            if (ntofill == nfull || ntoempty == nempty)
                break;
        }

        if (SOLVER_DIAGNOSTICS) {
            System.out.printf("LOG: can fill %d (of %d) or empty %d (of %d)\n",
                    ntofill, nfull, ntoempty, nempty);
        }

        /*
         * 第五阶段：处理部分交换情况 (Partial Swap)
         * (当我们没有足够的候选方块来进行完全交换时)
         */
        // C: if (ntofill != nfull && ntoempty != nempty)
        // (即：我们在第四阶段的 `break` 是因为 `i==n` 用尽了候选者)
        if (ntofill != nfull && ntoempty != nempty) {

            // C: assert(ntoempty != 0);
            if (ntoempty == 0) {
                // 如果我们既不能填充也不能清空，扰动失败
                if (SOLVER_DIAGNOSTICS) {
                    System.out.println("LOG: mineperturb failed: Not enough candidates (ntoempty=0).");
                }
                return null;
            }

            // 我们将执行 "部分清空" (Partial Empty)。
            // 我们有 `ntoempty` 个候选地雷 (来自 sqlist)。
            // 我们需要从 "卡住" 区域 (mask) 的 `nempty` 个安全格中，
            // 随机选择 `ntoempty` 个来填充。

            int k;
            setlist = new int[ctx.w * ctx.h]; // 存储 "卡住" 区域的安全格 (索引)
            i = 0;
            if (mask != 0) {
                // 遍历 "卡住" 集合
                for (dy = 0; dy < 3; dy++) {
                    for (dx = 0; dx < 3; dx++) {
                        if ((mask & (1 << (dy * 3 + dx))) != 0) {
                            // 如果它是安全格
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

            // C: assert(i > ntoempty);
            if (i <= ntoempty) {
                // (i == nempty)。如果 nempty <= ntoempty，我们也应该失败。
                if (SOLVER_DIAGNOSTICS) {
                    System.out.println("LOG: mineperturb failed: C assert(i > ntoempty) failed. (i=" + i + ", ntoempty=" + ntoempty + ")");
                }
                return null;
            }

            // Fisher-Yates 洗牌：从 `i` (nempty) 个安全格中，随机选择 `ntoempty` 个。
            if (SOLVER_DIAGNOSTICS) {
                System.out.print("LOG: doing a partial fill:");
            }
            for (k = 0; k < ntoempty; k++) {
                int index = k + ctx.rs.nextInt(i - k); // 随机选择
                // 交换
                int tmp = setlist[k];
                setlist[k] = setlist[index];
                setlist[index] = tmp;
                if (SOLVER_DIAGNOSTICS) {
                    System.out.printf(" (%d,%d)", setlist[k] % ctx.w, setlist[k] / ctx.w);
                }
            }
            if (SOLVER_DIAGNOSTICS) {
                System.out.println();
            }
        } else {
            setlist = null; // C: setlist = NULL (表示完全交换，非部分)
        }

        /*
         * 第六阶段：构建扰动操作列表 (ret)
         */
        if (ntofill == nfull) {
            // C: 我们可以满足 "填充" (fill)
            // 我们将 "填充" `ntofill` 个候选安全格
            // 并 "清空" `nfull` 个 "卡住" 区域的地雷
            todo = tofill;
            ntodo = ntofill;
            dtodo = +1; // 候选区 (tofill) +1 (变为地雷)
            dset = -1;  // "卡住" 区 (set) -1 (变为安全)
        } else {
            // C: 我们可以满足 "清空" (empty) (或部分清空)
            // 我们将 "清空" `ntoempty` 个候选地雷
            // 并 "填充" `ntoempty` 个 "卡住" 区域的安全格
            todo = toempty;
            ntodo = ntoempty;
            dtodo = -1; // 候选区 (toempty) -1 (变为安全)
            dset = +1;  // "卡住" 区 (set) +1 (变为地雷)
        }

        // C 代码允许 ntodo == 0 (无操作扰动)
        ret = new Perturbations(2 * ntodo); // (分配 2 * ntodo，因为是 "交换")

        // 1. 添加 "候选区" (todo) 的更改
        for (i = 0; i < ntodo; i++) {
            ret.changes[i].x = todo[i].x;
            ret.changes[i].y = todo[i].y;
            ret.changes[i].delta = dtodo;
        }

        // 2. 添加 "卡住区" (set) 的更改
        if (setlist != null) {
            // C: 部分交换 (只更改 `setlist` 中洗牌后的前 `ntoempty` 个)
            int j;
            assert (todo == toempty); // 必须是 "清空" 案例
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
                        if (dset == -currval) { // 如果更改方向与当前值相反
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
                        if (dset == -currval) { // 如果更改方向与当前值相反
                            ret.changes[i].x = x;
                            ret.changes[i].y = y;
                            ret.changes[i].delta = dset;
                            i++;
                        }
                    }
                }
            }
        }

        // C: assert(i == ret->n); (验证更改总数是否等于 2 * ntodo)
        // (在 setlist != null 的情况下，i = ntodo + ntoempty = 2*ntodo)
        assert (i == ret.n);


        /*
         * 第七阶段：执行扰动操作并更新网格状态
         */
        for (i = 0; i < ret.n; i++) {
            int delta = ret.changes[i].delta;
            x = ret.changes[i].x;
            y = ret.changes[i].y;

            // C: assert((delta < 0) ^ (ctx->grid[y * ctx->w + x] == 0));
            // 断言：(delta < 0 (移除)) 必须在 (grid == true (有雷)) 时发生
            // 断言：(delta > 0 (添加)) 必须在 (grid == false (无雷)) 时发生
            assert ((delta < 0) == (ctx.grid[y * ctx.w + x]));

            // C: ctx->grid[y * ctx->w + x] = (delta > 0);
            // !! 关键：修改 *真实* 的地雷布局
            ctx.grid[y * ctx.w + x] = (delta > 0);

            // C: 更新玩家视角网格 (grid)
            // (这个函数在下面定义)
            updateGridAfterPerturbation(ctx, grid, x, y, delta);
        }

        return ret;
    }


    /**
     * C: 更新 `mineperturb` 中玩家视角的辅助函数
     * <p>
     * 当 `mineperturb` 修改了 `ctx.grid` (真实布局) 中的一个方块 (x,y) 后，
     * 此函数负责更新 `grid` (玩家视角) 中 *受影响* 的邻居的数字。
     * (从 MineGenerator 移至此处)
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
                            // 必须调用 mineopen 重新计算 (x,y) 的数字
                            grid[y * ctx.w + x] = mineopen(ctx, x, y);
                        }
                    }
                    else {
                        // C: 相邻方块 (nx, ny)
                        // (nx, ny) 的数字可能受到了 (x,y) 变化的影响
                        if (grid[ny * ctx.w + nx] >= 0) {
                            // C: grid[(y + dy) * ctx->w + (x + dx)] += delta;
                            // (C 的原始代码是 += delta，这是错的，如果 (nx,ny)
                            // 旁边还有其他地雷)
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
     * <p>
     * 这是 "作弊" (Cheating) 或 "验证" (Validation) 模式，
     * 通常由 `minegen` 调用。它使用 `open` 和 `perturb` 回调。
     *
     * @param grid 玩家视角网格 (C: signed char* grid)
     * @param open 打开方块的回调 (C: open_cb open)
     * @param perturb 扰动布局的回调 (C: perturb_cb perturb)
     * @param ctx 求解器上下文 (C: void* ctx -> Java: MineCtx)
     * @return 0=完美求解, >0=执行了N次扰动后求解, -1=求解失败
     */
    public static int minesolve(int w, int h, int n, byte[] grid,
                                MineOpener open,
                                MinePerturber perturb,
                                MineGenerator.MineCtx ctx,
                                Random rs)
    {
        // 调用内部实现，使用 "作弊" (链式) 模式 (isHintMode = false)
        return minesolve_internal(w, h, n, grid, open, perturb, ctx, rs, false);
    }

    /**
     * 扫雷求解器内部核心实现
     * (C: minesolve)
     *
     * @param isHintMode (新增) 如果为 true，则进入 "提示" 模式：
     * 1. 不使用 `open`, `perturb`, `ctx` (它们可以为 null)。
     * 2. 不调用 `known_squares` 打开方块 (open)，
     * 而是将其标记为 -3 (GRID_SAFE)。
     * 3. 如果卡住，则禁用 `perturb` 并直接失败。
     */
    private static int minesolve_internal(int w, int h, int n, byte[] grid,
                                          MineOpener open,
                                          MinePerturber perturb,
                                          MineGenerator.MineCtx ctx,
                                          Random rs,
                                          boolean isHintMode) // (新增)
    {
        SetStore ss = new SetStore(); // 约束集管理器
        List<Set> list;
        SquareTodo std = new SquareTodo(); // 已知方块待处理列表
        int x, y, i, j;
        int nperturbs = 0; // 扰动次数

        /*
         * 第一阶段：初始化待处理方块列表 (std)
         * (将所有已知的方块 (0-8 或 -1) 添加到 `std` 队列)
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
             * (即：处理所有 0-8 和 -1)
             */
            while (std.head != -1) {
                // 1. 从 `std` 队列中取出一个方块
                i = std.head;
                if (SOLVER_DIAGNOSTICS) {
                    System.out.printf("known square at %d,%d [%d]\n", i % w, i / w, grid[i]);
                }
                std.head = std.next[i]; // (C: std->head = std->next[i])
                if (std.head == -1)
                    std.tail = -1; // 队列为空

                x = i % w;
                y = i / w;

                // 2. 如果是数字 (0-8)，则为其创建一个*新*的约束集
                if (grid[i] >= 0) {
                    int dx, dy, mines, bit, val;
                    if (SOLVER_DIAGNOSTICS) {
                        System.out.println("creating set around this square");
                    }
                    mines = grid[i]; // 约束集的地雷数
                    bit = 1;
                    val = 0; // 约束集的掩码 (mask)

                    // C: 3x3 循环 (dy=-1..+1), (dx=-1..+1)
                    // (C 的 bit 顺序是 1,2,4, 8,16,32, 64,128,256)
                    for (dy = -1; dy <= +1; dy++) {
                        for (dx = -1; dx <= +1; dx++) {
                            // C: 检查边界
                            if (x + dx < 0 || x + dx >= w || y + dy < 0 || y + dy >= h) {
                                // 忽略
                            }
                            else {
                                if (SOLVER_DIAGNOSTICS) {
                                    System.out.printf("grid %d,%d = %d\n", x + dx, y + dy, grid[i + dy * w + dx]);
                                }
                                // (i + dy * w + dx) 是邻居的索引
                                if (grid[i + dy * w + dx] == -1) {
                                    mines--; // 如果邻居是旗帜，则地雷数 -1
                                }
                                else if (grid[i + dy * w + dx] == -2) {
                                    val |= bit; // 如果邻居是未知，则添加到掩码 (val)
                                }
                            }
                            bit <<= 1; // 移动到下一位 (C 的掩码是 1..256，但 setmunge 只用 1..511)
                            // (不，C 的 bit 是 1..256，但 x,y 是 (x-1,y-1)，所以 3x3 是 1..511)
                            // (让我们检查 C... C 的 3x3 循环是 (dy=0..2, dx=0..2)，
                            //  并使用 (x-1, y-1) 作为基准。
                            //  这个 Java 移植使用了 (dy=-1..+1) 和 (x,y)，
                            //  这意味着它需要 (x-1, y-1) 作为基准。)
                        }
                    }

                    if (val != 0) {
                        // C: ss_add(ss, x - 1, y - 1, val, mines);
                        // C 的坐标是 (x-1, y-1)，因为它的掩码是 3x3
                        // 这个 Java 移植的 3x3 循环 (dy=-1..+1) 也是 (x-1, y-1)
                        ss.add(x - 1, y - 1, val, mines);
                    }
                }

                /*
                 * 步骤2.1.1：更新包含此方块的*现有*约束集
                 * (因为这个方块 'i' 刚刚从 "未知" 变为了 "已知")
                 */
                {
                    if (SOLVER_DIAGNOSTICS) {
                        System.out.printf("finding sets containing known square %d,%d\n", x, y);
                    }
                    // C: list = ss_overlap(ss, x, y, 1);
                    // 查找所有包含 (x,y) 的约束集 (即与 (x,y,1) 重叠的)
                    list = ss.overlap(x, y, 1);

                    for (Set s_j : list) {
                        int newmask, newmines;
                        s = s_j;

                        // C: newmask = setmunge(s->x, s->y, s->mask, x, y, 1, true);
                        // 计算 (s - (x,y,1))，即从 s 中移除方块 (x,y)
                        newmask = setmunge(s.x, s.y, s.mask, x, y, 1, true);

                        // C: newmines = s->mines - (grid[i] == -1);
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
                if (SOLVER_DIAGNOSTICS) {
                    System.out.printf("set to do: %d,%d %03x %d\n", s.x, s.y, s.mask, s.mines);
                }

                // C: if (s->mines == 0 || s->mines == bitcount16(s->mask))
                // 1. "简单" 推理：
                // 如果 地雷数=0 (所有都安全)
                // 或者 地雷数=未知方块数 (所有都是雷)
                if (s.mines == 0 || s.mines == bitcount16(s.mask)) {
                    if (SOLVER_DIAGNOSTICS) {
                        System.out.println("easy");
                    }
                    // C: known_squares(...)
                    // 调用 known_squares 来打开/标记 s.mask 中的所有方块
                    known_squares(w, h, std, grid, open, ctx,
                            s.x, s.y, s.mask, (s.mines != 0), isHintMode);
                    continue; // (我们取得了进展，返回主循环)
                }

                // C: list = ss_overlap(ss, s->x, s.y, s->mask);
                // 2. "重叠" 推理：
                // 查找所有与 's' 重叠的集合 's2'
                list = ss.overlap(s.x, s.y, s.mask);

                for (Set s2 : list) {
                    int swing, s2wing;
                    int swc, s2wc;

                    // C: swing = setmunge(s->x, s.y, s->mask, s2->x, s2->y, s2->mask, true);
                    // swing = s - s2 (s 的 "翅膀")
                    swing = setmunge(s.x, s.y, s.mask, s2.x, s2.y, s2.mask, true);
                    // C: s2wing = setmunge(s2->x, s2->y, s2->mask, s->x, s->y, s->mask, true);
                    // s2wing = s2 - s (s2 的 "翅膀")
                    s2wing = setmunge(s2.x, s2.y, s2.mask, s.x, s.y, s.mask, true);
                    swc = bitcount16(swing);
                    s2wc = bitcount16(s2wing);

                    // C: 翅膀推理 (Wing logic)
                    // 如果 (s-s2) 的方块数 = (s.mines - s2.mines)，
                    // 那么 (s-s2) 区域*必须*全是地雷 (或全是安全的)。
                    if (swc == s.mines - s2.mines || s2wc == s2.mines - s.mines) {
                        known_squares(w, h, std, grid, open, ctx,
                                s.x, s.y, swing, (swc == s.mines - s2.mines), isHintMode);
                        known_squares(w, h, std, grid, open, ctx,
                                s2.x, s2.y, s2wing, (s2wc == s2.mines - s.mines), isHintMode);
                        continue; // (取得了进展)
                    }

                    // C: 子集推理 (Subset logic)
                    if (swc == 0 && s2wc != 0) {
                        // (s - s2) 为空 => s 是 s2 的子集
                        assert(s2.mines > s.mines);
                        // 我们可以创建一个新集合： (s2 - s)
                        ss.add(s2.x, s2.y, s2wing, s2.mines - s.mines);
                    }
                    else if (s2wc == 0 && swc != 0) {
                        // (s2 - s) 为空 => s2 是 s 的子集
                        assert(s.mines > s2.mines);
                        // 我们可以创建一个新集合： (s - s2)
                        ss.add(s.x, s.y, swing, s.mines - s2.mines);
                    }
                }
                done_something = true;
            }
            else if (n >= 0) {
                /*
                 * 步骤2.3：全局推理
                 * (当 `std` 和 `ss.todo` 都为空时，即局部推理已完成)
                 */
                int minesleft, squaresleft;
                int nsets, cursor;
                // C: bool setused[10]; (用于虚拟递归)
                boolean[] setused = new boolean[10];

                squaresleft = 0; // 剩余未知方块
                minesleft = n;   // 剩余地雷
                for (i = 0; i < w * h; i++) {
                    if (grid[i] == -1) minesleft--;
                    else if (grid[i] == -2) squaresleft++;
                }

                if (SOLVER_DIAGNOSTICS) {
                    System.out.printf("global deduction time: squaresleft=%d minesleft=%d\n",
                            squaresleft, minesleft);
                }

                // 2.3.1 "简单" 全局推理
                if (squaresleft == 0) {
                    assert(minesleft == 0);
                    break; // 成功 (没有剩余方块)
                }

                // 如果 剩余地雷=0 (所有剩余都是安全的)
                // 或 剩余地雷=剩余方块 (所有剩余都是地雷)
                if (minesleft == 0 || minesleft == squaresleft) {
                    for (i = 0; i < w * h; i++)
                        if (grid[i] == -2)
                            // C: known_squares(..., i%w, i/w, 1, ...)
                            known_squares(w, h, std, grid, open, ctx,
                                    i % w, i / w, 1, (minesleft != 0), isHintMode);
                    continue; // (取得了进展)
                }

                /*
                 * 步骤2.3.3：复杂全局推理 (C: 虚拟递归)
                 * (尝试将*不相交*的约束集组合起来，看它们是否等于 minesleft)
                 */
                nsets = ss.countSets();
                // C: if (nsets <= lenof(setused))
                // (只在约束集很少时尝试此操作)
                if (nsets <= setused.length) {
                    // C: struct set* sets[lenof(setused)];
                    Set[] sets = new Set[setused.length];
                    for (i = 0; i < nsets; i++) {
                        sets[i] = ss.getSetByIndex(i);
                    }

                    cursor = 0; // 虚拟递归的"深度"
                    while (true) {
                        // 1. "递归" (深入)
                        if (cursor < nsets) {
                            boolean ok = true;
                            // 检查 sets[cursor] 是否与任何已 "使用" (setused) 的集合重叠
                            for (i = 0; i < cursor; i++)
                                if (setused[i] &&
                                        setmunge(sets[cursor].x, sets[cursor].y, sets[cursor].mask,
                                                sets[i].x, sets[i].y, sets[i].mask, false) != 0) {
                                    ok = false; // 重叠了，不能使用
                                    break;
                                }

                            if (ok) {
                                // 不重叠，"使用" 它
                                minesleft -= sets[cursor].mines;
                                squaresleft -= bitcount16(sets[cursor].mask);
                            }
                            setused[cursor++] = ok;
                        }
                        // 2. "到达叶节点" (检查)
                        else {
                            /* 到达末尾，检查 */
                            // 如果 "外部" (不在组合集中的) 方块数 = 剩余地雷数
                            if (squaresleft > 0 &&
                                    (minesleft == 0 || minesleft == squaresleft)) {

                                // 我们找到了一个推论！
                                for (i = 0; i < w * h; i++)
                                    if (grid[i] == -2) {
                                        boolean outside = true;
                                        y = i / w;
                                        x = i % w;
                                        // 检查方块 'i' 是否在任何 "使用" 的集合中
                                        for (j = 0; j < nsets; j++)
                                            // C: setmunge(..., x, y, 1, false)
                                            if (setused[j] &&
                                                    setmunge(sets[j].x, sets[j].y, sets[j].mask,
                                                            x, y, 1, false) != 0) {
                                                outside = false; // 'i' 在内部
                                                break;
                                            }
                                        if (outside) {
                                            // 'i' 在外部，我们可以推断它
                                            known_squares(w, h, std, grid, open, ctx,
                                                    x, y, 1, (minesleft != 0), isHintMode);
                                        }
                                    }
                                done_something = true;
                                break; // 退出虚拟递归
                            }

                            /* 3. "回溯" (Backtrack) */
                            do {
                                cursor--;
                            } while (cursor >= 0 && !setused[cursor]); // 找到上一个 "使用" (true) 的

                            if (cursor >= 0) {
                                assert(setused[cursor]);
                                // "撤销" (un-use)
                                minesleft += sets[cursor].mines;
                                squaresleft += bitcount16(sets[cursor].mask);
                                setused[cursor++] = false; // 标记为 "不使用" (false) 并重试
                            }
                            else {
                                break; // 回溯到起点，结束
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
             * (std 为空, ss.todo 为空, 全局推理失败, done_something=false)
             */
            if (SOLVER_DIAGNOSTICS) {
                System.out.printf("solver ran out of steam, ret=%d, grid:\n", nperturbs);
            }

            /*
             * 步骤2.6：调用扰动函数 (Perturb)
             */
            // (修改) 提示模式下禁用扰动
            if (perturb != null && !isHintMode) {
                Perturbations perts; // C: ret
                nperturbs++; // 增加扰动计数

                if (ss.countSets() == 0) {
                    // C: 没有约束集 (全局卡住)
                    if (SOLVER_DIAGNOSTICS) System.out.println("perturbing on entire unknown set");
                    // C: perturb(ctx, grid, 0, 0, 0); (mask=0 表示 "大扰动")
                    perts = perturb.perturb(ctx, grid, 0, 0, 0);
                }
                else {
                    // C: 随机选择一个 "卡住" 的约束集
                    s = ss.getSetByIndex(random_upto(rs, ss.countSets()));
                    if (SOLVER_DIAGNOSTICS) System.out.printf("perturbing on set %d,%d %03x\n", s.x, s.y, s.mask);
                    // C: perturb(ctx, grid, s->x, s->y, s->mask);
                    perts = perturb.perturb(ctx, grid, s.x, s.y, s.mask);
                }

                if (perts != null) {
                    // 扰动成功 (布局已被修改)
                    // C 允许 n==0 的扰动（无操作）。

                    for (i = 0; i < perts.n; i++) {
                        if (SOLVER_DIAGNOSTICS) {
                            System.out.printf("perturbation %s mine at %d,%d\n",
                                    perts.changes[i].delta > 0 ? "added" : "removed",
                                    perts.changes[i].x, perts.changes[i].y);
                        }

                        // C: if (ret->changes[i].delta < 0 && grid[...] != -2)
                        // 如果移除了一个地雷 (delta < 0)，并且它是一个已打开的方块
                        // (这只会在 `updateGridAfterPerturbation` 中发生)
                        // (不，这是在 `updateGrid...` *之前* 检查的)
                        // (C 代码的逻辑是：如果一个地雷从 *候选区* 移除，
                        //  并且该方块已打开，则将其添加到 `std` (这是不可能的？))
                        // (不，`grid` 是玩家网格。`delta < 0` 意味着 `todo[i]` 被清空了。
                        //  `todo[i]` (候选区) *不应该* 是 `!= -2` (已打开))
                        // (C 的逻辑是正确的：`if (delta < 0)` ... `grid[...]`...
                        //  `updateGridAfterPerturbation` 已经更新了 `grid`。)
                        // (不，`updateGrid...` 是在 `mineperturb` 内部调用的。)
                        // (C: `mineperturb` *内部* 调用 `updateGrid...`)
                        // (这个 Java 移植也是。)
                        //
                        // 让我们假设 C 的逻辑是：
                        // 如果扰动导致一个方块*变为*数字，则将其添加到 `std`。
                        // `updateGrid...` 已经这样做了 (当 delta < 0 时)。
                        // (C `mines.c` L1467: `if (ret->changes[i].delta < 0 ... std_add ...`)
                        //  这是在 `updateGrid...` 之后。
                        //  `updateGrid...` (L1349) 会在 `delta < 0` 时将 `grid[y*w+x]`
                        //  设置为 *数字*。
                        //  所以 L1467 的 `std_add` 是正确的。)
                        //
                        // Java `updateGridAfterPerturbation` (L1075) 也会在 `delta < 0`
                        // 时将 `grid[y*w+x]` 设置为数字。
                        // 所以这个 `std.add` 在这里是*必需的* (如果 `mineperturb`
                        // 移动了一个 *已打开* (但被标记为雷) 的方块)。
                        if (perts.changes[i].delta < 0 &&
                                grid[perts.changes[i].y * w + perts.changes[i].x] != -2) {
                            std.add(perts.changes[i].y * w + perts.changes[i].x);
                        }

                        // C: list = ss_overlap(...)
                        // 更新所有*包含*这个被修改方块 (changes[i]) 的约束集
                        list = ss.overlap(perts.changes[i].x, perts.changes[i].y, 1);
                        for (Set s_j : list) {
                            s_j.mines += perts.changes[i].delta; // 地雷数 +/- 1
                            ss.addTodo(s_j); // 重新处理这个集合
                        }
                    }

                    // C: sfree(ret->changes); sfree(ret);
                    // Java GC handles this.

                    if (SOLVER_DIAGNOSTICS) {
                        System.out.println("state after perturbation:");
                    }
                    continue; // 返回主循环 (我们取得了进展)
                }
            }

            /*
             * 步骤2.7：彻底失败
             * (推理停滞，且扰动被禁用或失败)
             */
            break;
        } // 结束 while(true)

        /*
         * 第三阶段：最终结果检查
         */
        // 检查是否仍有未知的方块
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
            // C: delpos234(ss, 0)
            while ((s_cleanup = ss.deleteSetAtPosition(0)) != null) {
                // Java GC handles s_cleanup
            }
            ss.freeTree();
            // Java GC handles ss, std
        }

        // C: return nperturbs;
        // (0=成功, >0=N次扰动后成功, -1=失败)
        return nperturbs;
    }

    /**
     * (新增) 扫雷求解器 "提示" (Hint) 入口点
     * <p>
     * 这是一个 "非作弊" 模式，它不使用 `open`/`perturb`/`ctx` 回调。
     * 它只读取玩家视角的 `grid`，并尝试进行逻辑推导。
     * 它不会自动打开安全的方块，而是将它们标记为 -3 (GRID_SAFE)。
     *
     * @param grid 玩家视角的网格
     * @return 0=成功 (但可能未完成), -1=卡住 (没有推论)
     */
    public static int minesolve_hint(int w, int h, int n, byte[] grid, Random rs)
    {
        // 调用内部实现，isHintMode = true
        // 并且 "open", "perturb", "ctx" 均为 null，
        // 因为提示模式在 isHintMode=true 时*不会*使用它们。
        return minesolve_internal(w, h, n, grid, null, null, null, rs, true);
    }

}
