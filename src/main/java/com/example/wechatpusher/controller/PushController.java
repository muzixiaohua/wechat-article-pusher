package com.example.wechatpusher.controller;

import com.example.wechatpusher.model.ArticleMeta;
import com.example.wechatpusher.model.Manifest;
import com.example.wechatpusher.service.WeChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
public class PushController {

    private static final ObjectMapper om = new ObjectMapper();
    private static final Pattern IMG_REF = Pattern.compile("src=\"(imgs/[^\"]+)\"");

    @Value("${pusher.api-token}")
    private String apiToken;

    @Value("${pusher.storage-dir}")
    private String storageDir;

    private final WeChatService weChat;

    public PushController(WeChatService weChat) {
        this.weChat = weChat;
    }

    @PostMapping("/api/push/draft")
    public Map<String, Object> push(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestParam("file") MultipartFile file) throws Exception {

        if (token == null || !token.equals(apiToken)) {
            return Map.of("error", "invalid token");
        }

        String batch = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
        File batchDir = new File(storageDir, batch);
        batchDir.mkdirs();

        // 1. 解压 zip 到批次目录
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(batchDir, e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(zis.readAllBytes());
                }
            }
        }

        File manifestFile = new File(batchDir, "manifest.json");
        if (!manifestFile.exists()) {
            throw new IOException("manifest.json missing");
        }
        Manifest manifest = om.readValue(manifestFile, Manifest.class);

        List<Map<String, Object>> articles = new ArrayList<>();
        for (ArticleMeta art : manifest.articles) {
            File htmlFile = new File(batchDir, art.html);
            File coverFile = new File(batchDir, art.cover);
            if (!htmlFile.exists()) {
                throw new IOException("html missing: " + art.html);
            }
            if (!coverFile.exists()) {
                throw new IOException("cover missing: " + art.cover);
            }

            String content = new String(Files.readAllBytes(htmlFile.toPath()), StandardCharsets.UTF_8);
            String thumb = weChat.uploadCover(Files.readAllBytes(coverFile.toPath()), art.cover);

            // 2. 上传正文图并替换 src 为微信图床地址
            Set<String> seen = new LinkedHashSet<>();
            Matcher mt = IMG_REF.matcher(content);
            while (mt.find()) {
                seen.add(mt.group(1));
            }
            for (String ref : seen) {
                File img = new File(batchDir, ref);
                if (!img.exists()) {
                    continue;
                }
                String url = weChat.uploadImg(Files.readAllBytes(img.toPath()), img.getName());
                content = content.replace("src=\"" + ref + "\"", "src=\"" + url + "\"");
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("title", art.title);
            a.put("author", manifest.author);
            a.put("digest", art.digest);
            a.put("content", content);
            a.put("thumb_media_id", thumb);
            a.put("need_open_comment", 0);
            a.put("only_fans_can_comment", 0);
            articles.add(a);
        }

        String mediaId = weChat.addDraft(articles);
        Files.writeString(new File(batchDir, "result.media_id").toPath(), mediaId);
        return Map.of("media_id", mediaId);
    }
}
