package com.example.wechatpusher.controller;

import com.example.wechatpusher.service.PublishedStoreService;
import com.example.wechatpusher.service.WeChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已发布文章相关接口。
 *
 * 1) GET  /api/published        —— 中继实时调微信 freepublish/batchget（需账号有权限）
 * 2) GET  /api/published/store  —— 读取中继本地的去重清单（多机共享数据源，写文前对比用）
 * 3) POST /api/published/store  —— 全量上传清单（body: {mode:"replace"|"merge", articles:[...]}, 种子同步用）
 * 4) POST /api/published/store/append —— 单条追加（body: {title, link}，推送成功后增量用）
 *
 * 统一 X-Api-Token 鉴权。
 */
@RestController
public class PublishedController {

    @Value("${pusher.api-token}")
    private String apiToken;

    private final WeChatService weChat;
    private final PublishedStoreService store;

    public PublishedController(WeChatService weChat, PublishedStoreService store) {
        this.weChat = weChat;
        this.store = store;
    }

    private boolean ok(String token) {
        return token != null && token.equals(apiToken);
    }

    @GetMapping("/api/published")
    public Map<String, Object> published(
            @RequestHeader(value = "X-Api-Token", required = false) String token) {
        if (!ok(token)) {
            return Map.of("error", "invalid token");
        }
        try {
            List<Map<String, Object>> list = weChat.listPublished();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", list.size());
            m.put("articles", list);
            return m;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/api/published/store")
    public Map<String, Object> storeGet(
            @RequestHeader(value = "X-Api-Token", required = false) String token) {
        if (!ok(token)) {
            return Map.of("error", "invalid token");
        }
        List<Map<String, Object>> all = store.all();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", all.size());
        m.put("articles", all);
        return m;
    }

    @PostMapping("/api/published/store")
    public Map<String, Object> storePost(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        if (!ok(token)) {
            return Map.of("error", "invalid token");
        }
        try {
            Object artsObj = body.get("articles");
            if (!(artsObj instanceof List)) {
                return Map.of("error", "articles must be a list");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> articles = (List<Map<String, Object>>) artsObj;
            String mode = body.get("mode") == null ? "merge" : String.valueOf(body.get("mode"));
            int total;
            if ("replace".equalsIgnoreCase(mode)) {
                total = store.replaceAll(articles);
            } else {
                total = store.merge(articles);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("mode", mode.toLowerCase());
            m.put("incoming", articles.size());
            m.put("total", total);
            return m;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/api/published/store/append")
    public Map<String, Object> storeAppend(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        if (!ok(token)) {
            return Map.of("error", "invalid token");
        }
        String title = body.get("title") == null ? "" : String.valueOf(body.get("title"));
        String link = body.get("link") == null ? "" : String.valueOf(body.get("link"));
        if (title.trim().isEmpty()) {
            return Map.of("error", "title required");
        }
        boolean added = store.append(title, link);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("added", added);
        m.put("total", store.count());
        return m;
    }
}
