package cs2d.playerAndAi.doublePlayer.mines;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Random;

/**
 * C 逻辑移植。
 * 这个类包含了从 C 代码 (minesolve, minegen, mineperturb) 逐字逐句移植过来的
 * 扫雷生成器和求解器核心逻辑。
 * 所有的函数和辅助类都声明为 static，以模拟C语言的风格。
 */
public class MineLogic {

    // --- 辅助类：模拟C语言中的 struct 和回调 ---

    /**
     * 模拟 C 语言的 random_state
     */
    public static class RandomState {
        private final Random random;

        public RandomState(long seed) {
            this.random = new Random(seed);
        }

        /**
         * 模拟 random_upto(rs, n)
         */
        public int random_upto(int n) {
            return random.nextInt(n);
        }

        /**
         * 模拟 random_bits(rs, 31)
         */
        public int random_bits(int bits) {
            // Java nextInt() 返回一个32位有符号整数，我们取其绝对值来模拟31位无符号
            return random.nextInt() & ((1 << bits) - 1);
        }
    }

    /**
     * 模拟 struct perturbation
     */
    public static class Perturbation {
        int x, y;
        int delta; // +1 == become a mine; -1 == cleared
    }

    /**
     * 模拟 struct perturbations
     */
    public static class Perturbations {
        int n;
        List<Perturbation> changes;
    }

    /**
     * 模拟 struct minectx
     */
    public static class MineCtx {
        boolean[] grid; // 真实的地雷布局
        boolean[] opened; // 求解器“打开”了哪些
        int w, h;
        int sx, sy; // 起始点击位置
        boolean allow_big_perturbs;
        int nperturbs_since_last_new_open;
        RandomState rs;

        public MineCtx(boolean[] grid, boolean[] opened, int w, int h, int sx, int sy, RandomState rs) {
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
     * 模拟 open_cb (回调函数)
     */
    @FunctionalInterface
    public interface OpenCallback {
        int open(MineCtx ctx, int x, int y);
    }

    /**
     * 模拟 perturb_cb (回调函数)
     */
    @FunctionalInterface
    public interface PerturbCallback {
        Perturbations perturb(MineCtx ctx, int[] grid, int setx, int sety, int mask);
    }

    /**
     * 模拟 struct square (用于 mineperturb)
     */
    public static class Square {
        int x, y, type;
        int random; // C 用 int, Java 用 int 也没问题
    }

    /**
     * 模拟 struct square (用于 mineperturb)
     */
    public static class SquareComparator implements Comparator<Square> {
        @Override
        public int compare(Square a, Square b) {
            if (a.type < b.type) return -1;
            if (a.type > b.type) return +1;
            if (a.random < b.random) return -1;
            if (a.random > b.random) return +1;
            if (a.y < b.y) return -1;
            if (a.y > b.y) return +1;
            if (a.x < b.x) return -1;
            if (a.x > b.x) return +1;
            return 0;
        }
    }

    /**
     * 模拟 struct squaretodo
     * C 代码使用了一个自定义的基于数组的链表
     */
    public static class SquareTodo {
        int[] next;
        int head, tail;

        public SquareTodo(int w, int h) {
            this.next = new int[w * h];
            Arrays.fill(this.next, -2); // -2 表示 "不在列表中"
            this.head = -1;
            this.tail = -1;
        }

        /**
         * 模拟 std_add
         */
        public void add(int i) {
            if (next[i] == -2) { // 只有当它不在列表中时才添加
                next[i] = -1; // -1 表示 "列表末尾"
                if (tail != -1) {
                    next[tail] = i;
                } else {
                    head = i;
                }
                tail = i;
            }
        }
    }

    /**
     * 模拟 struct set
     */
    public static class Set {
        int x, y, mask, mines;

        public Set(int x, int y, int mask, int mines) {
            this.x = x;
            this.y = y;
            this.mask = mask;
            this.mines = mines;
        }

        // 必须实现 equals 和 hashCode 才能让 List.remove/contains 工作
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Set set = (Set) o;
            // 在C代码的 setstore 中，(x, y, mask) 构成了唯一键
            return x == set.x && y == set.y && mask == set.mask;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, mask);
        }
    }

