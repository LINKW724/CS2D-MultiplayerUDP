package cs2d.playerAndAi.doublePlayer.mines2;

import java.util.*;

/**
 * 扫雷提示求解器 (HintSolver)
 * * 这是一个 "纯粹" 的逻辑求解器，它不依赖任何 "作弊" 回调 (如 open_cb 或 perturb_cb)。
 * 它像人类玩家一样，仅根据盘面上的已知数字进行所有可能的逻辑推导（包括连锁反应）。
 *
 * 它可以推断出：
 * 1. 绝对安全的格子
 * 2. 绝对是地雷的格子
 * 3. 玩家插错的旗帜（基于逻辑冲突）
 */
public class HintSolver {

    // 提示结果的内部标记
    private static final byte MARK_SAFE = -3;
    private static final byte MARK_KNOWN_MINE = -1; // -1 与玩家的 FLAG 标记共用
    
    // --- 内部数据结构 (移植自 MineSolver) ---
    // (Set, SetStore, SquareTodo, setmunge 等与 MineSolver 相同)
    
    /**
     * C: struct set 的 Java 占位符
     */
    static class Set {
        int x, y;
        int mask;
        int mines;
        boolean todo;
        Set prev, next;
    }

    /**
     * C: struct setstore 的 Java 实现
     */
    static class SetStore {
        private TreeMap<String, Set> sets = new TreeMap<>();
        private LinkedList<Set> todoList = new LinkedList<>();

        private String getKey(int x, int y, int mask) { return x + ":" + y + ":" + mask; }
        private String getKey(Set s) { return getKey(s.x, s.y, s.mask); }

        public void add(int x, int y, int mask, int mines) {
            Set s = new Set();
            s.x = x; s.y = y; s.mask = mask; s.mines = mines;
            s.todo = false;

            String key = getKey(s);
            if (sets.containsKey(key)) {
                // 如果集合已存在，我们可能需要检查地雷数是否冲突
                // (为简化，我们假设C代码的逻辑是正确的：新集合会覆盖或被忽略)
                // C: add234 发现重复，释放
                return;
            }
            sets.put(key, s);
            addTodo(s);
        }

        public void remove(Set s) {
            if (s.todo) {
                todoList.remove(s);
                s.todo = false;
            }
            sets.remove(getKey(s));
        }

        public void addTodo(Set s) {
            if (!s.todo) {
                s.todo = true;
                todoList.addLast(s);
            }
        }

        public Set todo() {
            if (todoList.isEmpty()) return null;
            Set s = todoList.removeFirst();
            s.todo = false;
            return s;
        }

        public List<Set> overlap(int x, int y, int mask) {
            List<Set> result = new ArrayList<>();
            // C 的 overlap 扫描 6x6 区域。Java TreeMap 无法高效实现。
            // 这是一个性能瓶颈，但对于功能是正确的：遍历所有集合。
            for (Set s : sets.values()) {
                if (setmunge(x, y, mask, s.x, s.y, s.mask, false) != 0) {
                    result.add(s);
                }
            }
            return result;
        }
        
        // (省略了 C 代码中用于全局推理的 getSetByIndex, countSets 等)
        // (为保持纯粹的局部推理，我们暂时不实现全局推理)
        // (更新：必须实现全局推理，否则求解器太弱)
        public int countSets() { return sets.size(); }

        public Set getSetByIndex(int index) {
            if (index < 0 || index >= sets.size()) return null;
            return new ArrayList<>(sets.values()).get(index);
        }

        public Set deleteSetAtPosition(int index) {
            Set s = getSetByIndex(index);
            if (s != null) remove(s);
            return s;
        }
    }

    /**
     * C: struct squaretodo
     */
    static class SquareTodo {
        int[] next;
        int head, tail;

        public void initialize(int size) {
            this.next = new int[size];
            this.head = -1;
            this.tail = -1;
        }

        public void add(int i) {
            // C 的实现假设 'i' 永远不会被添加两次。
            // 我们的逻辑(在 known_squares 中)会检查 grid[i] 状态，
            // 确保只添加一次新知识，所以这是安全的。
            next[i] = -1;
            if (tail != -1) {
                next[tail] = i;
            } else {
                head = i;
            }
            tail = i;
        }
    }

