# wechat-pusher

轻量**微信公众号草稿箱推送中继服务**。把你在本机生成的文章（HTML + 图片）打包成 zip，调一个 HTTP 接口，由部署在**固定公网 IP 服务器**上的 Java 服务去调微信接口，把文章写进公众号草稿箱。

## 它解决什么问题

微信公众平台要求调用方服务器 IP 加白名单。如果你的出网 IP 经常变（家庭宽带 / 办公网络 / 容器环境），每次推送前都要手动改白名单，非常麻烦。

把「调用微信」的逻辑放到一台固定 IP 的服务器上，本地只管生成文章、调接口。白名单**只加一次**，之后无论本地 IP 怎么变都不受影响。

```
本地(任意IP)  --HTTPS + X-Api-Token-->  服务器(固定IP, wechat-pusher)  --微信API-->  草稿箱
```

## 特性

- Spring Boot 单 jar，零额外依赖，JDK 17 即可运行
- 图片自动上传微信图床并替换 HTML 里的 `src`
- 原生 `HttpURLConnection` 上传，规避微信对 `chunked` / `Expect: 100-continue` 的兼容问题
- 按秒级时间戳建批次目录，定时（默认每天 03:00）清理 N 天前的本地副本
- `/api/health` 健康检查，部署后一眼确认配置是否正确

## 技术栈

- Java 17 + Spring Boot 3.2（服务端）
- Python 3.8+（本地推送脚本 `push_client.py`）

## 目录结构

```
wechat-pusher/
├── pom.xml                      # Maven 构建
├── push_client.py               # 本地推送脚本（打 zip + 调接口）
├── src/main/java/...            # Spring Boot 源码
├── src/main/resources/
│   └── application.yml          # 配置（建议用环境变量注入真实值）
├── samples/                     # 批次结构示例
│   ├── manifest.example.json
│   └── article.example.html
├── README.md
└── LICENSE
```

## 快速开始

### 1. 准备

- 微信公众号 **AppId** + **AppSecret**（公众平台后台「开发 → 基本配置」）
- 一台有**固定公网 IP** 的服务器（国内云厂商入门机型即可）
- 在公众平台「IP 白名单」加上这台服务器的出口 IP（服务器上执行 `curl -s ifconfig.me` 查询）
- 一个域名 + SSL 证书（可选但强烈建议：接口带 token，仍需 HTTPS 防中间人）

### 2. 配置服务端

两种方式二选一，生产环境推荐用**环境变量**（不落盘）：

| 配置项 | 环境变量 | 说明 |
|---|---|---|
| 微信 AppId | `WECHAT_APPID` | 公众号 AppId |
| 微信 AppSecret | `WECHAT_APPSECRET` | 公众号 AppSecret |
| 接口 token | `PUSHER_API_TOKEN` | **务必改成随机强字符串** |
| 落盘目录 | `PUSHER_STORAGE_DIR` | 批次缓存目录，运行用户需有写权限 |

> 也可直接改 `src/main/resources/application.yml` 里的占位值。⚠️ `PUSHER_API_TOKEN` 默认值 `change-me` 仅用于本地冒烟，**生产必须改**，否则任何人都能调用你的服务。

### 3. 打包

```bash
mvn clean package -DskipTests
# 产物：target/wechat-pusher-1.0.0.jar
```

### 4. 运行

```bash
java -jar wechat-pusher-1.0.0.jar
```

生产建议用 systemd 或宝塔「Java 项目」托管（JDK 17，启动参数可加 `-Dpusher.api-token=xxx`）。

### 5. 反代 + HTTPS（推荐）

用 Nginx 把 `443` 反代到 `127.0.0.1:8080`，并配置 SSL、强制 HTTPS：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

### 6. 验证

```bash
curl https://你的域名/api/health
```

应返回类似：

```json
{
  "status": "UP",
  "appIdConfigured": true,
  "secretConfigured": true,
  "tokenConfigured": true,
  "storageDir": "/www/wechat-pusher/drafts",
  "storageDirExists": true,
  "storageDirWritable": true,
  "cleanKeepDays": 3
}
```

`storageDirWritable:true` 才说明落盘目录可用，否则推送会 500。

### 7. 本地推送

```bash
PUSHER_URL=https://你的域名 PUSHER_TOKEN=你的token python push_client.py /path/to/batch
```

## 批次（zip）结构

```
batch/
├── manifest.json          # 文章元信息
├── article1.html          # 正文 HTML
├── cover1.jpg             # 封面图
└── imgs/
    └── body0.png          # 正文配图
```

### manifest.json 格式

```json
{
  "author": "可留空",
  "articles": [
    {
      "title": "文章标题",
      "digest": "摘要（建议 ≤ 50 字）",
      "html": "article1.html",
      "cover": "cover1.jpg"
    }
  ]
}
```

### HTML 图片写法

正文里的图片必须用：

```html
<img src="imgs/body0.png">
```

服务会自动上传 `imgs/` 下的图片并替换成微信图床地址。**不支持** `__WECHAT_IMG_X__` 之类的其他占位符。

完整示例见 [`samples/`](samples/)。

## 安全建议

- **务必修改 `PUSHER_API_TOKEN`**，接口无 token 等于裸奔
- 强烈建议 HTTPS，且只放行服务器自身或可信网段访问
- 微信 IP 白名单只需加「服务器出口 IP」一次，加在本地无意义

## 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| `/api/health` 返回 `storageDirWritable:false` | 落盘目录无写权限。改 `PUSHER_STORAGE_DIR` 到可写目录，或 `chown` 给运行用户 |
| 推送返回 `401 invalid token` | 客户端 `PUSHER_TOKEN` 与服务端 `PUSHER_API_TOKEN` 不一致 |
| 上传报 `Error writing request body` | 客户端用了 chunked / `Expect` 上传。请使用本仓库 `push_client.py`，已规避 |
| `article.html` 图片没上传 | HTML 里不是 `src="imgs/..."` 写法，或图片不在 `imgs/` 下 |

## License

[MIT](LICENSE)
