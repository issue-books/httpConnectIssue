## 1.issue 描述

请求服务A，偶发连接失败

## 2.分析 issue

```
OkHttp连接池中存有"空闲连接"（keepAlive=5min）
       ↓
AI平台服务端 or 中间负载均衡器 提前关闭了该TCP连接
（大多数LB/Nginx的idle timeout是30~90秒，远短于5分钟）
       ↓
下一次请求从连接池复用到该"死连接"
       ↓
写入请求数据时抛出 SocketException / IOException
       ↓
retryOnConnectionFailure=false → OkHttp不重试，直接失败
       ↓
接口返回null，调用方感知到偶发失败
```

## 3.复现 issue

服务A，缩短keepalive_timeout。服务 B 调用服务 A，并且调用连接池设置不重试且 keep_alive_duration 设置较长。

1.例如
服务A：Nginx/LB keepalive_timeout 设成 15s~30s（越短越容易复现）

```nginx
http {
    keepalive_timeout 5s;
}
```

服务B：@`src/main/java/IssuePoolHttpUtil.java`

```java
// 连接池
private static final ConnectionPool CONNECTION_POOL = new ConnectionPool(200, 5, TimeUnit.MINUTES);

// 关闭重试
// .retryOnConnectionFailure(false);

// 打印结果如下
/*
2026-04-22 09:43:58.229 [T:main] [Req:1] [start] pool(total=0,idle=0)
2026-04-22 09:43:58.266 [T:main] [Req:1] [acquired] connId=1550261631 route=Route{/127.0.0.1:80}
2026-04-22 09:43:58.280 [T:main] [Req:1] [done] code=200 costMs=54 pool(total=1,idle=0)
2026-04-22 09:43:58.281 [T:main] [Req:1] [released] connId=1550261631
2026-04-22 09:44:10.286 [T:main] [Req:2] [start] pool(total=1,idle=1)
2026-04-22 09:44:10.293 [T:main] [Req:2] [acquired] connId=1550261631 route=Route{/127.0.0.1:80}
2026-04-22 09:44:10.297 [T:main] [Req:2] [released] connId=1550261631
2026-04-22 09:44:10.297 [T:main] [Req:2] [failed] ex=IOException msg=unexpected end of stream on http://127.0.0.1/...
2026-04-22 09:44:10.299 [T:main] [Req:2] [error] ex=IOException msg=unexpected end of stream on http://127.0.0.1/... costMs=13
*/
```

现象：

1. 服务B请求1成功，请求2失败。请求1请求成功，放入连接池作为空闲连接。
2. 请求2开始复用该连接，服务A 提前关闭了该TCP连接，请求2复用该"死连接"，写入请求数据时抛出 IOException，请求失败。

## 4.解决 issue

1. 服务A设置空闲连接超时时间(keepAliveDuration)小于服务B的连接超时时间(idleTimeOut)。
2. 打开重试开关(retryOnConnectionFailure)
   参考 @`src/main/java/RepairIssuePoolHttpUtil.java`

## 5.总结

本次问题的根因不是服务完全不可用，而是客户端复用了一个已经被服务端或负载均衡关闭的空闲连接。

OkHttp 连接池默认会保留空闲连接一段时间。如果客户端保留时间比服务端 `keepalive_timeout` 更长，就可能拿到已经失效的连接，导致偶发 `IOException`。

处理思路比较简单：

1. 让客户端连接池的空闲保留时间小于服务端空闲连接超时时间。
2. 打开 OkHttp 的连接失败重试，让 OkHttp 自动丢弃坏连接并重新建连。
3. 对业务重要接口，可以在业务层做有限重试，但要确认接口是否支持重复调用。

## 6.扩展

### 6.1 TCP基本概念

TCP 是面向连接的传输协议。一次 HTTP 请求在真正发送数据前，底层通常需要先建立 TCP 连接。

TCP 连接可以简单理解为客户端和服务端之间的一条通信通道。连接建立后，双方可以在这条通道上收发数据；连接关闭后，这条通道就不能继续使用。

### 6.2 TCP三次握手

TCP 建立连接需要三次握手：

1. 客户端发送 SYN，表示想和服务端建立连接。
2. 服务端返回 SYN + ACK，表示同意建立连接。
3. 客户端再发送 ACK，连接建立完成。

连接建立完成后，HTTP 请求数据才会通过这条 TCP 连接发送出去。

### 6.3 HTTP keep-alive

HTTP keep-alive 的作用是复用 TCP 连接。

如果每次请求都重新建立 TCP 连接，会多一次三次握手，性能较差。开启 keep-alive 后，请求完成后连接不会马上关闭，而是先放入连接池，后续请求可以继续使用。

这也是本 issue 的关键点：客户端认为连接还在连接池里可以复用，但服务端或负载均衡已经把连接关闭了，下一次复用时就可能失败。

### 6.4 OkHttp连接池

OkHttp 的 `ConnectionPool` 会保存空闲连接：

```java
new ConnectionPool(200, 5, TimeUnit.MINUTES)
```

含义是最多保存 200 个空闲连接，空闲连接最多保留 5 分钟。

如果上游服务或 Nginx/LB 的 `keepalive_timeout` 比客户端连接池的保留时间短，就可能出现：

1. 客户端连接池还保留着连接。
2. 服务端已经关闭了这个 TCP 连接。
3. 客户端下次请求复用这个连接。
4. 写入数据时报 `IOException`。

### 6.5 处理建议

简单处理方式：

1. 客户端连接池的空闲保留时间设置得比服务端 `keepalive_timeout` 更短。
2. 开启 OkHttp 的 `retryOnConnectionFailure(true)`，让 OkHttp 在连接失效时自动换新连接重试。
3. 对重要请求，业务侧也可以做有限次数的重试，但要注意接口是否幂等，避免重复提交。
