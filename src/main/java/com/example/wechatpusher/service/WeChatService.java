package com.example.wechatpusher.service;

import com.example.wechatpusher.model.Manifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 微信交互层，全部用原生 HttpURLConnection 实现：
 *  - 手写 multipart，不发送 Expect: 100-continue（部分 HTTP 客户端默认会发，微信不支持会直接重置连接）
 *  - 带 User-Agent: Mozilla/5.0
 *  - 封面上传 material/add_material?type=thumb（图文草稿 thumb_media_id 要求）
 *  - 正文图上传 media/uploadimg
 *  - 固定 Content-Length（setFixedLengthStreamingMode），不用 chunked
 */
@Service
public class WeChatService {

    @Value("${wechat.app-id}")
    private String appId;

    @Value("${wechat.app-secret}")
    private String appSecret;

    private final ObjectMapper om = new ObjectMapper();
    private String cachedToken;
    private long tokenExpireAt;

    private String getAccessToken() throws IOException {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAt) {
            return cachedToken;
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                + appId + "&secret=" + appSecret;
        String resp = httpGet(url, 30000);
        Map<?, ?> m = om.readValue(resp, Map.class);
        if (m.get("access_token") == null) {
            throw new IOException("get access_token failed: " + resp);
        }
        cachedToken = String.valueOf(m.get("access_token"));
        Object exp = m.get("expires_in");
        long secs = exp instanceof Number ? ((Number) exp).longValue() : 7200;
        tokenExpireAt = now + (secs - 300) * 1000;
        return cachedToken;
    }

    private String httpGet(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = c.getResponseCode();
        try (InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream()) {
            String r = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (code != 200) {
                throw new IOException("GET " + code + " " + r);
            }
            return r;
        }
    }

    /**
     * 手写 multipart/form-data 上传。
     */
    private String uploadMedia(String url, byte[] data, String filename) throws IOException {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String ctype = filename.toLowerCase().endsWith(".gif") ? "image/gif" : "image/jpeg";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"media\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + ctype + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(data);
        bos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] body = bos.toByteArray();

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(60000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        // 固定长度，禁止 chunked / Expect: 100-continue
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        int code = c.getResponseCode();
        try (InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream()) {
            String r = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (code != 200) {
                throw new IOException("upload " + code + " " + r);
            }
            return r;
        }
    }

    /** 封面上传：material/add_material?type=thumb，返回 media_id */
    public String uploadCover(byte[] data, String name) throws IOException {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/material/add_material?access_token=" + token + "&type=thumb";
        String resp = uploadMedia(url, data, name);
        Map<?, ?> m = om.readValue(resp, Map.class);
        if (m.get("media_id") == null) {
            throw new IOException("upload cover failed: " + resp);
        }
        return String.valueOf(m.get("media_id"));
    }

    /** 正文图上传：media/uploadimg，返回 mmbiz url */
    public String uploadImg(byte[] data, String name) throws IOException {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/media/uploadimg?access_token=" + token;
        String resp = uploadMedia(url, data, name);
        Map<?, ?> m = om.readValue(resp, Map.class);
        if (m.get("url") == null) {
            throw new IOException("upload img failed: " + resp);
        }
        return String.valueOf(m.get("url"));
    }

    /** 创建草稿：draft/add，返回 media_id */
    public String addDraft(List<Map<String, Object>> articles) throws IOException {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/draft/add?access_token=" + token;
        byte[] body = om.writeValueAsBytes(Map.of("articles", articles));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(60000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        int code = c.getResponseCode();
        try (InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream()) {
            String r = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (code != 200) {
                throw new IOException("draft/add " + code + " " + r);
            }
            Map<?, ?> m = om.readValue(r, Map.class);
            if (m.get("media_id") == null) {
                throw new IOException("draft/add failed: " + r);
            }
            return String.valueOf(m.get("media_id"));
        }
    }
}
