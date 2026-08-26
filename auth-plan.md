# 登录认证方案

## Context

发票管理系统目前完全无认证：任何能访问 8080/8088 的人都能上传/查看/删除发票。需求是加登录功能。已确认三个决策：**JWT 无状态认证**、**单管理员用户**（不做注册）、**引入 vue-router**。

现状（探索结论）：
- 后端零安全设施：无 spring-security 依赖、无 user 表、无全局异常处理器，错误体是纯 String；JPA `ddl-auto: validate`，建表必须走 `schema.sql`
- 前端无 vue-router/Pinia，`App.vue` 用 `activeMenu` ref + localStorage 状态机切视图；`api/invoice.js` 的 `fileUrl()` 返回裸 URL 字符串（绕过 axios，带不了 Authorization header）
- `web/e2e/run.mjs` 直接 `fetch /api` 清库断言 → 加认证后必挂
- docker-compose backend 健康检查 `wget /api/invoices` → 变 401 后 busybox wget spider 返回非 0，healthcheck 挂

## 方案总览

```
后端                     前端
POST /api/auth/login  →  Login.vue（居中卡片，EP form）
JwtAuthenticationFilter  axios 请求拦截器带 Bearer header
                        vue-router 守卫：无 token → /login
                        响应 401 → 清 token 跳 /login
app_user 表 + seed admin  header 右上：用户名 + 退出
```

## 后端改动（server/）

### 1. 依赖（`server/pom.xml`）
- `org.springframework.boot:spring-boot-starter-security`
- jjwt 0.12.6 三件套：`io.jsonwebtoken:jjwt-api` / `jjwt-impl`(runtime) / `jjwt-jackson`(runtime)

### 2. 建表（`server/ddl/schema.sql` 追加）
```sql
CREATE TABLE IF NOT EXISTS app_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录用户表';
```

### 3. 新代码（`com.example.invoice` 下新增包）
- `entity/User.java` — 对应 `app_user`，仅 username/passwordHash
- `repository/UserRepository.java` — `findByUsername(String)`
- `dto/LoginRequest.java`（username/password，带 `@NotBlank`）、`dto/LoginResponse.java`（token、username）
- `service/AuthService.java` — `login()` 用 `PasswordEncoder.matches` 校验 + 签 JWT；`CommandLineRunner` 逻辑放这里：查无 admin 则插入（默认 `admin/admin123`，可被 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 覆盖）
- `service/JwtTokenService.java` — 签发/解析/校验，secret 与过期时间从 `@Value("${app.jwt.secret}")` 读
- `config/SecurityConfig.java` — `SecurityFilterChain` bean：
  - `permitAll("/api/auth/**")`，其余 `/api/**` `authenticated`
  - `csrf.disable()`（无状态 JWT）
  - 注册 `JwtAuthenticationFilter` 到 filter chain（`OncePerRequestFilter`，Bearer 解析 → `SecurityContextHolder`）
  - `AuthenticationEntryPoint` → 401 JSON `{"message":"未登录或登录已过期"}`
  - `PasswordEncoder` bean = `BCryptPasswordEncoder`
- `config/JwtAuthenticationFilter.java`
- `controller/AuthController.java` — `POST /api/auth/login`（成功返回 token，失败 401）、`GET /api/auth/ping`（公开，供 docker healthcheck）

### 4. 配置（`application.yml` 追加）
```yaml
app:
  jwt:
    secret: ${JWT_SECRET:invoice-manager-dev-secret-change-me}
    expire-seconds: 86400   # 24h
  admin:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:admin123}
```

### 5. 现有代码影响
- `InvoiceController` 的本地 `@ExceptionHandler` 不变（auth 错误由 Security filter 层处理，不冲突）
- 无需改现有 4 个业务端点

## 前端改动（web/）

### 1. 依赖
- `vue-router@4`（Pinia 不引入，token 用 localStorage + 简单 composable 即可）

