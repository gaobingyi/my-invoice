<template>
  <el-card shadow="never">
    <div class="list-toolbar">
      <div class="list-title">
        <span class="title-dot"></span>
        <span class="page-title">发票列表</span>
        <el-tag size="small" type="info" round effect="plain">共 {{ total }} 张</el-tag>
        <el-button
          class="list-refresh"
          :icon="Refresh"
          circle
          :loading="loading"
          title="刷新"
          @click="load"
        />
        <el-radio-group v-model="usedFilter" size="small">
          <el-radio-button value="unused">未使用</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
      </div>
      <el-input
        v-model="keyword"
        class="list-search"
        placeholder="搜索销售方 / 购买方 / 号码 / 项目"
        clearable
        :prefix-icon="Search"
      />
      <div class="batch-actions">
        <span v-if="selected.length" class="batch-summary">
          已选 {{ selected.length }} 张 · ¥{{ money(selectedSum) }}
          <el-button link type="primary" @click="clearSelection">清空</el-button>
        </span>
        <el-button
          class="batch-export-btn"
          type="primary"
          :disabled="!selected.length"
          @click="openExportDialog"
        >打包下载</el-button>
      </div>
    </div>
    <!-- 移动端：卡片列表（桌面 el-table 保持原样，e2e 断言依赖其 DOM） -->
    <div
      v-if="isMobile"
      v-loading="loading"
      class="card-list"
    >
      <el-empty v-if="!rows.length" description="暂无发票">
        <el-button type="primary" @click="router.push('/upload')">去上传</el-button>
      </el-empty>
      <div v-for="row in rows" :key="row.id" class="inv-card" @click="preview(row)">
        <div class="inv-card-top">
          <el-checkbox
            :model-value="isSelected(row)"
            :disabled="!!row.used"
            @change="toggleSelect(row)"
            @click.stop
          />
          <div class="inv-card-main">
            <div class="inv-card-seller">{{ row.sellerName || '未知销售方' }}</div>
            <div class="inv-card-meta">{{ row.invoiceNumber }} · {{ row.invoiceDate }}</div>
          </div>
          <div class="inv-card-side">
            <div class="inv-card-amount">¥{{ money(row.totalWithTax) }}</div>
            <el-tag size="small" :type="row.used ? 'warning' : 'success'">
              {{ row.used ? '已使用' : '未使用' }}
            </el-tag>
          </div>
        </div>
        <div class="inv-card-bottom">
          <span class="inv-card-category">{{ row.category || '—' }}</span>
          <span class="inv-card-actions">
            <el-button link type="primary" @click.stop="preview(row)">预览</el-button>
            <el-button link :loading="downloadingId === row.id" @click.stop="download(row)">下载</el-button>
            <el-button link type="danger" @click.stop="confirmDelete(row)">删除</el-button>
          </span>
        </div>
      </div>
    </div>
    <el-table
      v-else
      ref="tableRef"
      :data="rows"
      row-key="id"
      v-loading="loading"
      stripe
      @selection-change="onSelectionChange"
    >
      <template #empty>
        <el-empty description="暂无发票，上传 PDF 后在这里查看">
          <el-button type="primary" @click="router.push('/upload')">去上传</el-button>
        </el-empty>
      </template>
      <el-table-column type="selection" width="42" :selectable="row => !row.used" />
      <el-table-column prop="invoiceNumber" label="发票号码" width="220" />
      <el-table-column prop="invoiceDate" label="开票日期" width="120" />
      <el-table-column prop="sellerName" label="销售方" min-width="180" show-overflow-tooltip />
      <el-table-column prop="buyerName" label="购买方" min-width="180" show-overflow-tooltip />
      <el-table-column prop="category" label="项目名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="totalAmount" label="金额" width="110" align="right">
        <template #default="{ row }">{{ withYuan(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column prop="taxAmount" label="税额" width="110" align="right">
        <template #default="{ row }">{{ withYuan(row.taxAmount) }}</template>
      </el-table-column>
      <el-table-column prop="totalWithTax" label="价税合计" width="130" align="right" class-name="col-strong">
        <template #default="{ row }">{{ withYuan(row.totalWithTax) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="上传时间" width="170">
        <template #default="{ row }">
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.used" size="small" type="warning">已使用</el-tag>
          <el-tag v-else size="small" type="success">未使用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="preview(row)">预览</el-button>
          <el-button link :loading="downloadingId === row.id" @click="download(row)">下载</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="table-scroll-hint" aria-hidden="true"></div>

    <el-pagination
      v-if="total > 0"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      :page-size="pageSize"
      :current-page="currentPage"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
  </el-card>

  <PdfPreviewDialog ref="pdfPreviewRef" />

  <el-dialog v-model="exportVisible" title="创建导出批次" width="min(420px, 92%)">
    <div class="export-form">
      <div class="export-field">
        <span class="export-label">批次月份</span>
        <el-date-picker
          v-model="exportMonth"
          type="month"
          placeholder="选择月份"
          format="YYYY-MM"
          value-format="YYYY-MM"
          :clearable="false"
        />
      </div>
      <div class="export-summary">已选 {{ selected.length }} 张 · 价税合计 ¥{{ money(selectedSum) }}</div>
    </div>
    <template #footer>
      <el-button @click="exportVisible = false">取消</el-button>
      <el-button type="primary" :loading="exporting" @click="submitExport">创建批次</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listInvoices, deleteInvoice, fetchFile, errorMessage, createExportBatch } from '../api/invoice'
import { useIsMobile } from '../composables/useIsMobile'
import { formatTime, money, currentMonth } from '../utils/format'
import { saveBlob } from '../utils/download'
import PdfPreviewDialog from '../components/PdfPreviewDialog.vue'

