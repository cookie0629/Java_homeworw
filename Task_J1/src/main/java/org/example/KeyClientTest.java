package org.example;

public class KeyClientTest {
    public static void main(String[] args) {
        int numberOfClients = 50;
        String host = "127.0.0.1";
        String port = "5555";

        for (int i = 0; i < numberOfClients; i++) {
            final int clientId = i;
            new Thread(() -> {
                try {
                    KeyClient.main(new String[]{
                            "clientnew" + clientId,
                            host,                 // 服务端地址
                            port                  // 服务端端口
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }
}
