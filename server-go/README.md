# server-go — Go 后端（与 Java 版可切换）

发票管理系统的 Go 重写后端——与 `../server`（Java/Spring Boot）**行为逐字对齐**的并行实现（API 契约、业务规则、解析算法 1:1 移植）。生产 VPS 上与 Java 版共享同一 SQLite + uploads，systemd `Conflicts` 互斥、`scripts/switch-backend.sh` 一条命令切换（当前活跃后端以 `switch-backend.sh status` 为准）。解析原理与业务规则看仓库根 [AGENTS.md](../AGENTS.md) 与 [README.md](../README.md)。

## 与 Java 版的有意偏离

| 偏离 | 原因 |
|---|---|
| 文本/字形提取引擎用 **poppler `pdftotext -bbox`**（外部进程） | spike 实测：纯 Go 库 dslipak/pdf 对两类关键票不可用——京东票的值画在 Form XObject 里（rsc 系不遍历），铁路票用预定义 GBK CMap（rsc 系无映射表，CJK 全乱码）。poppler 通吃，词级 bbox 粒度 ≈ PDFBox writeString |
| poppler 的 Adobe-GB1 映射把部分票面 ASCII 输出成**全角**（铁路票号码/小数点），提取层做了全角→半角归一化 | PDFBox 走 ToUnicode 给的是半角，归一化以对齐解析行为 |
| 文本重建的换行判定用**纵向区间重叠**（非 Δy 阈值） | 同视觉行不同字号的词（如「扬州/站」）yMin 可差数 pt |
| YEN 正则的 `\s` 限定**同行空白**（不跨换行） | poppler 阅读序文本中「上一行行尾数字 + 下一行行首 ¥」会被跨行误配 |
| Layout 的 y 按页加偏移 | 修正 Java 版跨页 glyph 混排进同一 y 桶的隐患 |
| JWT 固定 HS256（Java 版 jjwt 按密钥长度自动选） | 密钥与 Java 版同源（.env 的 JWT_SECRET）时 token 可跨栈通用；本地 dev 各用各的默认密钥，切换后重登即可 |
| 日志走 slog/stdout（journald 接管），无文件滚动 | 单机部署简化 |
| 分页响应含 content/totalPages/totalElements/size/number/first/last/numberOfElements/empty/pageable/sort 全字段 | 前端只用前三个，形状保持兼容 |

## 共享 DB 的存储格式对齐（`internal/store/stamps.go`）

VPS 上两套后端共用 `/opt/invoice/data/db/invoice.db`，写入格式必须与 Java/Xerial `date_class=TEXT` **逐字节一致**，否则混写行 `ORDER BY created_at` 错序、Java 读回有解析风险：

- **写**：`created_at`/`used_at`/批次 `created_at` → `yyyy-MM-dd HH:mm:ss.SSS`（Xerial LocalDateTime 形态）；`invoice_date` → `yyyy-MM-dd 00:00:00.000`（Xerial 把 LocalDate 补全为午夜时间戳）。
- **读**：归一化回 API 形态（对齐 Jackson）——日期截回 `yyyy-MM-dd`、时刻转 `yyyy-MM-ddTHH:mm:ss`；同时兼容早期 Go 版写入的 `T` 整秒格式。
- 已由本地双向读写实验验证：Java 写 → Go 读/写 → Java 再读 → Go 再读，四阶段全过、无 ParseException、存储形态逐字节一致。**动 `stamps.go` 或时间戳相关代码后必须重跑该实验。**

## 架构

