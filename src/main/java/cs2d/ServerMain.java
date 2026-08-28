package cs2d;

import javax.swing.SwingUtilities;
import cs2d.server.ServerControlPanel;

/**
 * 主类，是整个服务器应用程序的入口点。
 * 它的唯一作用就是创建并显示服务器控制面板的GUI。
 */
public class ServerMain {
    /**
     * Java程序的main方法。
     * @param args 命令行参数（在此程序中未使用）。
     */
    public static void main(String[] args) {
        // 使用 SwingUtilities.invokeLater 来确保GUI的创建和更新
        // 都在事件分发线程（Event Dispatch Thread, EDT）上进行。
        // 这是所有Swing应用程序的标准做法，可以避免因多线程访问UI组件而导致
        // 的潜在的线程安全问题和界面无响应。
        SwingUtilities.invokeLater(ServerControlPanel::new);
    }
}
// Last Updated: 2025-09-19 12:01 PM MDT
