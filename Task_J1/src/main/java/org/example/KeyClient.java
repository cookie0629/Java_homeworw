package org.example;

// 简单阻塞客户端：发送 name\0，然后根据协议读取两个长度+blob并写入文件
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class KeyClient {
    static void usageAndExit() {
        System.err.println("Usage: java KeyClient [--delay seconds] [--exit-after-send] <name> <host> <port>");
        System.err.println("Example: java KeyClient alice 127.0.0.1 5555");
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        int idx = 0;
        int delaySeconds = 0;
        boolean exitAfterSend = false;
        if (args.length < 3) usageAndExit();
        while (idx < args.length && args[idx].startsWith("--")) {
            if (args[idx].equals("--delay")) {
                idx++;
                if (idx >= args.length) usageAndExit();
                delaySeconds = Integer.parseInt(args[idx++]);
            } else if (args[idx].equals("--exit-after-send")) {
                exitAfterSend = true;
                idx++;
            } else usageAndExit();
        }
        if (idx + 3 > args.length) usageAndExit();
        String name = args[idx++];
        String host = args[idx++];
        int port = Integer.parseInt(args[idx++]);

        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port));
        OutputStream os = sock.getOutputStream();
        InputStream is = sock.getInputStream();

        // send name + null byte
        os.write(name.getBytes(StandardCharsets.US_ASCII));
        os.write(0);
        os.flush();
        System.out.println("Sent name '" + name + "' to server.");

        if (exitAfterSend) {
            System.out.println("--exit-after-send specified: closing socket without reading response.");
            sock.close();
            return;
        }

        if (delaySeconds > 0) {
            System.out.println("Delaying " + delaySeconds + " seconds before reading (simulate slow client)...");
            Thread.sleep(delaySeconds * 1000L);
        }

        // read 4 bytes length of key
        byte[] len4 = is.readNBytes(4);
        if (len4.length < 4) throw new EOFException("Unexpected EOF when reading key length");
        int keyLen = ByteBuffer.wrap(len4).getInt();
        byte[] keyBytes = is.readNBytes(keyLen);
        if (keyBytes.length < keyLen) throw new EOFException("Unexpected EOF when reading key bytes");

        byte[] len4c = is.readNBytes(4);
        if (len4c.length < 4) throw new EOFException("Unexpected EOF when reading cert length");
        int certLen = ByteBuffer.wrap(len4c).getInt();
        byte[] certBytes = is.readNBytes(certLen);
        if (certBytes.length < certLen) throw new EOFException("Unexpected EOF when reading cert bytes");

        // write files
        String keyFile = name + ".key";
        String certFile = name + ".crt";
        try (FileOutputStream fk = new FileOutputStream(keyFile)) { fk.write(keyBytes); }
        try (FileOutputStream fc = new FileOutputStream(certFile)) { fc.write(certBytes); }
        System.out.println("Saved private key -> " + keyFile);
        System.out.println("Saved certificate -> " + certFile);

        sock.close();
    }

}


/*KeyClient <name> <server-host> <server-port>
KeyClient alice 127.0.0.1 5555

带 delay（等待若干秒再读响应，模拟慢客户端）
KeyClient --delay 5 alice 127.0.0.1 5555

崩溃/断开模拟：发送后立即关闭（不读响应）
--exit-after-send alice 127.0.0.1 5555
*/

/* 1. KeyClient.java
这是一个简单的阻塞式客户端，用于：
连接服务器
发送一个名称（如 "alice"）
接收服务器返回的私钥和证书
保存为 .key 和 .crt 文件
主要功能：
支持 --delay 参数：模拟慢客户端，延迟读取响应
支持 --exit-after-send：发送完名称后立即关闭，不读响应（模拟崩溃）
2. KeyServer.java
这是一个非阻塞式服务器，使用 Java NIO 的 Selector 和 Channel 处理多个客户端连接，避免为每个客户端创建一个线程。
主要组件：
KeyResult：保存生成的私钥和证书（PEM 格式）
ClientAttachment：每个连接的上下文（读缓冲区、名称、写缓冲区等）
nameTable：缓存已经生成过的密钥对（ConcurrentHashMap）
waitingClients：记录正在等待某个名称结果的客户端列表
generatorPool：线程池用于异步生成密钥和证书（因为生成很慢）
 */