### 2. 路由（新增 `src/router/index.js` + `main.js` 注册）
```
/login          → Login.vue
/               → Layout（App.vue 改造）  redirect → /upload
/upload         → InvoiceUpload.vue
/list           → InvoiceList.vue
```
- `beforeEach` 守卫：`localStorage` 无 token 且目标非 `/login` → 重定向 `/login`

### 3. `App.vue` → Layout
- `el-menu` 改为路由驱动：`@select` 里 `router.push(index === 'upload' ? '/upload' : '/list')`，`:default-active` 改为基于 `route.path`
- header 右侧加 `el-dropdown`：显示用户名 + 「退出登录」（清 localStorage + 跳 `/login`）
- 布局 DOM 结构尽量保持（E2E 依赖 `input[type=file]`、`.upload-btn`、`tbody tr`）

### 4. `views/Login.vue`（新增）
- 居中卡片 + EP form（用户名/密码），提交调 `POST /api/auth/login`，成功存 token + 用户名，`router.push('/upload')`

### 5. `api/invoice.js` 改造
- 抽出共享 `http` 实例，加：
  - **请求拦截器**：`Authorization: Bearer <token>`
  - **响应拦截器**：401 → 清 localStorage token → `location.href = '/login'`
- 新增 `login(username, password)` 函数
- **文件预览/下载必须改**：`fileUrl()` 返回裸 URL 带不了 header。改为 async `fetchFile(id, disposition)` 用 axios `responseType: 'blob'` 取回 → `URL.createObjectURL(blob)`。`InvoiceList.vue` 预览 dialog 的 iframe `src` 和下载 `<a download>` 改用 blob URL（Chrome 支持 blob PDF）

### 6. E2E（`web/e2e/run.mjs`）必须改造
- `resetInvoices()` 和末尾断言前：先 `fetch('http://localhost:5173/api/auth/login', POST admin/admin123)` 拿 token，后续请求带 `Authorization: Bearer`
- 页面 `goto` 前用 `page.evaluateOnNewDocument` 注入 localStorage token（比 UI 登录快且稳）
- 新增断言：未登录访问 `/` 跳 `/login`、登录后可进上传视图

## 配置 / Docker

- `docker-compose.yml` backend 环境追加：`JWT_SECRET: ${JWT_SECRET:...}`、`ADMIN_PASSWORD: ${ADMIN_PASSWORD:-admin123}`，**健康检查改**为 `wget --spider http://localhost:8080/api/auth/ping`（公开端点）
- 根 `.env.example` 追加 `JWT_SECRET`、`ADMIN_PASSWORD` 说明
- `server/.env`（gitignored）本地 dev 可加 `JWT_SECRET`
- `web/nginx.conf` 已含 `try_files ... /index.html` SPA 回退，**无需改**；`vite.config.js` 无需改（dev proxy 不变）

## 文档
- `README.md`：加登录说明（默认账号 `admin/admin123`，生产用 `ADMIN_PASSWORD` 改）、JWT_SECRET 配置
- `CLAUDE.md`：补充认证架构简述（auth 流程、`/api/auth/**` 公开、文件端点走 blob）

## 验证

1. 后端单测：`cd server && mvn test`（现有 parser 测试不受影响）+ 新增 `JwtTokenServiceTest`（签发→校验 roundtrip、篡改/过期失败）
2. 起 MySQL + 后端：`curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'` → 200 拿 token；`curl localhost:8080/api/invoices` 无 token → 401；带 `Authorization: Bearer <token>` → 200
3. 前端：`cd web && npm run dev`，访问 5173 未登录跳 `/login`，登录后进 `/upload`，刷新保持登录，退出回 `/login`
4. E2E 全流程：起 MySQL + 后端 + 前端，`node web/e2e/run.mjs` → 10+ 项断言（含新增登录断言）全过
5. Docker：`docker compose up -d --build` → `docker compose ps` 全 healthy（验证 healthcheck 改用 `/api/auth/ping` 生效），浏览器 8088 走登录 → 上传 → 列表全链路
6. 截图自验 UI（`web/e2e/screenshots/`），确认暗色/亮色下登录页正常