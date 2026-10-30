# STU 事项助手 · 前端

同一份界面三种形态：网页版（后端直接托管 `dist/`）、安卓 App（Capacitor，`dist-android/`）、iPhone 主屏 PWA。设计说明见 `../DESIGN.md`，部署见 `../deploy/README.md`。

## 开发

```powershell
npm install
npm run dev                      # http://localhost:5180，/v1 代理到 127.0.0.1:8100
```

不连后端也能看：`http://localhost:5180/?preview=1`。加 `&now=2026-10-28T10:20` 固定时间，`&theme=dark` 固定外观，`&tab=week` 打开指定分页。

## 构建

```powershell
npm run build                                                           # 网页版 → dist/
powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1 -Server https://你的域名   # 签名 APK
powershell -ExecutionPolicy Bypass -File ..\deploy\make-release.ps1 -Server https://你的域名  # 全部发布物 → ..\release\
node scripts/icons.mjs [--android] [--sheet]                            # 由一个定义生成全部图标
```

APK 的签名密钥在 `..\android-signing\`，不在这个目录里，也不会进入网页版。

## 检查

```powershell
node scripts/audit.mjs        # axe 无障碍、触控目标、横向溢出、CLS（需 npm run dev）
node scripts/shoot.mjs        # 全部页面截图 → shots/（需 npm run dev）
node scripts/e2e.mjs          # 网页版完整流程（需后端演示模式托管 dist/）
node scripts/e2e-native.mjs   # App 包首次启动流程（需 vite preview --outDir dist-android）
```

后端演示模式：在 `../backend` 运行 `STU_APP_DEMO=1 STU_APP_WEB_DIR=<web/dist> uvicorn app.main:app --port 8100`。
