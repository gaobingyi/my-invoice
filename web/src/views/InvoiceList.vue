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
        <el-switch v-model="showUsed" inline-prompt active-text="已使用" inactive-text="已使用" size="large" />
      </div>
      <div class="batch-actions">
        <span v-if="selected.length" class="batch-summary">已选 {{ selected.length }} 张 · ¥{{ selectedTotal }}</span>
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
      <div v-if="!rows.length" class="card-empty">暂无发票，上传 PDF 后在这里查看</div>
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
            <div class="inv-card-amount">¥{{ row.totalWithTax }}</div>
            <el-tag size="small" :type="row.used ? 'warning' : 'success'">
              {{ row.used ? '已使用' : '未使用' }}
            </el-tag>
          </div>
        </div>
        <div class="inv-card-bottom">
          <span class="inv-card-category">{{ row.category || '—' }}</span>
          <span class="inv-card-actions">
            <el-button link type="primary" @click.stop="preview(row)">预览</el-button>
            <el-button link @click.stop="download(row)">下载</el-button>
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
      empty-text="暂无发票，上传 PDF 后在这里查看"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="42" :selectable="row => !row.used" />
      <el-table-column prop="invoiceNumber" label="发票号码" width="220" />
      <el-table-column prop="invoiceDate" label="开票日期" width="120" />
      <el-table-column prop="sellerName" label="销售方" min-width="180" show-overflow-tooltip />
      <el-table-column prop="buyerName" label="购买方" min-width="180" show-overflow-tooltip />
      <el-table-column prop="category" label="项目名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="totalAmount" label="金额" width="100" align="right">
        <template #default="{ row }">{{ withYuan(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column prop="taxAmount" label="税额" width="100" align="right">
        <template #default="{ row }">{{ withYuan(row.taxAmount) }}</template>
      </el-table-column>
      <el-table-column prop="totalWithTax" label="价税合计" width="120" align="right">
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
          <el-button link @click="download(row)">下载</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="table-scroll-hint" aria-hidden="true"></div>

    <el-pagination
      v-if="total > 0"
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="currentPage"
      @current-change="onPageChange"
    />
  </el-card>

  <el-dialog v-model="previewVisible" :title="previewTitle" :width="isMobile ? '100%' : '70%'" :top="isMobile ? '0' : '5vh'" destroy-on-close @closed="onPreviewClosed">
    <div class="preview-nav">
      <el-button :icon="ArrowLeft" circle :disabled="!hasPrev" @click="goPreview(-1)" title="上一张" />
      <span class="preview-nav-label">{{ previewNavLabel }}</span>
      <el-button :icon="ArrowRight" circle :disabled="!hasNext" @click="goPreview(1)" title="下一张" />
    </div>
    <!-- 桌面：iframe 走浏览器原生查看器；移动端：pdf.js 内嵌渲染（移动浏览器 iframe 不渲染 PDF，
         丢给系统查看器的体验与下载无异） -->
    <iframe v-if="!isMobile" :src="previewUrl" class="preview-frame" />
    <div v-else class="pdf-embed" v-loading="!pdfRendered" element-loading-text="加载中">
      <VuePdfEmbed
        v-if="previewUrl"
        :source="previewUrl"
        @rendered="pdfRendered = true"
        @rendering-failed="onPdfRenderError"
      />
    </div>
  </el-dialog>

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
      <div class="export-summary">已选 {{ selected.length }} 张 · 价税合计 ¥{{ selectedTotal }}</div>
    </div>
    <template #footer>
      <el-button @click="exportVisible = false">取消</el-button>
      <el-button type="primary" :loading="exporting" @click="submitExport">创建批次</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted, watch, defineAsyncComponent } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { listInvoices, deleteInvoice, fetchFile, errorMessage, createExportBatch } from '../api/invoice'
import { useIsMobile } from '../composables/useIsMobile'

// pdf.js 体积大，异步进 chunk，只有移动端预览真正用到
const VuePdfEmbed = defineAsyncComponent(() => import('vue-pdf-embed'))

const router = useRouter()
const isMobile = useIsMobile()

const rows = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20
const loading = ref(false)
const showUsed = ref(false)
const previewVisible = ref(false)
const previewUrl = ref('')
const previewTitle = ref('')
const previewIndex = ref(-1)
const pdfRendered = ref(false)
const hasPrev = computed(() => previewIndex.value > 0)
const hasNext = computed(() => previewIndex.value >= 0 && previewIndex.value < rows.value.length - 1)
const previewNavLabel = computed(() =>
  previewIndex.value >= 0 ? `${previewIndex.value + 1} / ${rows.value.length}` : ''
)

// ===== 批量导出 =====
const tableRef = ref(null)
const selected = ref([])
const exportVisible = ref(false)
const exporting = ref(false)
// 默认当前月（value-format YYYY-MM）
function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
const exportMonth = ref(currentMonth())

const selectedTotal = computed(() =>
  selected.value.reduce((acc, r) => acc + (parseFloat(r.totalWithTax) || 0), 0).toFixed(2)
)

