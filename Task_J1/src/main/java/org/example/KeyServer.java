package org.example;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

public class KeyServer {
    // Result container (PEM strings) 封装生成的密钥和证书（PEM字符串格式）
    static class KeyResult {
        final String privateKeyPem; // PKCS#8 PEM
        final String certPem;       // X.509 PEM
        KeyResult(String privateKeyPem, String certPem) {
            this.privateKeyPem = privateKeyPem;
            this.certPem = certPem;
        }
    }

    // Per-client attachment for selector 每个客户端连接的上下文信息
    static class ClientAttachment {
        final SocketChannel channel;
        final ByteArrayOutputStream readBuffer = new ByteArrayOutputStream();
        boolean nameReceived = false;
        String name = null;
        // write buffer prepared when result is ready
        ByteBuffer writeBuffer = null;
        ClientAttachment(SocketChannel ch){ this.channel = ch; }
    }
//1 为什么使用线程池threadpol
// 2 为什么我们需要селектор

    private final int port;
    private final PrivateKey issuerKey;
    private final X500Name issuerX500;
    private final int generatorThreads;
    //密钥生成很耗时,线程池复用线程，避免频繁创建销毁线程的开销
    //每个连接需要一个线程,线程上下文切换开销大
    // name -> future result
    private final ConcurrentHashMap<String, CompletableFuture<KeyResult>> nameTable = new ConcurrentHashMap<>();
    // name -> list of waiting client attachments (channels)
    private final ConcurrentHashMap<String, List<ClientAttachment>> waitingClients = new ConcurrentHashMap<>();

    private final ExecutorService generatorPool;
    private Selector selector;

    public KeyServer(int port, PrivateKey issuerKey, X500Name issuerX500, int generatorThreads) {
        this.port = port;
        this.issuerKey = issuerKey;
        this.issuerX500 = issuerX500;
        this.generatorThreads = generatorThreads;
        this.generatorPool = Executors.newFixedThreadPool(generatorThreads);
    }