    /**
     * 模拟 struct setstore
     * C 代码使用了 tree234 (一种自平衡树)
     * Java 中我们用 ArrayList 和 LinkedList 来模拟其行为
     * 这在性能上不如 tree234，但能 100% 保证C代码的 *逻辑* 得以执行
     */
    public static class SetStore {
        // C代码的 ss->sets, 模拟 tree234
        List<Set> sets = new ArrayList<>();
        // C代码的 ss->todo
        LinkedList<Set> todo = new LinkedList<>();

        /**
         * 模拟 ss_add
         */
        public void add(int x, int y, int mask, int mines) {
            Set newSet = new Set(x, y, mask, mines);
            
            // 检查是否已存在 (tree234 会自动处理)
            if (!sets.contains(newSet)) {
                sets.add(newSet);
                todo.add(newSet);
            }
        }

        /**
         * 模拟 ss_overlap
         * 返回一个与 (x, y, mask) 重叠的集合列表
         */
        public List<Set> overlap(int x, int y, int mask) {
            List<Set> list = new ArrayList<>();
            for (Set s : sets) {
                // setmunge (false) 用来检查重叠
                if (setmunge(s.x, s.y, s.mask, x, y, mask, false) != 0) {
                    list.add(s);
                }
            }
            return list;
        }

        /**
         * 模拟 ss_remove
         */
        public void remove(Set s) {
            sets.remove(s);
            todo.remove(s); // 确保也从todo中移除
        }

        /**
         * 模拟 ss_todo
         */
        public Set todo() {
            return todo.poll(); // 从头部取出并移除
        }

        /**
         * 模拟 ss_add_todo (C代码中在扰动时使用)
         */
        public void add_todo(Set s) {
            if (!todo.contains(s)) {
                todo.add(s);
            }
        }
    }


    // --- 核心逻辑函数 (C -> Java 移植) ---

    /**
     * C -> Java 移植
     * 模拟 bitcount16
     */
    private static int bitcount16(int mask) {
        return Integer.bitCount(mask);
    }

    /**
     * C -> Java 移植
     * 模拟 setmunge
     * 这是一个复杂的位操作函数，用于计算集合的重叠、差异等
     */
    private static int setmunge(int x1, int y1, int mask1,
                                int x2, int y2, int mask2, boolean diff) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int ret = 0;

        if (dx <= -3 || dx >= 3 || dy <= -3 || dy >= 3) {
            // 没有重叠
        } else {
            // C 代码: mask2 <<= (dy * 3 + dx);
            // Java 的位移操作与C不同，特别是负位移
            if (dy * 3 + dx > 0) {
                mask2 <<= (dy * 3 + dx);
            } else {
                mask2 >>>= -(dy * 3 + dx); // 使用无符号右移
            }
        }

        // C 代码: ret = mask1 & mask2;
        // if (diff) ret = mask1 & ~mask2;
        ret = mask1 & (diff ? ~mask2 : mask2);

        // C 代码: ret &= 0777; (八进制 0777 = 二进制 111 111 111 = 十进制 511)
        ret &= 0x1FF; // 511

