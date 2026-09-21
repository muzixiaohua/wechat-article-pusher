package com.example.wechatpusher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WechatPusherApplication {
    public static void main(String[] args) {
        // 强制 IPv4，避免解析到 IPv6 被微信接口拒绝
        System.setProperty("java.net.preferIPv4Stack", "true");
        SpringApplication.run(WechatPusherApplication.class, args);
    }
}
