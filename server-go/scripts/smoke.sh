#!/usr/bin/env bash
# API 全链路冒烟：登录 → 上传（真票/重复/坏文件）→ 列表/汇总 → 批次（创建/ZIP/删除/恢复）→ 指标。
# 用法：BASE=http://127.0.0.1:8080 SAMPLE=<真实发票.pdf> bash scripts/smoke.sh
# 依赖：curl、python3（断言）。服务需已在运行（数据为空库或可写入）。
set -uo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
SAMPLE="${SAMPLE:?需要 SAMPLE 指向一张真实发票 PDF}"
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "✗ $1"; }
check() { # check <说明> <期望> <实际>
  if [ "$2" = "$3" ]; then ok "$1"; else fail "$1（期望=$2 实际=$3）"; fi
}

# 1. ping
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/auth/ping")
check "ping 200" 200 "$code"
body=$(curl -s "$BASE/api/auth/ping")
check "ping body pong" "pong" "$body"

# 2. 未认证 401（JSON 体）
resp=$(curl -s -w "\n%{http_code}" "$BASE/api/invoices")
code=$(echo "$resp" | tail -1); body=$(echo "$resp" | head -1)
check "未认证 401" 401 "$code"
echo "$body" | grep -q "未登录或登录已过期" && ok "401 JSON 体" || fail "401 JSON 体: $body"

# 3. 登录（错误密码 401 / 正确 200）
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}')
check "错误密码 401" 401 "$code"
resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}')
code=$(echo "$resp" | tail -1); body=$(echo "$resp" | head -1)
check "登录 200" 200 "$code"
TOKEN=$(echo "$body" | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
[ -n "$TOKEN" ] && ok "拿到 token" || fail "token 为空"
AUTH="Authorization: Bearer $TOKEN"

# 4. 上传：非 PDF / 好票 / 重复
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/invoices/upload" \
  -H "$AUTH" -F "file=@$SAMPLE;filename=x.txt")
check "非 PDF 400" 400 "$code"
resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/invoices/upload" -H "$AUTH" -F "file=@$SAMPLE")
code=$(echo "$resp" | tail -1); body=$(echo "$resp" | head -1)
check "上传真票 200" 200 "$code"
INV_ID=$(echo "$body" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['id'])" 2>/dev/null)
SELLER=$(echo "$body" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('sellerName') or '')" 2>/dev/null)
[ -n "$INV_ID" ] && ok "上传返回 id=$INV_ID seller=$SELLER" || fail "上传无 id: $body"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/invoices/upload" -H "$AUTH" -F "file=@$SAMPLE")
check "重复上传 409" 409 "$code"

# 5. 列表 / 汇总 / 单条
resp=$(curl -s "$BASE/api/invoices?page=0&size=20" -H "$AUTH")
cnt=$(echo "$resp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d['content']), d['totalElements'], d['totalPages'])" 2>/dev/null)
[ -n "$cnt" ] && ok "列表分页形状（content/totalElements/totalPages）=$cnt" || fail "列表形状异常: $resp"
resp=$(curl -s "$BASE/api/invoices/summary" -H "$AUTH")
echo "$resp" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['count'] >= 1" 2>/dev/null \
  && ok "summary count>=1: $resp" || fail "summary 异常: $resp"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/invoices/$INV_ID" -H "$AUTH")
check "单条 200" 200 "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/invoices/999999" -H "$AUTH")
check "不存在 400" 400 "$code"

# 6. 文件预览/下载
ct=$(curl -s -o /dev/null -w "%{http_code} %{content_type}" "$BASE/api/invoices/$INV_ID/file" -H "$AUTH")
echo "$ct" | grep -q "200 application/pdf" && ok "文件预览 $ct" || fail "文件预览: $ct"
cd=$(curl -s -o /dev/null -w "%{header_json}" -D - "$BASE/api/invoices/$INV_ID/file?disposition=download" -H "$AUTH" 2>/dev/null | grep -i content-disposition)
echo "$cd" | grep -q "attachment" && ok "下载 attachment 头" || fail "下载头: $cd"

# 7. 批次：创建 → 条目 → ZIP → 删除恢复 → 删发票
MONTH=$(date +%Y-%m)
resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/export-batches" -H "$AUTH" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$INV_ID],\"batchMonth\":\"$MONTH\"}")
code=$(echo "$resp" | tail -1); body=$(echo "$resp" | head -1)
check "创建批次 200" 200 "$code"
BATCH_ID=$(echo "$body" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" 2>/dev/null)
[ -n "$BATCH_ID" ] && ok "批次 id=$BATCH_ID" || fail "批次无 id: $body"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/export-batches" -H "$AUTH" \
  -H 'Content-Type: application/json' -d "{\"ids\":[$INV_ID],\"batchMonth\":\"$MONTH\"}")
check "批次月份重复 400" 400 "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE/api/invoices/$INV_ID" -H "$AUTH")
check "被批次引用的发票拒删 400" 400 "$code"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/export-batches/$BATCH_ID/zip" -H "$AUTH")
check "ZIP 200" 200 "$code"
curl -s "$BASE/api/export-batches/$BATCH_ID/zip" -H "$AUTH" -o /tmp/smoke.zip
python3 - <<'PYEOF' 2>/dev/null && ok "ZIP 结构（发票清单.xlsx 第一条目 + PDF）" || fail "ZIP 结构异常"
import zipfile
z = zipfile.ZipFile("/tmp/smoke.zip")
names = z.namelist()
assert "发票清单.xlsx" == names[0], names
assert any(n.endswith(".pdf") for n in names), names
assert z.read("发票清单.xlsx")[:2] == b"PK", "xlsx 魔数"
# sheet 行数抽查：xlsx 是 zip 套 zip，解出 sheet1 检查含「发票清单」标题与「合计」行
import io
sheet = zipfile.ZipFile(io.BytesIO(z.read("发票清单.xlsx"))).read("xl/worksheets/sheet1.xml").decode("utf-8", "replace")
assert "发票清单" in sheet and "合计" in sheet, "sheet 内容缺失"
PYEOF
restored=$(curl -s -X DELETE "$BASE/api/export-batches/$BATCH_ID" -H "$AUTH" | python3 -c "import json,sys; print(json.load(sys.stdin)['restored'])" 2>/dev/null)
check "删除批次 restored=1" 1 "$restored"
code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE/api/invoices/$INV_ID" -H "$AUTH")
check "删发票 200" 200 "$code"

# 8. 指标
resp=$(curl -s "$BASE/api/metrics/parsing" -H "$AUTH")
echo "$resp" | python3 -c "
import json,sys
d = json.load(sys.stdin)
assert d['uploadsTotal'] >= 2, d
assert 'regex' in d and 'llm' in d" 2>/dev/null && ok "指标计数 uploadsTotal" || fail "指标异常: $resp"
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/metrics/parsing/trend?days=7" -H "$AUTH")
check "趋势 200" 200 "$code"

echo
echo "== 冒烟结果: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" = 0 ]