    // 接受新连接
    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel sc = ssc.accept();
        sc.configureBlocking(false);
        ClientAttachment attach = new ClientAttachment(sc);
        sc.register(selector, SelectionKey.OP_READ, attach);
        System.out.println("Accepted connection from " + sc.getRemoteAddress());
    }
    // 读取客户端发送的数据
    private void read(SelectionKey key) throws IOException {
        ClientAttachment att = (ClientAttachment) key.attachment();
        SocketChannel sc = att.channel;
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int r = sc.read(buf);
        if (r == -1) {
            // client closed
            closeChannel(key);
            return;
        }
        buf.flip();
        while (buf.hasRemaining()) {
            byte b = buf.get();
            if (!att.nameReceived) {
                if (b == 0) {
                    att.nameReceived = true;
                    att.name = new String(att.readBuffer.toByteArray(), StandardCharsets.US_ASCII);
                    System.out.println("Received name '" + att.name + "' from " + sc.getRemoteAddress());
                    handleNameRequest(att.name, att, key);
                } else {
                    att.readBuffer.write(b);
                    // limit name length to something reasonable
                    if (att.readBuffer.size() > 4096) {
                        System.err.println("Name too long, closing");
                        closeChannel(key);
                        return;
                    }
                }
            }
        }
    }
    // 核心逻辑：避免重复生成相同名称的密钥
    private void handleNameRequest(String name, ClientAttachment att, SelectionKey key) {
        // get or create future for this name
        CompletableFuture<KeyResult> fut = nameTable.computeIfAbsent(name, n -> {
            CompletableFuture<KeyResult> newF = new CompletableFuture<>();

            // submit generation task
            generatorPool.submit(() -> {
                System.out.println(Thread.currentThread());
                try {
                    KeyResult res = generateKeyAndCert(n);
                    newF.complete(res);
                    // upon completion, deliver to waiting clients (the selector thread will do actual write)
                    //完成后，交付给等待的客户端（选择器线程将执行实际写入）
                    deliverResultToWaitingClients(n, res);
                } catch (Throwable t) {
                    newF.completeExceptionally(t);
                    t.printStackTrace();
                }
            });
            return newF;
        });

        // add this client to waiting list
        //将此客户端添加到等待列表
        waitingClients.compute(name, (k,v)->{
            if (v==null) v = Collections.synchronizedList(new ArrayList<>());
            v.add(att);
            return v;
        });

        // if future already completed, schedule immediate delivery
        //如果未来已经完成，安排立即交货
        if (fut.isDone()) {
            try {
                KeyResult res = fut.get();
                deliverResultToWaitingClients(name, res);
            } catch (Exception e) {
                // generation errored
                System.err.println("Generation errored for " + name);
                closeChannel(key);
            }
        }
    }

    // Called by generator thread after completion to wake selector and attach buffers
    // 当密钥生成完成后，将结果分发给所有等待该名称的客户端。
    private void deliverResultToWaitingClients(String name, KeyResult res) {
        List<ClientAttachment> clients = waitingClients.remove(name);
        if (clients == null || clients.isEmpty()) {
            return;
        }
        // Prepare bytes (protocol: 4 bytes len of key, key bytes, 4 bytes len of cert, cert bytes)
        // 准备字节（协议：密钥4字节len，密钥字节，证书4字节len，证书字节）
        byte[] keyBytes = res.privateKeyPem.getBytes(StandardCharsets.US_ASCII);
        byte[] certBytes = res.certPem.getBytes(StandardCharsets.US_ASCII);
        int total = 4 + keyBytes.length + 4 + certBytes.length;
        ByteBuffer combined = ByteBuffer.allocate(total);
        combined.putInt(keyBytes.length);
        combined.put(keyBytes);
        combined.putInt(certBytes.length);
        combined.put(certBytes);
        combined.flip();

        synchronized (pendingDeliveries) {
            for (ClientAttachment ca : clients) {
                // attach a duplicate buffer for each channel (duplicate shares content but independent position)
                //为每个通道附加一个重复缓冲区（重复共享内容但独立位置）
                ByteBuffer dup = combined.asReadOnlyBuffer();
                pendingDeliveries.add(new PendingDelivery(ca, dup));
            }
        }
        selector.wakeup();
    }

    // queue used to pass pending deliveries to selector thread
    // 用于将挂起的传递传递给选择器线程的队列
    static class PendingDelivery {
        final ClientAttachment client;
        final ByteBuffer buffer;
        PendingDelivery(ClientAttachment c, ByteBuffer b){this.client=c; this.buffer=b;}
    }
    private final List<PendingDelivery> pendingDeliveries = new LinkedList<>();

    // NIO的非阻塞写入
    private void write(SelectionKey key) throws IOException {
        ClientAttachment att = (ClientAttachment) key.attachment();
        SocketChannel sc = att.channel;
        if (att.writeBuffer == null) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            return;
        }
        att.writeBuffer.mark();
        int written = sc.write(att.writeBuffer);
        if (!att.writeBuffer.hasRemaining()) {
            // write complete -> close connection gracefully
            System.out.println("Finished sending to " + sc.getRemoteAddress() + "; closing");
            closeChannel(key);
        }
    }

    private void closeChannel(SelectionKey key) {
        try {
            SocketChannel sc = (SocketChannel) key.channel();
            System.out.println("Closing connection: " + sc.getRemoteAddress());
        } catch (Exception e) {}
        try { key.cancel(); key.channel().close(); } catch (IOException e) {}
    }
    //NIO核心逻辑：单线程处理所有连接
    public void startMainLoop() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        selector = Selector.open();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.bind(new InetSocketAddress(port));
        ssc.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("KeyServer listening on port " + port + " (generatorThreads=" + generatorThreads + ")");

        while (true) {
            selector.select();
            // first, process pendingDeliveries (from generator threads)
            //首先，处理pendingDeliveries（从生成器线程）
            synchronized (pendingDeliveries) {
                Iterator<PendingDelivery> it = pendingDeliveries.iterator();
                while (it.hasNext()) {
                    PendingDelivery pd = it.next();
                    ClientAttachment ca = pd.client;
                    SelectionKey key = ca.channel.keyFor(selector);
                    if (key == null || !key.isValid()) {
                        it.remove();
                        continue;
                    }
                    ca.writeBuffer = pd.buffer;
                    int ops = key.interestOps();
                    key.interestOps(ops | SelectionKey.OP_WRITE);
                    it.remove();
                }
            }

            Iterator<SelectionKey> it2 = selector.selectedKeys().iterator();
            while (it2.hasNext()) {
                SelectionKey key = it2.next(); it2.remove();
                try {
                    if (key.isAcceptable()) accept(key);
                    else if (key.isReadable()) read(key);
                    else if (key.isWritable()) write(key);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    try { key.channel().close(); } catch (IOException ioe) {}
                }
            }
        }
    }

    // 使用BouncyCastle库生成密钥和证书
    private KeyResult generateKeyAndCert(String subjectName) throws Exception {
        System.out.println("Generating RSA 8192 key for '" + subjectName + "' (this will take time)...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(8192, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        System.out.println("Key pair generated for '" + subjectName + "'.");

        // Build X.509 certificate
        X500Name subj = new X500Name("CN=" + subjectName);
        X500Name issuer = issuerX500;
        BigInteger serial = new BigInteger(160, new SecureRandom());
        Date notBefore = Date.from(ZonedDateTime.now().toInstant());
        Date notAfter = Date.from(ZonedDateTime.now().plus(10, ChronoUnit.YEARS).toInstant());

        SubjectPublicKeyInfo subPubInfo = SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subj, subPubInfo
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(issuerKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);

        // produce PEM strings
        StringWriter swKey = new StringWriter();
        try (PemWriter pw = new PemWriter(swKey)) {
            pw.writeObject(new PemObject("PRIVATE KEY", kp.getPrivate().getEncoded()));
        }
        String privatePem = swKey.toString();

        StringWriter swCert = new StringWriter();
        try (PemWriter pw = new PemWriter(swCert)) {
            pw.writeObject(new PemObject("CERTIFICATE", cert.getEncoded()));
        }
        String certPem = swCert.toString();

        return new KeyResult(privatePem, certPem);
    }

    // helper to load issuer private key from PEM file
    //从PEM文件中加载签发者私钥，用于后续为客户端证书签名 (issuer_key.pem)
    public static PrivateKey loadPrivateKeyFromPem(File pemFile) throws Exception {
        try (FileReader fr = new FileReader(pemFile);
             PEMParser parser = new PEMParser(fr)) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter conv = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof org.bouncycastle.openssl.PEMKeyPair) {
                return conv.getKeyPair((org.bouncycastle.openssl.PEMKeyPair)obj).getPrivate();
            } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
                return conv.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo)obj);
            } else if (obj instanceof org.bouncycastle.openssl.PEMEncryptedKeyPair) {
                throw new IllegalArgumentException("Encrypted PEM not supported in this demo");
            } else {
                throw new IllegalArgumentException("Unsupported PEM object: " + obj.getClass());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java KeyServer <port> <issuer-private-pem> \"<Issuer X500 Name>\" <generatorThreads>");
            System.err.println("Example: java KeyServer 5555 issuer_key.pem \"CN=MyIssuer,O=Org,C=US\" 4");
            System.exit(1);
        }
        int port = Integer.parseInt(args[0]);
        File issuerPem = new File(args[1]);
        String issuerX500Str = args[2];
        int genThreads = Integer.parseInt(args[3]);

        Security.addProvider(new BouncyCastleProvider());
        PrivateKey issuerKey = loadPrivateKeyFromPem(issuerPem);
        X500Name issuerX500 = new X500Name(issuerX500Str);

        KeyServer server = new KeyServer(port, issuerKey, issuerX500, genThreads);
        server.startMainLoop();
    }
}



//# 生成 issuer 私钥（PKCS#8 PEM）
//openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out issuer_key.pem
//# 也可生成一个自签名证书用于发布者证书（可选）
//openssl req -new -x509 -key issuer_key.pem -out issuer_cert.pem -subj "/CN=MyIssuer"