function onSelectionChange(sel) {
  // load() 整体替换 rows 也会触发本回调：翻页即清空选择（不做跨页保留，简单可预期）
  selected.value = sel
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
    await load()
    // 已使用的票刷新后 selectable 变 false，残留选中态一并清掉
    tableRef.value?.clearSelection?.()
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
    // 开关开启 → 显示全部（不传 used）；关闭 → 只看未使用
    const used = showUsed.value ? undefined : false
    const { data } = await listInvoices(currentPage.value - 1, pageSize, used)
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

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// 金额列统一带人民币符号（解析失败为 null 时保持空白，与移动端卡片一致）
function withYuan(v) {
  return v == null || v === '' ? '' : `¥${v}`
}

async function preview(row) {
  const idx = rows.value.findIndex(r => r.id === row.id)
  await loadPreview(row, idx)
}

function onPdfRenderError() {
  pdfRendered.value = true
  ElMessage.error('PDF 渲染失败')
}

async function goPreview(delta) {
  const idx = previewIndex.value + delta
  if (idx < 0 || idx >= rows.value.length) return
  await loadPreview(rows.value[idx], idx)
}

async function loadPreview(row, idx) {
  // 替换前先 revoke，避免连续预览时上一次的 blob 仍驻留内存
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  pdfRendered.value = false
  try {
    const { url } = await fetchFile(row.id, 'inline')
    previewUrl.value = url
    previewIndex.value = idx
    previewTitle.value = `发票预览 - ${row.invoiceNumber}`
    previewVisible.value = true
  } catch {
    ElMessage.error('加载预览失败')
  }
}

function onPreviewClosed() {
  // destroy-on-close 销毁 iframe，但 blob URL 需手动 revoke 才释放 PDF 字节
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
  previewIndex.value = -1
}

async function download(row) {
  try {
    const { url } = await fetchFile(row.id, 'download')
    const a = document.createElement('a')
    a.href = url
    a.download = `${row.invoiceNumber}.pdf`
    a.click()
    // a.click() 仅异步排队下载，立即释放 blob 可能让浏览器取到 0 字节；
    // 延迟 revoke，给下载流启动留出时间。
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    ElMessage.error('下载失败')
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
    tableRef.value?.clearSelection?.()
    if (rows.value.length === 1 && currentPage.value > 1) currentPage.value--
    await load()
  } catch (e) {
    ElMessage.error(await errorMessage(e, '删除失败'))
  }
}

onMounted(load)

watch(showUsed, () => {
  currentPage.value = 1
  load()
})
</script>

<style scoped>
:deep(.main > div > .el-card) {
  width: 100%;
}
:deep(.el-card__body) {
  padding: 16px;
  position: relative;
}
.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.list-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.list-title .el-tag {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  border-color: transparent;
  font-weight: 500;
}
.list-refresh { margin-left: 4px; }
.batch-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.batch-actions .el-radio-group {
  margin-right: 4px;
}
:deep(.list-title .el-switch) {
  transform: scale(1.1);
  transform-origin: left center;
  margin-left: 8px;
}
/* 开关 inline-prompt 文字：inactive 灰底上白色文字看不清，改深色 */
:deep(.list-title .el-switch:not(.is-checked) .el-switch__inner-wrapper) {
  color: var(--el-text-color-regular);
}
.batch-summary {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
:deep(.el-table) {
  --el-table-header-bg-color: var(--el-fill-color-lighter);
  border-radius: 8px;
}
:deep(.el-table th.el-table__cell) {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
:deep(.el-table .cell) {
  padding: 0 12px;
}
/* 操作列按钮间距 */
:deep(.el-table .el-button + .el-button) {
  margin-left: 4px;
}
/* 表格横向溢出时右侧渐变遮罩，提示用户可滚动查看更多列 */
:deep(.el-table) {
  position: relative;
}
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
/* 窄屏：分页居中；768 与其他处对齐见 src/styles/tokens.css */
@media (max-width: 768px) {
  .pager {
    justify-content: center;
  }
  /* 工具栏改为上下两行：标题行 + 批量操作行 */
  .list-toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .list-title {
    flex-wrap: wrap;
  }
  .batch-actions {
    justify-content: space-between;
  }
  .batch-actions .batch-export-btn {
    flex: 1;
  }
}

/* ===== 移动端卡片列表 ===== */
.card-list {
  min-height: 200px;
}
.card-empty {
  padding: 48px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}
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
.preview-frame {
  width: 100%;
  height: 70vh;
  border: none;
  border-radius: 8px;
  box-shadow: var(--shadow-iframe);
  background: var(--el-fill-color-lighter);
}
/* 移动端 pdf.js 内嵌预览：整页渲染后按容器宽度缩放，纵向滚动 + 手势缩放 */
.pdf-embed {
  height: 75vh;
  overflow: auto;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.pdf-embed :deep(.vue-pdf-embed) {
  margin: 0 auto;
}
.pdf-embed :deep(canvas) {
  display: block;
  width: 100% !important;
  height: auto !important;
}
.pdf-embed :deep(.vue-pdf-embed > div) {
  margin-bottom: 8px;
}
.preview-nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 10px;
}
.preview-nav-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  min-width: 60px;
  text-align: center;
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