const router = useRouter()
const isMobile = useIsMobile()

const rows = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const loading = ref(false)
// 未使用 / 全部（原「已使用」双文案开关语义反直觉，改 radio 明示）
const usedFilter = ref('unused')
const keyword = ref('')
let searchTimer = null

const pdfPreviewRef = ref(null)

// ===== 批量导出 =====
const tableRef = ref(null)
const selected = ref([])
const exportVisible = ref(false)
const exporting = ref(false)
const exportMonth = ref(currentMonth())

const selectedSum = computed(() =>
  selected.value.reduce((acc, r) => acc + (parseFloat(r.totalWithTax) || 0), 0)
)

const downloadingId = ref(null)

function onSelectionChange(sel) {
  // load() 整体替换 rows 也会触发本回调：翻页即清空选择（不做跨页保留，简单可预期）
  selected.value = sel
}

function clearSelection() {
  tableRef.value?.clearSelection?.()
  selected.value = []
}

// 移动端卡片勾选（el-table 不渲染，没有 selection-change，直接维护同一份 selected）
function isSelected(row) {
  return selected.value.some(r => r.id === row.id)
}
function toggleSelect(row) {
  const i = selected.value.findIndex(r => r.id === row.id)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(row)
}

function openExportDialog() {
  if (!selected.value.length) return
  exportMonth.value = currentMonth()
  exportVisible.value = true
}

async function submitExport() {
  if (!exportMonth.value) {
    ElMessage.warning('请选择批次月份')
    return
  }
  exporting.value = true
  try {
    const ids = selected.value.map(r => r.id)
    await createExportBatch(ids, exportMonth.value)
    exportVisible.value = false
    ElMessage.success('批次已创建，请在导出记录页下载')
    router.push('/exports')
  } catch (e) {
    ElMessage.error(await errorMessage(e, '创建批次失败'))
  } finally {
    exporting.value = false
  }
}

async function load() {
  loading.value = true
  try {
    // 全部 → 不传 used；未使用 → used=false
    const used = usedFilter.value === 'all' ? undefined : false
    const { data } = await listInvoices(currentPage.value - 1, pageSize.value, used, keyword.value.trim() || undefined)
    rows.value = data.content
    total.value = data.totalElements
    // 移动端卡片没有 el-table 的 selection-change 兜底，翻页/刷新后手动清空残留勾选
    selected.value = []
  } finally {
    loading.value = false
  }
}

function onPageChange(p) {
  currentPage.value = p
  load()
}

function onSizeChange() {
  currentPage.value = 1
  load()
}

// 金额列统一带人民币符号（解析失败为 null 时保持空白，与移动端卡片一致）
function withYuan(v) {
  const m = money(v)
  return m ? `¥${m}` : ''
}

function preview(row) {
  const idx = rows.value.findIndex(r => r.id === row.id)
  pdfPreviewRef.value?.open(rows.value, idx)
}

async function download(row) {
  downloadingId.value = row.id
  try {
    const { blob } = await fetchFile(row.id, 'download')
    saveBlob(blob, `${row.invoiceNumber}.pdf`)
  } catch {
    ElMessage.error('下载失败')
  } finally {
    downloadingId.value = null
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除发票 ${row.invoiceNumber}？该 PDF 文件将一并删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteInvoice(row.id)
    ElMessage.success('删除成功')
    // 已删行若在勾选集中，load 后会残留幽灵选中（发给后端会被容忍过滤，但保持干净）
    clearSelection()
    if (rows.value.length === 1 && currentPage.value > 1) currentPage.value--
    await load()
  } catch (e) {
    ElMessage.error(await errorMessage(e, '删除失败'))
  }
}

onMounted(load)

watch(usedFilter, () => {
  currentPage.value = 1
  load()
})

// 搜索防抖
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    currentPage.value = 1
    load()
  }, 300)
})
</script>

<style scoped>
:deep(.el-card__body) {
  padding: 16px;
  position: relative;
}
.batch-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.list-search {
  width: 240px;
}
.batch-summary {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
/* 价税合计列加重，扫视时一眼锁定总额 */
:deep(.el-table .col-strong .cell) {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
/* 表格横向溢出时右侧渐变遮罩，提示用户可滚动查看更多列 */
.table-scroll-hint {
  display: none;
}
@media (max-width: 1400px) {
  .table-scroll-hint {
    display: block;
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    width: 40px;
    background: linear-gradient(to left, var(--el-bg-color) 0%, transparent 100%);
    pointer-events: none;
    z-index: 2;
  }
}
@media (max-width: 768px) {
  .list-search {
    width: 100%;
  }
  .batch-actions {
    justify-content: space-between;
  }
  .batch-actions .batch-export-btn {
    flex: 1;
  }
}

/* ===== 移动端卡片列表 ===== */
.inv-card {
  padding: 12px;
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  margin-bottom: 10px;
  cursor: pointer;
  transition: background-color 0.2s ease;
}
.inv-card:active {
  background: var(--el-fill-color);
}
.inv-card-top {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.inv-card-top .el-checkbox {
  height: auto;
  margin-top: 2px;
}
.inv-card-main {
  flex: 1;
  min-width: 0;
}
.inv-card-seller {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inv-card-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inv-card-side {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}
.inv-card-amount {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}
.inv-card-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
  padding-left: 30px;
}
.inv-card-category {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inv-card-actions {
  flex-shrink: 0;
}
.inv-card-actions .el-button + .el-button {
  margin-left: 8px;
}
.export-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.export-field {
  display: flex;
  align-items: center;
  gap: 12px;
}
.export-label {
  width: 64px;
  color: var(--el-text-color-regular);
}
.export-summary {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
