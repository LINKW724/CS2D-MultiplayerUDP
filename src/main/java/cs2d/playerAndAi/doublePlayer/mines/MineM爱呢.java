package cs2d.playerAndAi.doublePlayer.mines;

import java.util.Stack;

public class MineM爱呢 {
    public static void main(String[] args) {
        Stack<Integer> stack = new Stack<>();;
        stack.add(1);
        stack.add(3);
        stack.add(5);
        stack.add(7);
        stack.add(9);
        System.out.println(stack.get(stack.size() - 1));

        StringBuilder b = new StringBuilder();
        b.append(2);
        b.insert(0,2);
        b.append(1);
        b.insert(0,1);
        System.out.println("StringBuilder :" + b);
        b.delete(0,b.length());
        System.out.println("StringBuilderDelete :" + b);
    }
}
