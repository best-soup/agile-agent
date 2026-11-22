# Agile Agent Web

Vue 3 / Vite / TypeScript 本地演示前端。需要 Node.js 20.19+ 或 22.12+，推荐 Node 22 LTS。

先按根目录 README 启动 Compose 依赖、初始化 Qdrant collection 并启动 Spring Boot。创建用户取得一次性 API Key，随后在另一个终端：

```powershell
cd web
npm install
Copy-Item .env.example .env.local
npm run dev
```

打开 `http://localhost:5173`，输入后端地址和 API Key。`VITE_API_BASE_URL` 只提供默认地址，可以在连接页调整；不要把密钥放入任何 `.env`。构建用 `npm run build`，其中包含 TypeScript 检查。本项目暂未配置前端测试框架。

后端默认只允许 `http://localhost:5173` 跨域，不使用 Cookie。若从手机访问，`localhost` 指手机自身，应使用电脑的局域网 IP、将前端默认地址或连接页中的 API 地址改为 `http://电脑IP:8080`，并在启动后端前将 `AGILE_CORS_ALLOWED_ORIGINS` 设为准确的前端地址，如 `http://电脑IP:5173`；确保手机与电脑网络互通、防火墙允许访问。多个允许来源用逗号分隔，勿使用通配符。仅在可信的本地网络中使用，HTTP 不提供传输加密。

API Key 仅用于本地演示，保存在当前标签页的 sessionStorage，关闭标签页或点击退出后失效；同源恶意脚本、本机用户或浏览器扩展仍可能读取，不能作为生产网页登录方案。请求不会记录凭证，也不将其放入 URL。提交草稿时的幂等键也保存在标签页，用于网络错误重试；后端已接受的键可从草稿响应恢复。

可手动验收：连接用户 → 选择项目 → 导入测试会议 → 生成并编辑草稿 → 确认 → 提交 → 查看任务。生成草稿和 RAG 索引依赖可用的模型及外部服务；没有真实环境时不要将该流程标记为通过。

当前不提供 PWA、Capacitor 或 Android 包；后续可以在评估安全和适配后复用这套前端。