    // --- C 核心函数移植 ---
    
    private static int bitcount16(int mask) {
        return Integer.bitCount(mask & 0xFFFF);
    }

    private static int setmunge(int x1, int y1, int mask1, int x2, int y2, int mask2, boolean diff) {
        if (Math.abs(x2-x1) >= 3 || Math.abs(y2-y1) >= 3) {
            mask2 = 0;
        } else {
            while (x2 > x1) { mask2 &= ~(4|32|256); mask2 <<= 1; x2--; }
            while (x2 < x1) { mask2 &= ~(1|8|64); mask2 >>= 1; x2++; }
            while (y2 > y1) { mask2 &= ~(64|128|256); mask2 <<= 3; y2--; }
            while (y2 < y1) { mask2 &= ~(1|2|4); mask2 >>= 3; y2++; }
        }
        if (diff) mask2 ^= 511;
        return mask1 & mask2;
    }

    /**
     * C: random_upto (用于全局推理时的随机集合选择)
     */
    private static int random_upto(Random rs, int n) {
        if (n <= 0) return 0;
        return rs.nextInt(n);
    }
    
    
    /**
     * (已修复) C: known_squares (纯逻辑版)
     *
     * 这是 "人类思考" 求解器的核心修复。
     * 当一个格子被推导出来时（无论是安全还是地雷），
     * 它必须被添加回 `std` 队列，以便它的新知识
     * 可以用于下一轮的集合简化 (Step 2.1.1)。
     */
    private static void known_squares(int w, int h, SquareTodo std, byte[] grid,
                                      int x, int y, int mask, boolean is_mine)
    {
        int bit = 1;

        for (int yy = 0; yy < 3; yy++) {
            for (int xx = 0; xx < 3; xx++) {
                if ((mask & bit) != 0) {
                    int nx = x + xx;
                    int ny = y + yy;

                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        int i = ny * w + nx;

                        // 关键：只处理玩家未知的格子
                        // (玩家已知的数字(>=0)或已插旗(-1)不应被覆盖)
                        // (更新：我们*必须*处理已插旗(-1)的格子，以找出错误旗帜)
                        
                        if (grid[i] == -2 || grid[i] == -1) { // -2=未知, -1=玩家插旗
                            
                            if (is_mine) {
                                // 推导出是地雷
                                if (grid[i] == -2) { // 之前是未知
                                    grid[i] = MARK_KNOWN_MINE; // 标记为地雷
                                    std.add(i); // 添加新知识
                                }
                                // 如果 grid[i] == -1 (玩家已插旗)，那么推导与玩家一致
                                // 我们不需要改变它，也不需要 std.add，因为知识没有更新
                                
                            } else {
                                // 推导出是安全的
                                if (grid[i] == -1) { // 之前是玩家插旗
                                    grid[i] = MARK_SAFE; // 标记为安全 (这将暴露错误旗帜)
                                    std.add(i); // 添加新知识
                                } else if (grid[i] == -2) { // 之前是未知
                                    grid[i] = MARK_SAFE; // 标记为安全
                                    std.add(i); // 添加新知识
                                }
                            }
                        }
                    }
                }
                bit <<= 1;
            }
        }
    }


    /**
     * 扫雷提示求解器的主入口点。
     * * @param w 宽度
     * @param h 高度
     * @param n 地雷总数
     * @param originalGrid 玩家的当前视角网格 (-2=未知, -1=插旗, 0-8=数字)
     * @param rs 随机源 (仅用于全局推理的集合选择)
     * @return 一个新的 byte[] 网格，包含所有推导出的信息
     * (-3=安全, -1=地雷, 0-8=原数字)
     */
    public static byte[] solve(int w, int h, int n, byte[] originalGrid, Random rs) {
        
        // 1. 创建一个我们将在其上工作的副本
        byte[] grid = originalGrid.clone();
        
        // 2. (关键) "像人类一样思考"
        // 为了找出 "插旗错误"，我们必须*无视*所有旗帜，
        // 仅根据数字重新推导一切。
        // 我们将所有玩家旗帜 (-1) 暂时视作未知 (-2)。
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == -1) {
                grid[i] = -2;
            }
        }

        // 3. 运行纯粹的 (非作弊) 求解器逻辑
        // (这是 C: minesolve 的移植和净化版)
        minesolve_pure_logic(w, h, n, grid, rs);

        // 4. 返回充满提示的网格
        return grid;
    }


    /**
     * C: minesolve 的纯逻辑核心 (无作弊回调)
     * (移植自 `minesolve_internal` 并进行净化)
     */
    private static void minesolve_pure_logic(int w, int h, int n, byte[] grid, Random rs)
    {
        SetStore ss = new SetStore();
        List<Set> list;
        SquareTodo std = new SquareTodo();
        int x, y, i, j;

        // --- 第一阶段：初始化待处理方块列表 ---
        std.initialize(w * h);
        for (y = 0; y < h; y++) {
            for (x = 0; x < w; x++) {
                i = y * w + x;
                // 仅将 "数字" 作为初始知识
                if (grid[i] >= 0) {
                    std.add(i);
                }
            }
        }

        // --- 第二阶段：主推理循环 ---
        while (true) {
            boolean done_something = false;
            Set s;

            // --- 步骤2.1：处理待处理列表中的已知方块 ---
            // (这些是新发现的地雷/安全格，或初始的数字格)
            while (std.head != -1) {
                i = std.head;
                std.head = std.next[i];
                if (std.head == -1) std.tail = -1;

                x = i % w;
                y = i / w;

                // 步骤 2.1.1: 如果是数字 (>=0)，则创建集合
                if (grid[i] >= 0) {
                    int dx, dy, mines, bit, val;
                    mines = grid[i];
                    bit = 1;
                    val = 0;

                    for (dy = -1; dy <= +1; dy++) {
                        for (dx = -1; dx <= +1; dx++) {
                            if (x + dx < 0 || x + dx >= w || y + dy < 0 || y + dy >= h) {
                                // 边界外
                            } else {
                                int k = i + dy * w + dx;
                                // 关键：我们只关心 "已推导的地雷" 和 "未知"
                                // 我们忽略玩家的旗帜(因为它们已被设为-2)
                                // 和我们自己找到的安全格(-3)
                                if (grid[k] == MARK_KNOWN_MINE) { // -1
                                    mines--;
                                } else if (grid[k] == -2) { // -2 (未知，或被重置的旗帜)
                                    val |= bit;
                                }
                            }
                            bit <<= 1;
                        }
                    }
                    if (val != 0) {
                        ss.add(x - 1, y - 1, val, mines);
                    }
                }

                // 步骤 2.1.2: 更新包含此方块的现有集合
                // (无论这个方块是数字、地雷(-1)还是安全(-3))
                {
                    list = ss.overlap(x, y, 1);
                    for (Set s_j : list) {
                        int newmask, newmines;
                        s = s_j;
                        newmask = setmunge(s.x, s.y, s.mask, x, y, 1, true);
                        
                        // 如果新知识是地雷，则地雷数-1
                        newmines = s.mines - (grid[i] == MARK_KNOWN_MINE ? 1 : 0);

                        if (newmask != 0) {
                            ss.add(s.x, s.y, newmask, newmines);
                        }
                        ss.remove(s);
                    }
                }
                done_something = true;
            } // 结束 while(std.head != -1)

            
            // --- 步骤2.2：处理集合待办列表 ---
            s = ss.todo();
            if (s != null) {
                // 步骤 2.2.1: 简单情况 (全安全或全地雷)
                if (s.mines == 0 || s.mines == bitcount16(s.mask)) {
                    // (已修复) 调用纯逻辑版 known_squares
                    known_squares(w, h, std, grid, s.x, s.y, s.mask, (s.mines != 0));
                    continue; // 让我们在下一轮处理 std 队列
                }

                // 步骤 2.2.2: 集合间推理 (子集/翅膀)
                list = ss.overlap(s.x, s.y, s.mask);
                for (Set s2 : list) {
                    int swing, s2wing;
                    int swc, s2wc;

                    swing = setmunge(s.x, s.y, s.mask, s2.x, s2.y, s2.mask, true);
                    s2wing = setmunge(s2.x, s2.y, s2.mask, s.x, s.y, s.mask, true);
                    swc = bitcount16(swing);
                    s2wc = bitcount16(s2wing);

                    // 翅膀推理
                    if (swc == s.mines - s2.mines || s2wc == s2.mines - s.mines) {
                        known_squares(w, h, std, grid, s.x, s.y, swing, (swc == s.mines - s2.mines));
                        known_squares(w, h, std, grid, s2.x, s2.y, s2wing, (s2wc == s2.mines - s.mines));
                        continue; 
                    }

                    // 子集推理
                    if (swc == 0 && s2wc != 0) {
                        ss.add(s2.x, s2.y, s2wing, s2.mines - s.mines);
                    }
                    else if (s2wc == 0 && swc != 0) {
                        ss.add(s.x, s.y, swing, s.mines - s2.mines);
                    }
                }
                done_something = true;
            }
            // --- 步骤 2.3: 全局推理 (如果地雷数 n >= 0) ---
            else if (n >= 0) {
                int minesleft, squaresleft;
                squaresleft = 0;
                minesleft = n;
                for (i = 0; i < w * h; i++) {
                    // 我们必须统计 *所有* 标记的地雷，
                    // 无论它们是玩家标记的(-1)还是求解器标记的(也是-1)
                    // ... 但我们已将玩家旗帜重置为 -2。
                    // 所以我们只统计求解器找到的：
                    if (grid[i] == MARK_KNOWN_MINE) minesleft--; // 求解器找到的雷
                    else if (grid[i] == -2) squaresleft++; // 未知 (包括被重置的旗帜)
                    // -3 (安全) 和 0-8 (数字) 不计入
                }

                if (squaresleft == 0) {
                    break; // 成功
                }

                // 简单全局情况
                if (minesleft == 0 || minesleft == squaresleft) {
                    for (i = 0; i < w * h; i++)
                        if (grid[i] == -2)
                            known_squares(w, h, std, grid,
                                    i % w, i / w, 1, minesleft != 0);
                    continue;
                }

                // 复杂全局推理 (C: 虚拟递归)
                int nsets = ss.countSets();
                // (为简化，我们使用与 C 相同的限制)
                if (nsets > 0 && nsets <= 10) {
                    boolean[] setused = new boolean[10];
                    Set[] sets = new Set[setused.length];
                    for (i = 0; i < nsets; i++) sets[i] = ss.getSetByIndex(i);
                    int cursor = 0;
                    
                    // (C 虚拟递归的 Java 移植)
                    while (true) {
                        if (cursor < nsets) {
                            boolean ok = true;
                            for (i = 0; i < cursor; i++)
                                if (setused[i] && setmunge(sets[cursor].x, sets[cursor].y, sets[cursor].mask,
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
                            if (squaresleft > 0 && (minesleft == 0 || minesleft == squaresleft)) {
                                for (i = 0; i < w * h; i++)
                                    if (grid[i] == -2) {
                                        boolean outside = true; y = i / w; x = i % w;
                                        for (j = 0; j < nsets; j++)
                                            if (setused[j] && setmunge(sets[j].x, sets[j].y, sets[j].mask,
                                                            x, y, 1, false) != 0) {
                                                outside = false; break;
                                            }
                                        if (outside)
                                            known_squares(w, h, std, grid, x, y, 1, minesleft != 0);
                                    }
                                done_something = true;
                                break; 
                            }
                            do { cursor--; } while (cursor >= 0 && !setused[cursor]);
                            if (cursor >= 0) {
                                assert(setused[cursor]);
                                minesleft += sets[cursor].mines;
                                squaresleft += bitcount16(sets[cursor].mask);
                                setused[cursor++] = false;
                            }
                            else break;
                        }
                    }
                }
            } // 结束全局推理

            // --- 步骤2.4：检查进展 ---
            if (done_something)
                continue;

            // --- 步骤2.5：推理停滞 ---
            // (我们没有 perturb，所以如果没进展，我们就完成了)
            break;
            
        } // 结束 while(true)

        // --- 第四阶段：清理 (Java GC 会处理) ---
        // (ss.freeTree() 等)
    }
}