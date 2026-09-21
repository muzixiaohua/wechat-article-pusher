#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地推送脚本：把批次目录打包成 zip，调 wechat-pusher 服务的 /api/push/draft 接口，
由固定 IP 的服务器去完成微信草稿箱推送，绕开本地 IP 白名单限制。

用法:
  python push_client.py /path/to/batch
  PUSHER_URL=https://your-server.example.com PUSHER_TOKEN=xxxx python push_client.py /path/to/batch

批次目录结构（示例见 samples/）:
  batch/
    manifest.json          # { author, articles:[ {title,digest,html,cover} ] }
    article1.html
    cover1.jpg
    imgs/body0.png         # 正文图，HTML 里用 <img src="imgs/body0.png">
"""
import sys
import os
import io
import json
import zipfile
import uuid
import ssl
import http.client
from urllib.parse import urlparse

# 默认服务地址与 token（请用环境变量覆盖；token 必须与服务端一致）
DEFAULT_URL = os.environ.get("PUSHER_URL", "https://your-server.example.com")
DEFAULT_TOKEN = os.environ.get("PUSHER_TOKEN", "change-me")
DEFAULT_AUTHOR = os.environ.get("PUSHER_AUTHOR", "")


def load_manifest(batch_dir):
    """读取批次目录下的 manifest.json"""
    with open(os.path.join(batch_dir, "manifest.json"), encoding="utf-8") as f:
        return json.load(f)


def build_zip(batch_dir, manifest):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for a in manifest.get("articles", []):
            for rel in (a["html"], a["cover"]):
                fp = os.path.join(batch_dir, rel)
                if os.path.exists(fp):
                    zf.write(fp, rel)
        imgs_dir = os.path.join(batch_dir, "imgs")
        if os.path.isdir(imgs_dir):
            for f in sorted(os.listdir(imgs_dir)):
                zf.write(os.path.join(imgs_dir, f), os.path.join("imgs", f))
        # 确保 author 落盘
        manifest.setdefault("author", DEFAULT_AUTHOR)
        zf.writestr("manifest.json",
                    json.dumps(manifest, ensure_ascii=False, indent=2))
    return buf.getvalue()


def push(server_url, token, zip_bytes):
    p = urlparse(server_url)
    host = p.hostname
    scheme = (p.scheme or "https").lower()
    if scheme == "https":
        port = p.port or 443
    else:
        port = p.port or 80
    boundary = uuid.uuid4().hex
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="batch.zip"\r\n'
        f"Content-Type: application/zip\r\n\r\n"
    ).encode() + zip_bytes + f"\r\n--{boundary}--\r\n".encode()

    # 内网/自签证书时跳过校验（token 已防未授权）
    if scheme == "https":
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        conn = http.client.HTTPSConnection(host, port, context=ctx, timeout=180)
    else:
        conn = http.client.HTTPConnection(host, port, timeout=180)

    conn.request(
        "POST", "/api/push/draft", body=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-Api-Token": token,
        },
    )
    resp = conn.getresponse()
    return resp.status, resp.read().decode("utf-8", "replace")


def main():
    if len(sys.argv) < 2:
        print("用法: python push_client.py <批次目录>")
        sys.exit(1)
    batch_dir = sys.argv[1]
    if not os.path.isdir(batch_dir):
        print("批次目录不存在:", batch_dir)
        sys.exit(1)

    try:
        manifest = load_manifest(batch_dir)
    except Exception as e:
        print("读取 manifest.json 失败:", e)
        sys.exit(1)

    zip_bytes = build_zip(batch_dir, manifest)

    server_url = os.environ.get("PUSHER_URL", DEFAULT_URL)
    token = os.environ.get("PUSHER_TOKEN", DEFAULT_TOKEN)

    print(f"打包完成: {len(zip_bytes)} bytes, 文章数={len(manifest.get('articles', []))}")
    print(f"推送至: {server_url}")
    status, text = push(server_url, token, zip_bytes)
    print(f"HTTP {status}")
    print(text)
    if status == 200:
        print("OK 推送成功，去公众号后台草稿箱核对发布。")
    else:
        print("推送失败，检查 PUSHER_URL / PUSHER_TOKEN 或服务器日志。")


if __name__ == "__main__":
    main()
