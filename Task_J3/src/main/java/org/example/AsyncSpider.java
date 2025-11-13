package org.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异步网络爬虫，用于遍历HTTP服务器的资源图
 */
public class AsyncSpider {
    // 正则表达式模式，用于解析JSON响应
    private static final Pattern MESSAGE_P = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
    private static final Pattern SUCCESSORS_P = Pattern.compile("\"successors\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern STRING_IN_ARRAY_P = Pattern.compile("\"(.*?)\"");

    // 请求超时时间和最大重试次数
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(13);
    private static final int MAX_RETRIES = 2;

    /**
     * 节点数据类，表示从服务器获取的资源节点
     *
     * @param message 消息内容
     * @param successors 后续路径列表
     */
    record Node(String message, List<String> successors) {}

    /**
     * HTTP客户端，配置了虚拟线程执行器和连接超时
     */
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    // 线程安全的集合，用于存储已访问的路径和收集的消息
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final Queue<String> allMessages = new ConcurrentLinkedQueue<>();

    /**
     * 程序入口点
     *
     * @param args 命令行参数：
     *             {@code [0]} 基础URL（默认 {@code http://localhost:8080}）
     *             {@code [1]} 起始路径（默认 {@code /}）
     * @throws Exception 如果遍历过程中发生意外错误
     */
    public static void main(String[] args) throws Exception {

        String base;
        if (args.length > 0)
            base = args[0];
        else
            base = "http://localhost:8080";

        String startPath;
        if (args.length > 1)
            startPath = args[1];
        else
            startPath = "/";

        AsyncSpider spider = new AsyncSpider();
        List<String> result = spider.crawl(URI.create(base), startPath, Duration.ofSeconds(180));

        // 输出排序后的消息列表
        result.forEach(System.out::println);

    }

    /**
     * 执行并发的资源图遍历
     *
     * @param base 服务器基础URI
     * @param startPath 起始访问路径
     * @param globalTimeout 整个遍历过程的最大允许时间
     * @return 按字典序排序的收集到的消息列表
     * @throws InterruptedException 如果等待线程在等待完成时被中断
     * @throws TimeoutException 如果遍历超过全局超时时间
     */
    public List<String> crawl(URI base, String startPath, Duration globalTimeout)
            throws InterruptedException, TimeoutException {

        // 使用虚拟线程执行器
        try (ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor()) {
            // 跟踪正在进行的请求数量
            AtomicInteger inFlight = new AtomicInteger(0);
            // 完成信号
            CountDownLatch done = new CountDownLatch(1);

            // 超时时间点
            long deadlineNanos = System.nanoTime() + globalTimeout.toNanos();

            // 提交任务的Runnable实现
            Runnable submit = new Runnable() {
                /**
                 * 递归地提交路径处理任务
                 * @param path 要处理的路径
                 */
                void fork(String path) {
                    // 如果路径已被访问过，则跳过
                    if (!visited.add(path))
                        return;

                    // 增加进行中任务计数
                    inFlight.incrementAndGet();

                    // 提交任务到执行器
                    vexec.submit(() -> {
                        try {
                            // 获取节点数据
                            Node node = fetchNode(base.resolve(path));
                            if (node != null) {
                                // 如果有消息内容，添加到消息队列
                                if (node.message() != null && !node.message().isBlank())
                                    allMessages.add(node.message());

                                // 递归处理所有后续路径
                                for (String next : node.successors()) {
                                    fork(next);
                                }
                            }
                        } finally {
                            // 减少进行中任务计数，如果所有任务都完成，则释放门闩
                            if (inFlight.decrementAndGet() == 0)
                                done.countDown();
                        }
                    });
                }

                @Override
                public void run() {
                    // 从起始路径开始遍历
                    fork(startPath);
                }
            };

            // 开始遍历
            submit.run();

            // 等待所有任务完成或超时
            long remaining;
            while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
                if (done.await(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 250), TimeUnit.MILLISECONDS))
                    break;
            }

            // 检查是否超时
            if (System.nanoTime() >= deadlineNanos)
                throw new TimeoutException("全局遍历超时");
        }

        // 收集并排序所有消息
        var list = new ArrayList<>(allMessages);
        list.sort(Comparator.naturalOrder());

        return list;
    }

    /**
     * 从指定URI获取并解析节点数据，支持重试机制
     *
     * @param uri 要获取资源的绝对URI
     * @return 解析后的Node对象，如果获取失败则返回null
     */
    private Node fetchNode(URI uri) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                // 创建HTTP请求
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(REQ_TIMEOUT)
                        .GET()
                        .build();

                // 发送请求并获取响应
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                // 只处理200 状态码的响应
                if (resp.statusCode() != 200)
                    return null;

                // 解析响应体
                return parseNode(resp.body());
            } catch (IOException e) {
                // 达到最大重试次数后返回null
                if (attempt == MAX_RETRIES)
                    return null;
            } catch (InterruptedException e) {
                // 恢复中断状态并返回
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 使用正则表达式从JSON字符串中解析节点信息
     *
     * @param json 从服务器获取的原始JSON字符串
     * @return Node实例
     */
    private static Node parseNode(String json) {
        String message = null;

        // 提取message字段
        Matcher m = MESSAGE_P.matcher(json);
        if (m.find())
            message = unescape(m.group(1));

        List<String> successors = new ArrayList<>();

        // 提取successors数组
        Matcher s = SUCCESSORS_P.matcher(json);
        if (s.find()) {
            String arr = s.group(1);

            // 提取数组中的所有字符串
            Matcher each = STRING_IN_ARRAY_P.matcher(arr);
            while (each.find())
                successors.add(unescape(each.group(1)));
        }

        return new Node(message, successors);
    }

    /**
     * 简单的JSON字符串转义处理
     *
     * @param s JSON转义后的字符串内容（不包含引号）
     * @return 转义后的Java字符串
     */
    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}


// java -jar Task_J3-server.jar ZhangSan