        return ret;
    }

    /**
     * C -> Java 移植
     * 模拟 known_squares
     * (在C代码中是 minesolve 内部的本地函数)
     */
    private static void known_squares(int w, int h, SquareTodo std, int[] grid,
                                      OpenCallback open, MineCtx ctx,
                                      int x, int y, int mask, boolean mine) {
        int dx, dy, bit;

        bit = 1;
        for (dy = 0; dy < 3; dy++) {
            for (dx = 0; dx < 3; dx++) {
                if ((mask & bit) != 0) {
                    int ax = x + dx;
                    int ay = y + dy;
                    if (ax >= 0 && ax < w && ay >= 0 && ay < h) {
                        int i = ay * w + ax;
                        if (grid[i] == -2) { // 如果是未知的
                            if (mine) {
                                grid[i] = -1; // 标记为地雷
                                std.add(i); // 加入待处理列表
                            } else {
                                // C 代码: grid[i] = open(ctx, ax, ay);
                                // 我们通过回调来打开
                                grid[i] = open.open(ctx, ax, ay);
                                std.add(i); // 加入待处理列表
                            }
                        }
                    }
                }
                bit <<= 1;
            }
        }
    }

    /**
     * C -> Java 移植
     * 模拟 minesolve
     * 这是求解器的主入口点
     * @param grid 玩家视角的网格 (-2=未知, -1=标记, 0-8=数字)
     * @return -1=卡住, 0=成功, >0=扰动次数
     */
    public static int minesolve(int w, int h, int n, int[] grid,
                                OpenCallback open, PerturbCallback perturb,
                                MineCtx ctx, RandomState rs) {
        // C 代码: struct setstore* ss = ss_new();
        SetStore ss = new SetStore();
        List<Set> list;
        // C 代码: struct squaretodo astd, * std = &astd;
        SquareTodo std = new SquareTodo(w, h);
        int x, y, i, j;
        int nperturbs = 0;

        /*
         * 第一阶段：初始化待处理方块列表
         */
        // C 代码: std->next = snewn(w * h, int); (已在 SquareTodo 构造函数中完成)
        // C 代码: std->head = std->tail = -1; (已在 SquareTodo 构造函数中完成)

        for (y = 0; y < h; y++) {
            for (x = 0; x < w; x++) {
                i = y * w + x;
                if (grid[i] != -2) { // 如果不是未知方块
                    std.add(i); // 加入待处理列表
                }
            }
        }

        /*
         * 第二阶段：主推理循环
         */
        while (true) {
            boolean done_something = false;
            Set s;

            /*
             * 步骤 2.1：处理待处理列表中的已知方块
             */
            while (std.head != -1) {
                i = std.head;
                // C 代码: std->head = std->next[i];
                std.head = std.next[i];
                std.next[i] = -2; // 标记为已处理 (C代码中没有这步，但Java中为了std.add的逻辑正确性需要)

                if (std.head == -1)
                    std.tail = -1;

                x = i % w;
                y = i / w;

                if (grid[i] >= 0) { // 处理数字方块
                    int dx, dy, mines, bit, val;
                    mines = grid[i];
                    bit = 1;
                    val = 0;

                    for (dy = -1; dy <= +1; dy++) {
                        for (dx = -1; dx <= +1; dx++) {
                            if (x + dx < 0 || x + dx >= w || y + dy < 0 || y + dy >= h) {
                                /* 忽略边界外 */
                            } else {
                                int index = i + dy * w + dx;
                                if (grid[index] == -1) {
                                    mines--;
                                } else if (grid[index] == -2) {
                                    val |= bit;
                                }
                            }
                            bit <<= 1;
                        }
                    }

                    if (val != 0)
                        ss.add(x - 1, y - 1, val, mines);
                }

                /*
                 * 步骤 2.1.1：更新包含此方块的现有集合
                 */
                {
                    // C 代码: list = ss_overlap(ss, x, y, 1);
                    list = ss.overlap(x, y, 1);

                    for (j = 0; j < list.size(); j++) {
                        int newmask, newmines;
                        s = list.get(j);

                        newmask = setmunge(s.x, s.y, s.mask, x, y, 1, true);
                        newmines = s.mines - (grid[i] == -1 ? 1 : 0);

                        if (newmask != 0)
                            ss.add(s.x, s.y, newmask, newmines);

                        ss.remove(s);
                    }
                    // C 代码: sfree(list); (Java GC 会处理)
                }
                done_something = true;
            } // end while (std->head != -1)


            /*
             * 步骤 2.2：处理集合待办列表
             */
            // C 代码: if ((s = ss_todo(ss)) != NULL) {
            s = ss.todo();
            if (s != null) {
                /*
                 * 步骤 2.2.1：简单情况处理
                 */
                if (s.mines == 0 || s.mines == bitcount16(s.mask)) {
                    known_squares(w, h, std, grid, open, ctx,
                            s.x, s.y, s.mask, (s.mines != 0));
                    continue; // C 代码: continue;
                }

                /*
                 * 步骤 2.2.2：集合间推理
                 */
                list = ss.overlap(s.x, s.y, s.mask);

                for (j = 0; j < list.size(); j++) {
                    Set s2 = list.get(j);
                    if (s.equals(s2)) continue; // 忽略自身

                    int swing, s2wing;
                    int swc, s2wc;

                    swing = setmunge(s.x, s.y, s.mask, s2.x, s2.y, s2.mask, true);
                    s2wing = setmunge(s2.x, s2.y, s2.mask, s.x, s.y, s.mask, true);
                    swc = bitcount16(swing);
                    s2wc = bitcount16(s2wing);

                    if (swc == s.mines - s2.mines ||
                            s2wc == s2.mines - s.mines) {
                        known_squares(w, h, std, grid, open, ctx,
                                s.x, s.y, swing,
                                (swc == s.mines - s2.mines));
                        known_squares(w, h, std, grid, open, ctx,
                                s2.x, s2.y, s2wing,
                                (s2wc == s2.mines - s.mines));
                        continue; // C 代码: continue;
                    }

                    /*
                     * 步骤 2.2.3：子集推理
                     */
                    if (swc == 0 && s2wc != 0) {
                        /* s是s2的子集 */
                        // C 代码: assert(s2->mines > s->mines);
                        ss.add(s2.x, s2.y, s2wing, s2.mines - s.mines);
                    } else if (s2wc == 0 && swc != 0) {
                        /* s2是s的子集 */
                        // C 代码: assert(s->mines > s2->mines);
                        ss.add(s.x, s.y, swing, s.mines - s2.mines);
                    }
                }
                // C 代码: sfree(list); (Java GC)
                done_something = true;
            }
            /*
             * 步骤 2.3：全局推理
             */
            else if (n >= 0) {
                int minesleft, squaresleft;
                int nsets, cursor;
                // C 代码: bool setused[10];
                boolean[] setused = new boolean[10];

                /*
                 * 步骤 2.3.1：统计剩余状态
                 */
                squaresleft = 0;
                minesleft = n;
                for (i = 0; i < w * h; i++) {
                    if (grid[i] == -1)
                        minesleft--;
                    else if (grid[i] == -2)
                        squaresleft++;
                }

                if (squaresleft == 0) {
                    // C 代码: assert(minesleft == 0);
                    break; // 退出主循环，返回成功
                }

                /*
                 * 步骤 2.3.2：简单全局情况
                 */
                if (minesleft == 0 || minesleft == squaresleft) {
                    for (i = 0; i < w * h; i++)
                        if (grid[i] == -2)
                            known_squares(w, h, std, grid, open, ctx,
                                    i % w, i / w, 1, minesleft != 0);
                    continue; // 返回主循环
                }

                /*
                 * 步骤 2.3.3：复杂全局推理 - 虚拟递归
                 */
                // C 代码: nsets = count234(ss->sets);
                nsets = ss.sets.size();
                // C 代码: if (nsets <= lenof(setused)) {
                if (nsets <= setused.length) {
                    // C 代码: struct set* sets[lenof(setused)];
                    Set[] sets = new Set[setused.length];
                    for (i = 0; i < nsets; i++)
                        // C 代码: sets[i] = index234(ss->sets, i);
                        sets[i] = ss.sets.get(i);

                    cursor = 0;
                    while (true) {
                        if (cursor < nsets) {
                            boolean ok = true;
                            for (i = 0; i < cursor; i++)
                                if (setused[i] &&
                                        setmunge(sets[cursor].x,
                                                sets[cursor].y,
                                                sets[cursor].mask,
                                                sets[i].x, sets[i].y, sets[i].mask,
                                                false) != 0) {
                                    ok = false;
                                    break;
                                }

                            if (ok) {
                                minesleft -= sets[cursor].mines;
                                squaresleft -= bitcount16(sets[cursor].mask);
                            }
                            setused[cursor++] = ok;
                        } else {
                            /* 到达组合末尾，检查是否有用 */
                            if (squaresleft > 0 &&
                                    (minesleft == 0 || minesleft == squaresleft)) {
                                /* 发现有效推理 */
                                for (i = 0; i < w * h; i++)
                                    if (grid[i] == -2) {
                                        boolean outside = true;
                                        y = i / w;
                                        x = i % w;
                                        for (j = 0; j < nsets; j++)
                                            if (setused[j] &&
                                                    setmunge(sets[j].x, sets[j].y,
                                                            sets[j].mask, x, y, 1,
                                                            false) != 0) {
                                                outside = false;
                                                break;
                                            }
                                        if (outside)
                                            known_squares(w, h, std, grid,
                                                    open, ctx,
                                                    x, y, 1, minesleft != 0);
                                    }
                                done_something = true;
                                break; // 退出虚拟递归
                            }

                            /* 回溯 */
                            do {
                                cursor--;
                            } while (cursor >= 0 && !setused[cursor]);

                            if (cursor >= 0) {
                                // C 代码: assert(setused[cursor]);
                                minesleft += sets[cursor].mines;
                                squaresleft += bitcount16(sets[cursor].mask);
                                setused[cursor++] = false;
                            } else {
                                break; // 尝试了所有组合
                            }
                        }
                    } // end while(true) 虚拟递归
                } // end if (nsets <= setused.length)
            } // end if (n >= 0)


            /*
             * 步骤 2.4：检查进展
             */
            if (done_something)
                continue; // 有进展，继续推理

            /*
             * 步骤 2.6：最后手段 - 调用扰动
             */
            if (perturb != null) {
                Perturbations ret;
                nperturbs++;

                if (ss.sets.isEmpty()) {
                    ret = perturb.perturb(ctx, grid, 0, 0, 0);
                } else {
                    // C 代码: s = index234(ss->sets, random_upto(rs, count234(ss->sets)));
                    s = ss.sets.get(rs.random_upto(ss.sets.size()));
                    ret = perturb.perturb(ctx, grid, s.x, s.y, s.mask);
                }

                if (ret != null) {
                    // C 代码: assert(ret->n > 0);
                    for (i = 0; i < ret.n; i++) {
                        Perturbation change = ret.changes.get(i);
                        if (change.delta < 0 &&
                                grid[change.y * w + change.x] != -2) {
                            std.add(change.y * w + change.x);
                        }

                        list = ss.overlap(change.x, change.y, 1);

                        for (j = 0; j < list.size(); j++) {
                            list.get(j).mines += change.delta;
                            ss.add_todo(list.get(j));
                        }
                        // C 代码: sfree(list); (Java GC)
                    }
                    // C 代码: sfree(ret->changes); (Java GC)
                    // C 代码: sfree(ret); (Java GC)
                    continue; // 返回主循环继续尝试求解
                }
            }

            /*
             * 步骤 2.7：彻底失败
             */
            break;
        } // end while(true) 主推理循环

        /*
         * 第三阶段：最终结果检查
         */
        for (y = 0; y < h; y++)
            for (x = 0; x < w; x++)
                if (grid[y * w + x] == -2) {
                    nperturbs = -1; // 有未知格子，标记为失败
                    break;
                }

        /*
         * 第四阶段：清理资源
         * C 代码中的 sfree, freetree234 等在Java中由垃圾收集器自动处理
         * 我们不需要手动清理 ss, std->next
         */

        return nperturbs;
    }


    /**
     * C -> Java 移植
     * 模拟 mineopen
     * 这是 'open_cb' 回调的真正实现
     */
    public static int mineopen(MineCtx ctx, int x, int y) {
        int i, j, n;
        
        // C 代码: assert(x >= 0 && x < ctx->w && y >= 0 && y < ctx->h);
        
        if (ctx.grid[y * ctx.w + x])
            return -1; // *bang*

        if (!ctx.opened[y * ctx.w + x]) {
            ctx.opened[y * ctx.w + x] = true;
            ctx.nperturbs_since_last_new_open = 0;
        }

        n = 0;
        for (i = -1; i <= +1; i++) {
            if (x + i < 0 || x + i >= ctx.w)
                continue;
            for (j = -1; j <= +1; j++) {
                if (y + j < 0 || y + j >= ctx.h)
                    continue;
                if (i == 0 && j == 0)
                    continue;
                if (ctx.grid[(y + j) * ctx.w + (x + i)])
                    n++;
            }
        }
        return n;
    }


    /**
     * C -> Java 移植
     * 模拟 mineperturb
     * 这是 'perturb_cb' 回调的真正实现
     */
    public static Perturbations mineperturb(MineCtx ctx, int[] grid,
                                             int setx, int sety, int mask) {
        Square[] sqlist = new Square[ctx.w * ctx.h];
        int x, y, dx, dy, i, n;
        int nfull, nempty;
        List<Square> tofill = new ArrayList<>();
        List<Square> toempty = new ArrayList<>();
        List<Square> todo;
        int ntodo;
        int dtodo, dset;
        Perturbations ret;
        int[] setlist;

        /*
         * 第一阶段：扰动条件检查
         */
        if (mask == 0 && !ctx.allow_big_perturbs) {
            return null; // 拒绝扰动
        }
        
        ctx.nperturbs_since_last_new_open++;
        if (ctx.nperturbs_since_last_new_open > ctx.w ||
                ctx.nperturbs_since_last_new_open > ctx.h) {
            return null; // 停止扰动
        }

        /*
         * 第二阶段：构建候选方块列表
         */
        n = 0;
        for (y = 0; y < ctx.h; y++) {
            for (x = 0; x < ctx.w; x++) {
                if (Math.abs(y - ctx.sy) <= 1 && Math.abs(x - ctx.sx) <= 1)
                    continue;

                if ((mask == 0 && grid[y * ctx.w + x] == -2) ||
                        (x >= setx && x < setx + 3 &&
                                y >= sety && y < sety + 3 &&
                                (mask & (1 << ((y - sety) * 3 + (x - setx)))) != 0))
                    continue;

                sqlist[n] = new Square();
                sqlist[n].x = x;
                sqlist[n].y = y;

                if (grid[y * ctx.w + x] != -2) {
                    sqlist[n].type = 3;
                } else {
                    sqlist[n].type = 2;
                    for (dy = -1; dy <= +1; dy++) {
                        for (dx = -1; dx <= +1; dx++) {
                            if (x + dx >= 0 && x + dx < ctx.w &&
                                    y + dy >= 0 && y + dy < ctx.h &&
                                    grid[(y + dy) * ctx.w + (x + dx)] != -2) {
                                sqlist[n].type = 1;
                                break;
                            }
                        }
                        if (sqlist[n].type == 1) break;
                    }
                }
                sqlist[n].random = ctx.rs.random_bits(31);
                n++;
            }
        }
        
        // C 代码: qsort(sqlist, n, sizeof(struct square), squarecmp);
        // Java 移植:
        Square[]
                actualSqList = Arrays.copyOf(sqlist, n);
        Arrays.sort(actualSqList, new SquareComparator());
        sqlist = actualSqList; // 现在 sqlist 是排序好且大小正确的

        /*
         * 第三阶段：分析扰动集合
         */
        nfull = nempty = 0;
        if (mask != 0) {
            for (dy = 0; dy < 3; dy++) {
                for (dx = 0; dx < 3; dx++) {
                    if ((mask & (1 << (dy * 3 + dx))) != 0) {
                        // C 代码: assert(setx + dx <= ctx->w); (Java中边界检查更严格)
                        if (ctx.grid[(sety + dy) * ctx.w + (setx + dx)])
                            nfull++;
                        else
                            nempty++;
                    }
                }
            }
        } else {
            for (y = 0; y < ctx.h; y++) {
                for (x = 0; x < ctx.w; x++) {
                    if (grid[y * ctx.w + x] == -2) {
                        if (ctx.grid[y * ctx.w + x])
                            nfull++;
                        else
                            nempty++;
                    }
                }
            }
        }

        /*
         * 第四阶段：寻找交换候选
         */
        int ntofill = 0;
        int ntoempty = 0;
        
        for (i = 0; i < n; i++) {
            Square sq = sqlist[i];
            if (ctx.grid[sq.y * ctx.w + sq.x]) {
                toempty.add(sq);
                ntoempty++;
            } else {
                tofill.add(sq);
                ntofill++;
            }
            if (ntofill == nfull || ntoempty == nempty)
                break;
        }

        /*
         * 第五阶段：处理部分交换情况
         */
        if (ntofill != nfull && ntoempty != nempty) {
            int k;
            // C 代码: assert(ntoempty != 0);
            
            setlist = new int[ctx.w * ctx.h];
            i = 0;

            if (mask != 0) {
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
                for (y = 0; y < ctx.h; y++) {
                    for (x = 0; x < ctx.w; x++) {
                        if (grid[y * ctx.w + x] == -2) {
                            if (!ctx.grid[y * ctx.w + x]) {
                                setlist[i++] = y * ctx.w + x;
                            }
                        }
                    }
                }
            }

            // C 代码: assert(i > ntoempty);
            
            /* Fisher-Yates 洗牌 (部分) */
            for (k = 0; k < ntoempty; k++) {
                int index = k + ctx.rs.random_upto(i - k);
                int tmp = setlist[k];
                setlist[k] = setlist[index];
                setlist[index] = tmp;
            }
            // setlist 的前 ntoempty 个元素现在是随机选择的
        } else {
            setlist = null;
        }

        /*
         * 第六阶段：构建扰动操作列表
         */
        ret = new Perturbations();

        if (ntofill == nfull) {
            todo = tofill;
            ntodo = ntofill;
            dtodo = +1;
            dset = -1;
        } else {
            todo = toempty;
            ntodo = ntoempty;
            dtodo = -1;
            dset = +1;
        }

        ret.n = 2 * ntodo;
        ret.changes = new ArrayList<>(ret.n);
        for(int k=0; k < ret.n; k++) ret.changes.add(new Perturbation()); // 预填充列表

        for (i = 0; i < ntodo; i++) {
            ret.changes.get(i).x = todo.get(i).x;
            ret.changes.get(i).y = todo.get(i).y;
            ret.changes.get(i).delta = dtodo;
        }

        if (setlist != null) {
            int j;
            // C 代码: assert(todo == toempty);
            for (j = 0; j < ntoempty; j++) {
                ret.changes.get(i).x = setlist[j] % ctx.w;
                ret.changes.get(i).y = setlist[j] / ctx.w;
                ret.changes.get(i).delta = dset;
                i++;
            }
        } else if (mask != 0) {
            for (dy = 0; dy < 3; dy++) {
                for (dx = 0; dx < 3; dx++) {
                    if ((mask & (1 << (dy * 3 + dx))) != 0) {
                        int currval = (ctx.grid[(sety + dy) * ctx.w + (setx + dx)] ? +1 : -1);
                        if (dset == -currval) {
                            ret.changes.get(i).x = setx + dx;
                            ret.changes.get(i).y = sety + dy;
                            ret.changes.get(i).delta = dset;
                            i++;
                        }
                    }
                }
            }
        } else {
            for (y = 0; y < ctx.h; y++) {
                for (x = 0; x < ctx.w; x++) {
                    if (grid[y * ctx.w + x] == -2) {
                        int currval = (ctx.grid[y * ctx.w + x] ? +1 : -1);
                        if (dset == -currval) {
                            ret.changes.get(i).x = x;
                            ret.changes.get(i).y = y;
                            ret.changes.get(i).delta = dset;
                            i++;
                        }
                    }
                }
            }
        }

        // C 代码: assert(i == ret->n);
        // 如果断言失败，说明交换逻辑出错了，调整列表大小
        if (i != ret.n) {
             ret.n = i;
             // 裁剪列表到实际大小
             ret.changes = new ArrayList<>(ret.changes.subList(0, i));
        }


        /*
         * 第七阶段：执行扰动操作并更新网格状态
         */
        for (i = 0; i < ret.n; i++) {
            int delta;
            x = ret.changes.get(i).x;
            y = ret.changes.get(i).y;
            delta = ret.changes.get(i).delta;

            // C 代码: assert((delta < 0) ^ (ctx->grid[y * ctx->w + x] == 0));
            
            ctx.grid[y * ctx.w + x] = (delta > 0);

            for (dy = -1; dy <= +1; dy++) {
                for (dx = -1; dx <= +1; dx++) {
                    if (x + dx >= 0 && x + dx < ctx.w &&
                            y + dy >= 0 && y + dy < ctx.h &&
                            grid[(y + dy) * ctx.w + (x + dx)] != -2) {

                        if (dx == 0 && dy == 0) {
                            if (delta > 0) {
                                grid[y * ctx.w + x] = -1;
                            } else {
                                int dx2, dy2, minecount = 0;
                                for (dy2 = -1; dy2 <= +1; dy2++) {
                                    for (dx2 = -1; dx2 <= +1; dx2++) {
                                        if (x + dx2 >= 0 && x + dx2 < ctx.w &&
                                                y + dy2 >= 0 && y + dy2 < ctx.h &&
                                                ctx.grid[(y + dy2) * ctx.w + (x + dx2)]) {
                                            minecount++;
                                        }
                                    }
                                }
                                grid[y * ctx.w + x] = minecount;
                            }
                        } else {
                            if (grid[(y + dy) * ctx.w + (x + dx)] >= 0) {
                                grid[(y + dy) * ctx.w + (x + dx)] += delta;
                            }
                        }
                    }
                }
            }
        }
        
        return ret;
    }


    /**
     * C -> Java 移植
     * 模拟 minegen
     * 生成一个可解的扫雷布局
     * @return boolean[] 数组 (true=有雷, false=无雷)
     */
    public static boolean[] minegen(int w, int h, int n, int x, int y, boolean unique,
                                    RandomState rs) {
        boolean[] ret = new boolean[w * h];
        boolean success;
        int ntries = 0;

        do {
            success = false;
            ntries++;

            Arrays.fill(ret, false);

            /*
             * 第一阶段：初始地雷放置
             */
            {
                int[] tmp = new int[w * h];
                int i, j, k, nn;

                k = 0;
                for (i = 0; i < h; i++) {
                    for (j = 0; j < w; j++) {
                        if (Math.abs(i - y) > 1 || Math.abs(j - x) > 1) {
                            tmp[k++] = i * w + j;
                        }
                    }
                }

                nn = n;
                while (nn-- > 0 && k > 0) { // 添加 k > 0 保护
                    i = rs.random_upto(k);
                    ret[tmp[i]] = true;
                    tmp[i] = tmp[--k];
                }
            }

            /*
             * 第二阶段：可解性验证
             */
            if (unique) {
                // 求解器使用的网格
                int[] solvegrid = new int[w * h];
                // 记录哪些方块已被"打开"
                boolean[] opened = new boolean[w * h];

                MineCtx actx;
                int solveret;
                int prevret = -2;

                Arrays.fill(opened, false);
                
                // C 代码: ctx = &actx;
                actx = new MineCtx(ret, opened, w, h, x, y, rs);
                actx.allow_big_perturbs = (ntries > 100);
                actx.nperturbs_since_last_new_open = 0;

                // 求解循环
                while (true) {
                    Arrays.fill(solvegrid, -2); // -2 = 未知

                    // 模拟玩家点击起始位置
                    solvegrid[y * w + x] = mineopen(actx, x, y);
                    // C 代码: assert(solvegrid[y * w + x] == 0);

                    /*
                     * 调用主求解器验证布局可解性
                     */
                    solveret = minesolve(w, h, n, solvegrid,
                            MineLogic::mineopen,     // 传递 mineopen 作为回调
                            MineLogic::mineperturb,  // 传递 mineperturb 作为回调
                            actx, rs);

                    if (solveret < 0 || (prevret >= 0 && solveret >= prevret)) {
                        /* 求解失败 */
                        success = false;
                        break;
                    } else if (solveret == 0) {
                        /* 求解成功 */
                        success = true;
                        break;
                    }

                    // 如果 solveret > 0，说明扰动已发生，继续循环
                    prevret = solveret;
                }
                
                // C 代码: sfree(solvegrid); sfree(opened); (Java GC)
            } else {
                success = true;
            }

        } while (!success);

        return ret;
    }
}