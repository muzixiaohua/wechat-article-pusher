package com.example.wechatpusher.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class CleanupTask {

    @Value("${pusher.storage-dir:/www/wechat-pusher/drafts}")
    private String storageDir;

    @Value("${pusher.clean-keep-days:3}")
    private int keepDays;

    /** 每天 03:00 清理 keepDays 天前写入的批次目录（仅清本地副本，不动微信草稿箱） */
    @Scheduled(cron = "0 0 3 * * *")
    public void clean() {
        File d = new File(storageDir);
        if (!d.isDirectory()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - (long) keepDays * 24 * 3600 * 1000;
        File[] subs = d.listFiles();
        if (subs == null) {
            return;
        }
        for (File sub : subs) {
            if (sub.isDirectory() && sub.lastModified() < cutoff) {
                deleteRecursively(sub);
            }
        }
    }

    private void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
