package cs2d.test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Random;

public class UDPClientTest {
    public static void main(String[] args) {
        try (DatagramSocket socket = new DatagramSocket()) {
            // 传输1GB数据可能需要一些时间，将超时延长至60秒
            socket.setSoTimeout(60000);

            // --- 准备1GB虚拟文件 ---
            // 注意: 这会占用 1GB 内存。如果你的内存不足，请减小这个值。
            final long FILE_SIZE_BYTES = 1L * 1024 * 1024 * 1024; // 1 GB
            final int PACKET_DATA_SIZE = 1400; // 每个包的数据负载大小

            System.out.println("正在内存中创建 1GB 虚拟文件，请稍候...");
            // 这个虚拟文件只存在于内存中，程序结束后会自动被垃圾回收，相当于“删除”
            byte[] virtualFile = new byte[(int)FILE_SIZE_BYTES];
            new Random().nextBytes(virtualFile); // 用随机数据填充文件
            System.out.println("虚拟文件创建完毕。");

            InetAddress address = InetAddress.getByName("127.0.0.1");
            int port = 14726;
            long totalPackets = (long) Math.ceil((double) FILE_SIZE_BYTES / PACKET_DATA_SIZE);

            System.out.println("\n--- UDP 持续压力测试 (1GB) ---");
            System.out.printf("准备传输虚拟文件: %.2f GB\n", (double) FILE_SIZE_BYTES / (1024 * 1024 * 1024));
            System.out.printf("数据包大小: %d bytes, 总包数: %d\n", PACKET_DATA_SIZE, totalPackets);
            System.out.println("--------------------------------------------------");
            System.out.println("[客户端] 开始持续发送数据 (有多快发多快)...");

            long startTime = System.nanoTime();
            long lastUpdateTime = startTime;
            long bytesSentSinceUpdate = 0;

            // --- 循环发送文件数据包 ---
            for (long i = 0; i < totalPackets; i++) {
                int offset = (int) (i * PACKET_DATA_SIZE);
                int length = (int) Math.min(PACKET_DATA_SIZE, FILE_SIZE_BYTES - offset);

                DatagramPacket sendPacket = new DatagramPacket(virtualFile, offset, length, address, port);
                socket.send(sendPacket);
                bytesSentSinceUpdate += length;

                // 每秒更新一次进度
                long now = System.nanoTime();
                if (now - lastUpdateTime > 1_000_000_000) {
                    double rate = (double) bytesSentSinceUpdate / (1024 * 1024) / ((now - lastUpdateTime) / 1_000_000_000.0);
                    System.out.printf("[客户端] 进度: %.2f%%, 当前速率: %.2f MB/s\n", ((double)(i+1) / totalPackets) * 100, rate);
                    lastUpdateTime = now;
                    bytesSentSinceUpdate = 0;
                }
            }

            // --- 发送结束信号 ---
            String endMessage = "TRANSFER_COMPLETE";
            byte[] endBuffer = endMessage.getBytes();
            DatagramPacket endPacket = new DatagramPacket(endBuffer, endBuffer.length, address, port);
            socket.send(endPacket);

            long endTime = System.nanoTime();
            double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
            double transferRateMBps = (FILE_SIZE_BYTES / (1024.0 * 1024.0)) / durationSeconds;

            System.out.println("[客户端] 所有数据包已发送完毕。");
            System.out.printf("[客户端] 总耗时: %.3f 秒\n", durationSeconds);
            System.out.printf("[客户端] 平均发送速率: %.2f MB/s\n", transferRateMBps);
            System.out.println("[客户端] 正在等待服务器的最终确认...");

            // --- 等待服务器的确认回包 ---
            byte[] receiveBuffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(receivePacket);

            String replyMessage = new String(receivePacket.getData(), 0, receivePacket.getLength());
            System.out.println("\n--- 测试结果 ---");
            System.out.println("[客户端] 成功收到服务器的确认!");
            System.out.println("   - 服务器回复: \"" + replyMessage + "\"");
            System.out.println("✅ [诊断结论] UDP高吞吐量 (1GB) 数据传输测试通过！");

        } catch (SocketTimeoutException e) {
            System.out.println("\n--- 测试结果 ---");
            System.out.println("[客户端] 接收服务器最终确认时超时！");
            System.out.println("❌ [诊断结论] 在高负载情况下，网络通信可能存在严重丢包或服务器处理不过来。");
            System.out.println("   UDP是不可靠的，在极高速率下，部分数据包丢失是正常现象。如果服务器收到的数据远少于1GB，说明丢包严重。");

        } catch (OutOfMemoryError e) {
            System.out.println("\n--- 错误 ---");
            System.out.println("❌ 内存不足！无法在内存中创建 1GB 大小的虚拟文件。");
            System.out.println("   请尝试减小 FILE_SIZE_BYTES 的值，或者为JVM分配更多的堆内存 (例如使用 -Xmx2g 启动参数)。");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

