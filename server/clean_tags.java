import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import java.util.*;

public class clean_tags {
    public static void main(String[] args) throws Exception {
        String[] files = {
                "o:/java/games/CS2D-MultiplayerUDP/src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java",
                "o:/java/games/CS2D-MultiplayerUDP/src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java"
        };
        Pattern tagsPattern = Pattern.compile(
                "\\[(新增|修改|重大修改|修复|核心修复|核心|优化|REALISTIC|已修改|新辅助方法|!! 同步客户端逻辑 !!|!! 核心修复 !!|由 .+ 增加到|签名修改|Error Fix|公共 DTO|已移除|新的公共 API|异步执行|自动计算)\\]\\s*");

        for (String filePath : files) {
            Path path = Paths.get(filePath);
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

            content = tagsPattern.matcher(content).replaceAll("");

            // Remove block comments like // --- 新增 Import ---
            content = content.replaceAll("(?m)^\\s*//\\s*---\\s*(?:新增|结束新增|新增：|\\[新增[^\\]]*\\]).*?---\\s*$\\n", "");

            String[] specificReplacements = {
                    "// --- [REALISTIC 难度专项：蜂群意识共享] ---\n",
                    "// --- [新增逻辑结束] ---\n",
                    "// --- VVVV 核心修改 VVVV ---\n",
                    "// --- ^^^^ 修复结束 ^^^^ ---\n",
                    "// --- VVVV [核心修复] VVVV ---\n",
                    "// --- ^^^^ [修复结束] ^^^^ ---\n",
                    "// --- [新增：短枪远距离绕路与掩护] ---\n",
                    "// --- 5.设置重生后的初始冲锋目标 ---\n"
            };

            for (String sr : specificReplacements) {
                content = content.replace(sr, "");
            }

            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            System.out.println("Processed " + filePath);
        }
    }
}
