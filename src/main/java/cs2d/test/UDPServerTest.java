package cs2d.test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UDPServerTest {
    public static void main(String[] args) {
        try (DatagramSocket socket = new DatagramSocket(14726)) {
            System.out.println("--- UDP 持续压力测试服务器 (1GB) ---");
            System.out.println("[服务器] 正在端口 14726 上等待数据...");

            byte[] receiveBuffer = new byte[2048]; // 使用稍大的缓冲区以防万一
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            long totalBytesReceived = 0;
            long packetCount = 0;
            InetAddress clientAddress = null;
            int clientPort = 0;

            long startTime = 0;

            // --- 循环接收数据 ---
            while (true) {
                socket.receive(receivePacket);

                if (clientAddress == null) {
                    clientAddress = receivePacket.getAddress();
                    clientPort = receivePacket.getPort();
                    startTime = System.nanoTime();
                    System.out.println("[服务器] 检测到来自 " + clientAddress.getHostAddress() + ":" + clientPort + " 的连接，开始接收数据...");
                }

                String receivedData = new String(receivePacket.getData(), 0, receivePacket.getLength());

                // 检查是否是结束信号
                if (receivedData.equals("TRANSFER_COMPLETE")) {
                    System.out.println("\n[服务器] 收到传输完成信号。");
                    break; // 结束接收循环
                }

                totalBytesReceived += receivePacket.getLength();
                packetCount++;

                // 每收到10000个包打印一次进度
                if (packetCount % 10000 == 0) {
                    System.out.printf("\r[服务器] 已接收 %d 个数据包 (%.2f MB)...", packetCount, (double) totalBytesReceived / (1024 * 1024));
                }
            }

            long endTime = System.nanoTime();
            double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
            double transferRateMBps = (totalBytesReceived / (1024.0 * 1024.0)) / durationSeconds;


            System.out.println("\n--------------------------------------------------");
            System.out.println("[服务器] 数据接收完毕。");
            System.out.printf("[服务器] 总耗时: %.3f 秒\n", durationSeconds);
            System.out.printf("[服务器] 平均接收速率: %.2f MB/s\n", transferRateMBps);
            System.out.printf("[服务器] 总计收到数据包: %d 个\n", packetCount);
            System.out.printf("[服务器] 总计收到字节数: %d bytes (%.2f MB)\n", totalBytesReceived, (double) totalBytesReceived / (1024 * 1024));

            // --- 向客户端发送确认信息 ---
            String confirmationMessage = String.format("确认收到 %d 个包, 共计 %.2f MB", packetCount, (double) totalBytesReceived / (1024 * 1024));
            byte[] sendBuffer = confirmationMessage.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, clientAddress, clientPort);

            socket.send(sendPacket);
            System.out.println("[服务器] 已向客户端发送最终确认。测试结束。");


        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

