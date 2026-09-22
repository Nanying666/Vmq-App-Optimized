package com.shinian.pay.util;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 统一网络客户端
 *
 * 解决问题：移动数据网络下访问境外服务器时，TCP 连接/TLS 握手被中间设备
 * 间歇性重置（Connection reset），导致心跳检测失败。
 *
 * 核心策略（三重保障）：
 * 1. followSslRedirects(true) —— 服务器对 http 返回 308 重定向到 https 时自动跟随；
 * 2. 失败自动重试，并在 https / http 协议间交替切换，单协议被重置时换协议再试；
 * 3. 使用阿里 DoH（HTTPS 加密 DNS）解析，避免明文 UDP DNS 被污染或劫持。
 *
 * 按实测约 30% 的单次成功率计算，重试 6 次全失败的概率不足 0.1%。
 */
public final class NetworkClient {

    private static final String TAG = "NetworkClient";

    /** 最大尝试次数（含首次） */
    private static final int MAX_RETRY = 6;

    /** 重试基础延迟（线性退避） */
    private static final long RETRY_BASE_DELAY_MS = 300L;

    private static volatile OkHttpClient sClient;

    private NetworkClient() {
    }

    /** 获取全局唯一的 OkHttpClient（带重定向/重试/加密DNS能力） */
    public static OkHttpClient client() {
        if (sClient == null) {
            synchronized (NetworkClient.class) {
                if (sClient == null) {
                    sClient = new OkHttpClient.Builder()
                            .connectTimeout(8, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .callTimeout(25, TimeUnit.SECONDS)
                            // 关键①：连接失败(被重置等)时 OkHttp 内部自动换路重试
                            .retryOnConnectionFailure(true)
                            // 关键②：http->https 308 重定向自动跟随（followRedirects 已默认 true，
                            // 但跨协议重定向必须显式打开 followSslRedirects）
                            .followRedirects(true)
                            .followSslRedirects(true)
                            // 关键③：加密 DNS，防明文 DNS 污染
                            .dns(new AliDns())
                            .build();
                }
            }
        }
        return sClient;
    }

    /**
     * 异步 GET 请求（带协议交替重试）。
     * 结果始终回调在 OkHttp 工作线程，UI 操作请自行 post 到主线程。
     *
     * @param host 配置的地址（如 your.domain.com，不带协议）
     * @param path 接口路径（如 /appHeart?t=xxx&sign=xxx，含查询参数）
     * @param cb   OkHttp 原生回调；若全部重试仍失败，只会回调一次 onFailure
     */
    public static void getWithRetry(String host, String path, Callback cb) {
        // IP 直连模式：http 优先（https+IP 无匹配证书必然失败，不浪费尝试）
        // 域名模式：https 优先（加密+跟随308重定向）
        attempt(host, path, 0, !isIpHost(host), cb);
    }

    private static void attempt(final String host, final String path,
                                final int attemptIndex, final boolean httpsFirst,
                                final Callback cb) {
        if (attemptIndex >= MAX_RETRY) {
            cb.onFailure(null, new IOException("网络连续 " + MAX_RETRY + " 次请求失败(已尝试http/https交替重试)"));
            return;
        }

        // 偶数次用 https，奇数次用 http
        boolean useHttps = httpsFirst ? (attemptIndex % 2 == 0) : (attemptIndex % 2 == 1);
        HttpUrl url = buildUrl(host, path, useHttps);

        Request request = new Request.Builder()
                .url(url)
                .header("Connection", "close") // 每次新建连接，避免复用已被中间设备干扰的半死连接
                .build();

        client().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "第" + (attemptIndex + 1) + "次尝试失败: " + call.request().url()
                        + " 错误: " + e.getMessage());
                // 延迟后换协议重试
                new Thread(() -> {
                    try {
                        Thread.sleep(RETRY_BASE_DELAY_MS * (attemptIndex + 1));
                    } catch (InterruptedException ignored) {
                    }
                    attempt(host, path, attemptIndex + 1, httpsFirst, cb);
                }).start();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "第" + (attemptIndex + 1) + "次尝试成功: " + call.request().url());
                cb.onResponse(call, response);
            }
        });
    }

    /** 按协议构建请求 URL（host 支持 "域名"、"IP"、"host:端口"、"[IPv6]:端口" 格式；path 形如 /appHeart?t=xx&sign=xx） */
    private static HttpUrl buildUrl(String host, String path, boolean useHttps) {
        String h = host.trim();
        int port = -1;

        // 解析 [IPv6]:端口
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close > 0) {
                String rest = h.substring(close + 1);
                if (rest.startsWith(":")) {
                    try {
                        port = Integer.parseInt(rest.substring(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                h = h.substring(1, close);
            }
        } else {
            // 解析 host:端口（IPv4/域名）
            int colon = h.lastIndexOf(':');
            if (colon > 0) {
                try {
                    port = Integer.parseInt(h.substring(colon + 1));
                    h = h.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // 冒号后不是端口号（如IPv6裸地址），保持原样
                }
            }
        }

        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : null;

        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme(useHttps ? "https" : "http")
                .host(h);
        if (port > 0) {
            builder.port(port);
        } else {
            builder.port(useHttps ? 443 : 80);
        }
        return builder.encodedPath(p).query(q).build();
    }

    /** 判断 host 是否为 IP 直连（支持 "IP" 和 "IP:端口" 两种格式） */
    private static boolean isIpHost(String host) {
        if (host == null) return false;
        String h = host.trim();
        int colon = h.lastIndexOf(':');
        if (colon > 0 && !h.startsWith("[")) {
            h = h.substring(0, colon);
        }
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * 同步 GET（阻塞当前线程，禁止在主线程调用！），带协议交替重试。
     * 供子线程（如自动补回调）使用。返回响应体字符串，全部重试失败则抛 IOException。
     */
    public static String getWithRetrySync(String host, String path) throws IOException {
        IOException last = null;
        // IP 直连模式全部走 http（https+IP 无匹配证书必然失败）
        boolean isIp = isIpHost(host);
        for (int i = 0; i < MAX_RETRY; i++) {
            boolean useHttps = isIp ? false : (i % 2 == 0); // 域名: https->http交替; IP: 全http
            HttpUrl url = buildUrl(host, path, useHttps);
            Request request = new Request.Builder().url(url).header("Connection", "close").build();
            Response resp = null;
            try {
                resp = client().newCall(request).execute();
                if (resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    Log.d(TAG, "同步请求第" + (i + 1) + "次成功: " + url);
                    return body;
                }
                last = new IOException("HTTP " + resp.code());
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "同步请求第" + (i + 1) + "次失败: " + url + " 错误: " + e.getMessage());
            } finally {
                if (resp != null) resp.close();
            }
            try {
                Thread.sleep(RETRY_BASE_DELAY_MS * (i + 1));
            } catch (InterruptedException ignored) {
            }
        }
        throw last != null ? last : new IOException("网络连续请求失败");
    }

    /**
     * 阿里公共 DNS 的 DoH（DNS over HTTPS）解析器。
     * 明文 UDP DNS 在移动网络下易被污染，改用 HTTPS 查询。
     */
    static final class AliDns implements Dns {
        private static final String DOH_URL = "https://dns.alidns.com/resolve?name=%s&type=A";

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            // 内网地址/纯IP 直接走系统解析
            if (hostname == null || hostname.isEmpty()) {
                throw new UnknownHostException("hostname is empty");
            }
            if (isIpAddress(hostname)) {
                List<InetAddress> list = Dns.SYSTEM.lookup(hostname);
                if (list.isEmpty()) throw new UnknownHostException(hostname);
                return list;
            }
            try {
                String u = String.format(DOH_URL, URLEncoder.encode(hostname, "UTF-8"));
                // 复用单例客户端（DoH 每次心跳都会调用，旧实现每次新建 OkHttpClient
                // 会导致连接池与线程池持续泄漏）
                OkHttpClient bare = getBareClient();
                Request req = new Request.Builder().url(u).header("accept", "application/dns-json").build();
                Response resp = bare.newCall(req).execute();
                String body = resp.body() != null ? resp.body().string() : "";
                resp.close();

                JSONObject json = new JSONObject(body);
                // DoH JSON API 中 Status 为数字，0 = NOERROR(成功)，非 0 为各类 DNS 错误码
                if (json.optInt("Status", -1) != 0) {
                    throw new UnknownHostException("DoH Status != 0: " + hostname);
                }
                JSONArray answers = json.optJSONArray("Answer");
                List<InetAddress> result = new ArrayList<>();
                if (answers != null) {
                    for (int i = 0; i < answers.length(); i++) {
                        JSONObject ans = answers.optJSONObject(i);
                        if (ans == null) continue;
                        if (ans.optInt("type", -1) == 1) { // A 记录
                            String data = ans.optString("data", "").trim();
                            if (!data.isEmpty()) {
                                result.add(InetAddress.getByName(data));
                            }
                        }
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
                throw new UnknownHostException("DoH 无 A 记录: " + hostname);
            } catch (UnknownHostException e) {
                throw e;
            } catch (Exception e) {
                // DoH 失败时降级回系统 DNS，保证可用性
                Log.w(TAG, "DoH 解析失败，降级系统DNS: " + hostname + " " + e.getMessage());
                List<InetAddress> list = Dns.SYSTEM.lookup(hostname);
                if (list.isEmpty()) throw new UnknownHostException(hostname);
                return list;
            }
        }

        private static volatile OkHttpClient sBareClient;

        /** DoH 专用轻量客户端（系统DNS，避免递归走DoH），全局单例 */
        private static OkHttpClient getBareClient() {
            if (sBareClient == null) {
                synchronized (AliDns.class) {
                    if (sBareClient == null) {
                        sBareClient = new OkHttpClient.Builder()
                                .connectTimeout(4, TimeUnit.SECONDS)
                                .readTimeout(4, TimeUnit.SECONDS)
                                .build();
                    }
                }
            }
            return sBareClient;
        }

        private static boolean isIpAddress(String s) {
            return s.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                    || s.contains(":"); // 粗略匹配 IPv6
        }
    }
}
