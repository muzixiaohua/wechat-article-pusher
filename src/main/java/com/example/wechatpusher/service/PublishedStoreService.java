package com.example.wechatpusher.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已发布文章去重清单的本地持久化存储。
 * 独立于 drafts（drafts 会被定时清理），路径由 pusher.published-file 配置。
 * 记录结构统一为 {title, create_time, link}，与客户端 published_titles.json 一致。
 * 保存采用「临时文件 + 原子移动」，防止写一半损坏。
 */
@Service
public class PublishedStoreService {

    @Value("${pusher.published-file:/data/wechat-pusher/published_titles.json}")
    private String filePath;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> cache = new ArrayList<>();

    @PostConstruct
    public void load() {
        Path p = Paths.get(filePath);
        if (!Files.exists(p)) {
            return;
        }
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            List<Map<String, Object>> list = mapper.readValue(
                    s, new TypeReference<List<Map<String, Object>>>() {});
            synchronized (cache) {
                cache.clear();
                cache.addAll(list);
            }
        } catch (Exception e) {
            // 文件损坏时不让服务起不来，保留空清单并打日志
            System.err.println("[PublishedStore] load failed: " + e.getMessage());
        }
    }

    /** 合并：按 title 去重后追加，返回合并后总数。 */
    public synchronized int merge(List<Map<String, Object>> incoming) {
        int added = 0;
        for (Map<String, Object> a : incoming) {
            String title = a.get("title") == null ? "" : String.valueOf(a.get("title")).trim();
            if (title.isEmpty() || containsTitle(title)) {
                continue;
            }
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("title", title);
            rec.put("create_time", a.get("create_time") == null ? 0 : a.get("create_time"));
            rec.put("link", a.get("link") == null ? "" : String.valueOf(a.get("link")));
            cache.add(rec);
            added++;
        }
        if (added > 0) {
            save();
        }
        return cache.size();
    }

    /** 全量替换（种子上传用），返回总数。 */
    public synchronized int replaceAll(List<Map<String, Object>> incoming) {
        cache.clear();
        return merge(incoming);
    }

    /** 单条追加，返回是否真正新增。 */
    public synchronized boolean append(String title, String link) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || containsTitle(t)) {
            return false;
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("title", t);
        rec.put("create_time", System.currentTimeMillis() / 1000);
        rec.put("link", link == null ? "" : link.trim());
        cache.add(rec);
        save();
        return true;
    }

    public synchronized List<Map<String, Object>> all() {
        return new ArrayList<>(cache);
    }

    public synchronized int count() {
        return cache.size();
    }

    private boolean containsTitle(String title) {
        for (Map<String, Object> a : cache) {
            if (title.equals(String.valueOf(a.get("title")))) {
                return true;
            }
        }
        return false;
    }

    private void save() {
        Path p = Paths.get(filePath);
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cache), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("save published store failed: " + e.getMessage(), e);
        }
    }
}
