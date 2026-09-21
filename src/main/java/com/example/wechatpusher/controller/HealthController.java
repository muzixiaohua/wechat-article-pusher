package com.example.wechatpusher.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    @Value("${wechat.app-id:}")
    private String appId;

    @Value("${wechat.app-secret:}")
    private String appSecret;

    @Value("${pusher.api-token:}")
    private String apiToken;

    @Value("${pusher.storage-dir:/www/wechat-pusher/drafts}")
    private String storageDir;

    @Value("${pusher.clean-keep-days:3}")
    private int cleanKeepDays;

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        File d = new File(storageDir);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("appIdConfigured", !appId.isEmpty());
        m.put("secretConfigured", !appSecret.isEmpty());
        m.put("tokenConfigured", !apiToken.isEmpty());
        m.put("storageDir", storageDir);
        m.put("storageDirExists", d.exists());
        m.put("storageDirWritable", d.exists() && d.canWrite());
        m.put("cleanKeepDays", cleanKeepDays);
        return m;
    }

    @GetMapping("/")
    public String root() {
        return "WeChat Pusher is running...";
    }
}