```
cmd/server/            入口：装配 + 优雅停机 30s
cmd/parsetool/         解析对照工具：spike PDFs → TSV，与 truth.tsv 逐字段 diff
cmd/dumptext/          调试工具：dump poppler 文本提取
internal/
  config/              env 全表（INVOICE_DB/UPLOAD_DIR/JWT_SECRET/ADMIN_*/LLM_*/WX_*/TRUST_PROXY/SERVER_ADDRESS/STATIC_DIR/POPPLER_PDFTOTEXT）
  store/               schema.sql(embed，与 Java 版互为镜像) + PRAGMA 迁移 + 单连接（单写者串行）+ stamps.go 存储格式对齐
  money/               Decimal：字符串精确保留 scale，JSON number 原样输出
  pdf/                 poppler 提取（词流 + 逐字形几何均摊近似）+ 全角归一化 + 文本重建
  parser/              正则路径（三值不变量/免税分支/铁路分支/位置配对）→ Layout 字形坐标（LINE_TOL=3/GAP=1.6）→ LLM 兜底
  metrics/             parsing_metrics/parsing_log；flush 绝不拖垮上传
  auth/                JWT HS256、BCrypt seed、限流(60s≥10/1h≥100)、wx-login、X-Real-IP 信任策略
  invoice/             上传全流程（tmp→解析→购方校验→哨兵→os.Link 原子改名→孤儿处理→截断→入库/409）
  export/              批次校验链/重算/used 恢复三处一致、writeZip（xlsx 第一条目+缺失清单）、Excel StreamWriter
  api/                 handlers + 错误映射（400/409/413 text/plain、401 JSON、500 固定文案）
  webstatic/           可选：服务 Vue dist（SPA fallback + 缓存头）
```

依赖（4 个直接依赖）：`modernc.org/sqlite`（纯 Go 静态编译）、`golang-jwt/jwt/v5`、`x/crypto`（bcrypt）、`xuri/excelize/v2`。

## 运行

```bash
# 依赖：Go ≥1.27；运行时依赖 poppler-utils + poppler-data（文本提取）
CGO_ENABLED=0 go build -o bin/invoice-server ./cmd/server

# 本地起服务（空库自建、管理员 admin/admin123 自动 seed）；或仓库根 BACKEND=go ./scripts/dev.sh
LLM_ENABLED=false SERVER_ADDRESS=127.0.0.1:8080 \
INVOICE_DB=./data/invoice.db UPLOAD_DIR=./uploads ./bin/invoice-server

# 冒烟（登录→上传→批次→ZIP→指标 全链路 27 项断言）
BASE=http://127.0.0.1:8080 SAMPLE=<真实发票.pdf> bash scripts/smoke.sh
```

## 解析对照（spike 基线）

`spike/`（gitignore）持有从生产拷贝的 11 张代表性发票 PDF + 生产 DB 真值：
`cmd/parsetool` 逐张解析（无 LLM）→ 与真值逐字段 diff —— **11/11 完全一致**（覆盖京东两列同排、铁路 GBK 全角、免税、个体户、经营部）。
改解析器/提取层后必须重跑此对照；**解析修复必须 Java/Go 两边同步落地**（两套解析器长期并存）。

## 生产部署（VPS，与 Java 版互斥切换）

生产 VPS（103.232.94.248）上两套后端并存、共享数据，**默认 Java 活跃**：

- 二进制：`/opt/invoice/server-go/bin/invoice-server`（本地 `CGO_ENABLED=0 go build` 后 scp）。
- 单元：`/etc/systemd/system/invoice-go.service`（入库于 `../deploy/`），与 `invoice-backend.service` 互相 `Conflicts=`——start 一个自动停另一个；`EnvironmentFile=/opt/invoice/.env.systemd` 与 Java 单元共用（键名两栈对齐）；单元内固定 `INVOICE_DB`/`UPLOAD_DIR`/`SERVER_ADDRESS=127.0.0.1:8080`/`STATIC_DIR`/`TZ`/`TRUST_PROXY`。
- 切换：仓库根 `scripts/switch-backend.sh java|go|status`（start + enable/disable 对调，保证重启后仍起对的后端）。
- 运行时依赖：`apt install poppler-utils poppler-data`（data 提供 Adobe-GB1 CJK 映射，缺它铁路票全乱）。
- 备份（`scripts/backup-mega.sh`）与数据路径无关，谁当后端都照常。

## instabox 并行实例（已退役，2026-11-01）

曾在 instabox-dev（`gaobingyi@100.125.166.11`，tailnet 内）部署独立并行实例：systemd 单元 `/etc/systemd/system/invoice-go.service` + `/opt/invoice-go/{bin,data,static,.env}`。2026-11-01 随生产 VPS 双后端上线而退役——单元与部署目录已删，机器保留。历史部署细节（/opt 路径对 systemd 可见性、poppler-data 依赖、keepalive cron 保活、SSH 限频）翻 git 历史本文；生产 VPS 上的 `instabox-keepalive` cron 独立于本部署，未随之拆除。
