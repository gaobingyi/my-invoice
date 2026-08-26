# 发票管理系统开发计划

## Context

Greenfield 项目。按 Requirements.md 开发发票管理系统：上传（存本地磁盘）、解析（PDF）、列表展示。前后端分离，后端 Java 25 + Spring Boot + SQLite，前端待定（本计划给推荐）。

**PDF 解析可行性已验证**：示例发票为纯文本 PDF（非扫描件），文本规整，用 PDFBox 抽文本 + 正则提取即可，无需 OCR。关键字段：`发票号码`(20位数字)、`开票日期`、购买方/销售方名称与税号、项目名称行（*餐饮服务*餐饮服务，含数量/单价/金额/税率/税额）、价税合计大小写、开票人。

## 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 后端 | Spring Boot 4.x, Java 25, Maven | 需求指定 |
| DB | SQLite + Spring Data JPA | 需求指定；CRUD 场景 JPA 最省事 |
| PDF 解析 | **Apache PDFBox 3** | Java 生态标准库，纯文本 PDF 直接 `PDFTextStripper`，无外部进程依赖 |
| 文件存储 | 本地磁盘 `./uploads/`，DB 存相对路径 | 需求指定 |
| 前端 | **Vue 3 + Vite + Element Plus + Axios** | 管理后台场景组件全、表格/上传开箱即用；比 React 生态在同场景模板代码少 |
| 包管理 | npm + Vite dev proxy 转发 `/api` 到 8080 | 免 CORS 配置 |

备选：React 18 + Ant Design（若团队更熟 React）；OCR 方案（Tess4J）仅当后续遇到扫描件再加。

## 数据模型

`invoice` 表：
- `id` BIGINT PK auto_increment
- `invoice_number` VARCHAR(20) UNIQUE（发票号码，防重复上传）
- `invoice_date` DATE
- `buyer_name` / `buyer_tax_id` VARCHAR
- `seller_name` / `seller_tax_id` VARCHAR
- `total_amount` DECIMAL(10,2)（金额合计，不含税）
- `tax_amount` DECIMAL(10,2)
- `total_with_tax` DECIMAL(10,2)（价税合计小写）
- `category` VARCHAR(64)（项目名称，如 `*餐饮服务*餐饮服务`）
- `drawer` VARCHAR(64)（开票人）
- `file_path` VARCHAR(255)（磁盘相对路径）
- `created_at` DATETIME

明细行（规格/数量/单价）本期不拆表 —— 示例发票单行，YAGNI；需要多行明细时再加 `invoice_item` 子表。

## 后端结构（`server/`）

标准分层，单模块 Maven：

```
server/src/main/java/com/example/invoice/
  InvoiceApplication.java
  entity/Invoice.java
  repository/InvoiceRepository.java        // JpaRepository，findByInvoiceNumber
  service/InvoiceParser.java               // PDFBox + 正则 → ParseResult record
  service/InvoiceService.java              // 存盘 + 解析 + 入库，重复号码→409
  controller/InvoiceController.java        // /api/invoices
```

**API**：
- `POST /api/invoices/upload` （multipart file）→ 校验 PDF 类型/大小(≤10MB) → 存 `uploads/yyyyMM/uuid.pdf` → 解析 → 入库 → 返回 Invoice
- `GET /api/invoices?page=&size=` → 分页列表，按开票日期倒序
- `GET /api/invoices/{id}/file` → 返回 PDF 文件流（前端预览/下载）

**解析正则**（基于示例布局，每字段独立提取，缺失字段容忍 null）：
- 号码： `发票号码：(?<no>\d{20})`
- 日期： `开票日期：(?<y>\d{4})年(?<m>\d{2})月(?<d>\d{2})日`
- 购买方： `购` 区块后 `名称：(.*?)\s`，税号 `纳税人识别号：([0-9A-Z]{18})`（购/售各取一次）
- 明细行金额： 行内 `数量 单价 金额 税率 税额` 数字序列
- 价税合计小写： `（小写）¥?(\d+\.\d{2})`
- 开票人： `开票人：(\S+)`

配置：`application.yml` 读 `spring.datasource.*`（环境变量注入密码）、`upload-dir=./uploads` (`@PostMapping` 时 `Files.createDirectories`)。

## 前端结构（`web/`）

Vue 3 + Vite + Element Plus，单页：

```
web/src/
  api/invoice.js          // axios 封装：uploadInvoice, listInvoices
  views/InvoiceList.vue   // 上传按钮(elf-upload drag) + el-table 列表 + 分页
  App.vue / main.js
```

列表列：发票号码、开票日期、销售方、金额、税额、价税合计、操作（下载）。上传成功/失败用 ElMessage 提示，成功后刷新列表。`vite.config.js` proxy `/api → localhost:8080`。

## 实施步骤

1. `server/` Maven 骨架（spring-boot-starter-web/data-jpa/validation + sqlite-jdbc + hibernate-community-dialects + pdfbox + lombok）
2. Entity + Repository + schema（用 `ddl-auto: update`，本地开发够用）
3. `InvoiceParser` + 用示例 PDF 文本写的单元测试（assert 全字段提取正确）
4. `InvoiceService` + `InvoiceController`（上传/列表/下载）
5. `web/` Vite + Vue 骨架，InvoiceList 页面
6. 端到端联调

## 验证

后端：
- `cd server && mvn test` — 解析器单元测试（以上面提取的示例文本为 fixture，断言号码 `26322000004144614676`、金额 `189.62`、税额 `11.38`、价税合计 `201.00` 等）
- `mvn spring-boot:run` 后 `curl -F "file=@_餐饮服务_..._201.00.pdf" localhost:8080/api/invoices/upload` 返回入库 JSON；再 `curl localhost:8080/api/invoices` 见该条
- 重复上传同一 PDF → 409

前端：`cd web && npm run dev`，浏览器上传示例 PDF，列表出现该发票，点击下载能取回 PDF。

环境前置：SQLite 是文件式数据库，无需起服务。`server/data/invoice.db` 由 `spring.sql.init.mode: always` 在首次启动时自动建。

## 明确跳过

- OCR（扫描件）—— 遇到再加 Tess4J
- 明细行子表、用户认证、发票验真（税务总局查验接口）
- Docker 化部署 —— 本期本地开发即可
