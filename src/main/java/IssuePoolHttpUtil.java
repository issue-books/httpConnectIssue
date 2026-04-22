import okhttp3.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class IssuePoolHttpUtil {

    private static final AtomicLong REQ_ID = new AtomicLong(1);
    private static final Map<String, AtomicLong> EX_COUNTER = new ConcurrentHashMap<>();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static String now() {
        return LocalDateTime.now().format(FMT);
    }
    private static void incEx(Throwable t) {
        String key = (t == null) ? "Unknown" : t.getClass().getSimpleName();
        EX_COUNTER.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    // 类名
    private static final String className = IssuePoolHttpUtil.class.getName();

    // 连接池
    private static final ConnectionPool CONNECTION_POOL = new ConnectionPool(200, 5,
            TimeUnit.MINUTES);

    // OkHttpClient 实例
    private static final OkHttpClient CLIENT;

    // 超时时间设置较短: 实现快速失败
    static {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        try {
            // 1.跳过SSL鉴权
            X509TrustManager trustAllCert = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // 默认信任所有证书
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // 默认信任所有证书
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[]{};
                }
            };

            // 2.创建 SSLSocketFactory
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllCert}, new SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            builder.sslSocketFactory(sslSocketFactory, trustAllCert)
                    .hostnameVerifier((hostname, session) -> true);

            // 通过 OkHttp EventListener + 连接池快照日志 来看到“坏连接失败后被淘汰”的过程
            builder.eventListenerFactory(call -> new EventListener() {
                @Override
                public void connectionAcquired(Call call, Connection connection) {
                    String reqId = call.request().header("X-Req-Id");
                    System.out.printf("%s [T:%s] [Req:%s] [acquired] connId=%s route=%s%n",
                            now(), Thread.currentThread().getName(), reqId,
                            System.identityHashCode(connection), connection.route());
                }
                @Override
                public void connectionReleased(Call call, Connection connection) {
                    String reqId = call.request().header("X-Req-Id");
                    System.out.printf("%s [T:%s] [Req:%s] [released] connId=%s%n",
                            now(), Thread.currentThread().getName(), reqId,
                            System.identityHashCode(connection));
                }
                @Override
                public void callFailed(Call call, IOException ioe) {
                    String reqId = call.request().header("X-Req-Id");
                    incEx(ioe);
                    System.out.printf("%s [T:%s] [Req:%s] [failed] ex=%s msg=%s%n",
                            now(), Thread.currentThread().getName(), reqId,
                            ioe.getClass().getSimpleName(), ioe.getMessage());
                }
            });
        } catch (Exception e) {
            System.out.println(className + "初始化SSL失败:" + e.getMessage());
            e.printStackTrace();
        }

        // 2.设置最大并发请求
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(200);
        dispatcher.setMaxRequestsPerHost(200);

        // 1.创建OkHttpClient实例
        CLIENT = builder
                .dispatcher(dispatcher)
                // 3.关闭重试
                .retryOnConnectionFailure(false)
                // 4.超时时间
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                // 5.设置连接池
                .connectionPool(CONNECTION_POOL)
                .build();
        System.out.println(className + "初始化完成");
    }

    public static void main(String[] args) throws InterruptedException {
        String shortKeepUrl = "http://127.0.0.1";

        // 示例：请求 -> sleep -> 请求，方便复现死连接
        for (int i = 0; i < 20; i++) {
            long id = REQ_ID.getAndIncrement();
            Request req = new Request.Builder()
                    .url(shortKeepUrl)
                    .addHeader("X-Req-Id", String.valueOf(id)) // 请求链路ID
                    .get()
                    .build();
            long start = System.currentTimeMillis();
            System.out.printf("%s [T:%s] [Req:%s] [start] pool(total=%d,idle=%d)%n",
                    now(), Thread.currentThread().getName(), id,
                    CONNECTION_POOL.connectionCount(), CONNECTION_POOL.idleConnectionCount());
            try (Response resp = CLIENT.newCall(req).execute()) {
                System.out.printf("%s [T:%s] [Req:%s] [done] code=%d costMs=%d pool(total=%d,idle=%d)%n",
                        now(), Thread.currentThread().getName(), id,
                        resp.code(), (System.currentTimeMillis() - start),
                        CONNECTION_POOL.connectionCount(), CONNECTION_POOL.idleConnectionCount());
            } catch (Exception e) {
                incEx(e);
                System.out.printf("%s [T:%s] [Req:%s] [error] ex=%s msg=%s costMs=%d%n",
                        now(), Thread.currentThread().getName(), id,
                        e.getClass().getSimpleName(), e.getMessage(),
                        (System.currentTimeMillis() - start));
            }
            // 这里调成 > 上游 keepalive_timeout，便于复现
            Thread.sleep(12000);
        }

        System.out.println("==== Exception Summary ====");
        EX_COUNTER.forEach((k, v) -> System.out.println(k + " = " + v.get()));
    }
}
