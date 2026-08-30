<template>
  <el-card shadow="never">
    <div class="list-toolbar">
      <div class="list-title">
        <span class="title-dot"></span>
        <span class="page-title">导出记录</span>
        <el-tag size="small" type="info" round effect="plain">共 {{ total }} 批</el-tag>
        <el-button
          class="list-refresh"
          :icon="Refresh"
          circle
          :loading="loading"
          title="刷新"
          @click="load"
        />
      </div>
    </div>
    <!-- 移动端：批次卡片列表 -->
    <div v-if="isMobile" v-loading="loading" class="card-list">
      <div v-if="!rows.length" class="card-empty">暂无导出批次，在发票列表勾选后创建</div>
      <div v-for="row in rows" :key="row.id" class="batch-card">
        <div class="batch-card-top" @click="openPreview(row)">
          <div class="batch-card-month">{{ row.batchMonth }}</div>
          <div class="batch-card-total">¥{{ row.totalWithTax }}</div>
        </div>
        <div class="batch-card-meta">{{ row.invoiceCount }} 张 · 导出于 {{ formatTime(row.createdAt).slice(0, 16) }}</div>
        <div class="batch-card-actions">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
          <el-button link type="primary" @click="download(row)">下载</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </div>
      </div>
    </div>
    <el-table v-else :data="rows" v-loading="loading" stripe empty-text="暂无导出批次，在发票列表勾选后创建">
      <el-table-column prop="batchMonth" label="批次月份" width="120" />
      <el-table-column prop="invoiceCount" label="张数" width="90" align="right" />
      <el-table-column prop="totalWithTax" label="价税合计" width="130" align="right">
        <template #default="{ row }">{{ withYuan(row.totalWithTax) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="导出时间" width="180">
        <template #default="{ row }">
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
          <el-button link type="primary" @click="download(row)">下载</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="previewVisible"
      :title="previewTitle"
      width="min(860px, 94%)"
      top="6vh"
      destroy-on-close
      @closed="onPreviewClosed"
    >
      <div v-loading="previewLoading">
        <div class="preview-summary" v-if="previewRows.length">
          共 {{ previewRows.length }} 张 · 价税合计 ¥{{ previewTotal }}
        </div>
        <!-- 移动端：批次明细行列表 -->
        <div v-if="isMobile" v-loading="previewLoading" class="m-batch-rows">
          <div v-if="!previewRows.length" class="card-empty">暂无明细</div>
          <div v-for="row in previewRows" :key="row.id" class="m-batch-row" @click="previewPdf(row)">
            <div class="m-row-main">
              <div class="m-row-no">{{ row.invoiceNumber }}</div>
              <div class="m-row-sub">{{ row.sellerName || '未知销售方' }} · {{ row.invoiceDate }}</div>
            </div>
            <div class="m-row-side">
              <span class="m-row-amount">¥{{ row.totalWithTax }}</span>
              <el-tag v-if="row.used" size="small" type="warning">已使用</el-tag>
            </div>
          </div>
        </div>
        <el-table v-else :data="previewRows" stripe max-height="55vh">
          <el-table-column prop="invoiceNumber" label="发票号码" min-width="200">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="previewPdf(row)">{{ row.invoiceNumber }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="invoiceDate" label="开票日期" width="110" />
          <el-table-column prop="sellerName" label="销售方" min-width="170" show-overflow-tooltip />
          <el-table-column prop="totalAmount" label="金额" width="100" align="right">
            <template #default="{ row }">{{ withYuan(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column prop="totalWithTax" label="价税合计" width="110" align="right">
            <template #default="{ row }">{{ withYuan(row.totalWithTax) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.used" size="small" type="warning">已使用</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog
      v-model="editVisible"
      :title="editTitle"
      width="min(860px, 94%)"
      top="6vh"
      destroy-on-close
    >
      <div v-loading="editLoading">
        <div class="preview-summary">
          共 {{ editRows.length }} 张 · 价税合计 ¥{{ editTotal }}
        </div>
        <!-- 移动端：编辑批次行列表（点移除走同一 confirm 流程） -->
        <div v-if="isMobile" class="m-batch-rows">
          <div v-if="!editRows.length" class="card-empty">批次为空，请从下方添加发票</div>
          <div v-for="row in editRows" :key="row.id" class="m-batch-row">
            <div class="m-row-main">
              <div class="m-row-no">{{ row.invoiceNumber }}</div>
              <div class="m-row-sub">{{ row.sellerName || '未知销售方' }} · {{ row.invoiceDate }}</div>
            </div>
            <div class="m-row-side">
              <span class="m-row-amount">¥{{ row.totalWithTax }}</span>
              <el-button link type="danger" :disabled="editLoading" @click="removeInvoice(row)">移除</el-button>
            </div>
          </div>
        </div>
        <el-table v-else :data="editRows" stripe max-height="32vh" empty-text="批次为空，请从下方添加发票">
          <el-table-column prop="invoiceNumber" label="发票号码" min-width="200" />
          <el-table-column prop="invoiceDate" label="开票日期" width="110" />
          <el-table-column prop="sellerName" label="销售方" min-width="170" show-overflow-tooltip />
          <el-table-column prop="totalWithTax" label="价税合计" width="110" align="right">
            <template #default="{ row }">{{ withYuan(row.totalWithTax) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="danger" :disabled="editLoading" @click="removeInvoice(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="edit-add">
          <el-select
            v-model="selectedIds"
            multiple
            filterable
            placeholder="选择要添加的未使用发票"
            class="edit-add-select"
            popper-class="edit-add-popper"
          >
            <el-option
              v-for="inv in availableInvoices"
              :key="inv.id"
              :value="inv.id"
              :label="invoiceLabel(inv)"
            >
              <!-- 移动端两行显示：号码+金额 / 销售方，长销售方名截断；
                   选中后的 tag 仍用 label（完整文案，宽度由下方 CSS 约束） -->
              <template v-if="isMobile">
                <div class="m-opt-no">{{ inv.invoiceNumber }} · ¥{{ inv.totalWithTax }}</div>
                <div class="m-opt-sub">{{ inv.sellerName || '未知销售方' }}</div>
              </template>
              <template v-else>{{ invoiceLabel(inv) }}</template>
            </el-option>
          </el-select>
          <el-button type="primary" :disabled="!selectedIds.length" :loading="adding" @click="addSelected">
            添加选中
          </el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog
      v-model="pdfVisible"
      :title="pdfTitle"
      :width="isMobile ? '100%' : '70%'"
      :top="isMobile ? '0' : '4vh'"
      destroy-on-close
      @closed="onPdfClosed"
    >
      <div class="pdf-nav">
        <el-button :icon="ArrowLeft" circle :disabled="!hasPrev" @click="goPdf(-1)" title="上一张" />
        <span class="pdf-nav-label">{{ pdfNavLabel }}</span>
        <el-button :icon="ArrowRight" circle :disabled="!hasNext" @click="goPdf(1)" title="下一张" />
      </div>
      <!-- 桌面 iframe 原生查看器；移动端 pdf.js 内嵌渲染（移动浏览器 iframe 不渲染 PDF） -->
      <iframe v-if="!isMobile" :src="pdfUrl" class="pdf-frame" />
      <div v-else class="pdf-embed" v-loading="!pdfRendered" element-loading-text="加载中">
        <VuePdfEmbed
          v-if="pdfUrl"
          :source="pdfUrl"
          @rendered="pdfRendered = true"
          @rendering-failed="onPdfRenderError"
        />
      </div>
    </el-dialog>

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
</template>

<script setup>
import { ref, computed, onMounted, defineAsyncComponent } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import {
  listExportBatches,
  listBatchInvoices,
  addBatchInvoices,
  removeBatchInvoice,
  fetchBatchZip,
  deleteExportBatch,
  listInvoices,
  fetchFile,
  errorMessage
} from '../api/invoice'
import { useIsMobile } from '../composables/useIsMobile'

const isMobile = useIsMobile()

// pdf.js 体积大，异步进 chunk，只有移动端预览真正用到
const VuePdfEmbed = defineAsyncComponent(() => import('vue-pdf-embed'))

const rows = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20
const loading = ref(false)

// ===== 批次预览 =====
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewRows = ref([])
const previewBatch = ref(null)
// PDF 预览（发票号码点击进入，blob URL 需手动 revoke）
const pdfVisible = ref(false)
const pdfUrl = ref('')
const pdfTitle = ref('')
const pdfIndex = ref(-1)
const pdfRendered = ref(false)
const hasPrev = computed(() => pdfIndex.value > 0)
const hasNext = computed(() => pdfIndex.value >= 0 && pdfIndex.value < previewRows.value.length - 1)
const pdfNavLabel = computed(() =>
  pdfIndex.value >= 0 ? `${pdfIndex.value + 1} / ${previewRows.value.length}` : ''
)

const previewTitle = computed(() =>
  previewBatch.value ? `批次预览 - ${previewBatch.value.batchMonth}（${previewBatch.value.invoiceCount} 张）` : '批次预览'
)
const previewTotal = computed(() =>
  previewRows.value.reduce((acc, r) => acc + (parseFloat(r.totalWithTax) || 0), 0).toFixed(2)
)

async function openPreview(batch) {
  previewBatch.value = batch
  previewRows.value = []
  previewVisible.value = true
  previewLoading.value = true
  try {
    const { data } = await listBatchInvoices(batch.id)
    previewRows.value = data
  } catch (e) {
    ElMessage.error(await errorMessage(e, '加载批次明细失败'))
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

async function previewPdf(row) {
  const idx = previewRows.value.findIndex(r => r.id === row.id)
  await loadPdf(row, idx)
}

async function goPdf(delta) {
  const idx = pdfIndex.value + delta
  if (idx < 0 || idx >= previewRows.value.length) return
  await loadPdf(previewRows.value[idx], idx)
}

async function loadPdf(row, idx) {
  // 替换前先 revoke，避免连续预览时上一次的 blob 驻留内存
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value)
  pdfUrl.value = ''
  pdfRendered.value = false
  pdfUrl.value = ''
  try {
    const { url } = await fetchFile(row.id, 'inline')
    pdfUrl.value = url
    pdfIndex.value = idx
    pdfTitle.value = `发票预览 - ${row.invoiceNumber}`
    pdfVisible.value = true
  } catch {
    ElMessage.error('加载预览失败')
  }
}

function onPdfClosed() {
  if (pdfUrl.value) {
    URL.revokeObjectURL(pdfUrl.value)
    pdfUrl.value = ''
  }
  pdfIndex.value = -1
}

function onPdfRenderError() {
  pdfRendered.value = true
  ElMessage.error('PDF 渲染失败')
}

function onPreviewClosed() {}

// ===== 批次编辑（添加/移除发票） =====
const editVisible = ref(false)
const editLoading = ref(false)
const editRows = ref([])
const editBatch = ref(null)
const availableInvoices = ref([])
const selectedIds = ref([])
const adding = ref(false)

const editTitle = computed(() =>
  editBatch.value ? `编辑批次 - ${editBatch.value.batchMonth}` : '编辑批次'
)
const editTotal = computed(() =>
  editRows.value.reduce((acc, r) => acc + (parseFloat(r.totalWithTax) || 0), 0).toFixed(2)
)

function invoiceLabel(inv) {
  return `${inv.invoiceNumber} · ${inv.sellerName || '未知销售方'} · ¥${inv.totalWithTax}`
}

/** 编辑返回的批次就地更新列表行（张数/价税合计），不整页刷新。 */
function applyBatch(updated) {
  if (!updated) return
  editBatch.value = updated
  const row = rows.value.find(r => r.id === updated.id)
  if (row) {
    row.invoiceCount = updated.invoiceCount
    row.totalWithTax = updated.totalWithTax
  }
}

async function openEdit(batch) {
  editBatch.value = batch
  editRows.value = []
  selectedIds.value = []
  availableInvoices.value = []
  editVisible.value = true
  editLoading.value = true
  try {
    // 添加候选 = 当前未使用的发票（已使用 = 已在其他批次，不可重复打包）
    const [invoicesRes, availableRes] = await Promise.all([
      listBatchInvoices(batch.id),
      listInvoices(0, 100, false)
    ])
    editRows.value = invoicesRes.data
    availableInvoices.value = availableRes.data.content
  } catch (e) {
    ElMessage.error(await errorMessage(e, '加载批次明细失败'))
    editVisible.value = false
  } finally {
    editLoading.value = false
  }
}

async function reloadEditRows() {
  // 添加/移除都会改变发票的 used 状态：批次明细和待选列表（未使用发票）必须一起刷新，
  // 否则移除的发票不会出现在待选里、已添加的仍留在待选中
  const [invoicesRes, availableRes] = await Promise.all([
    listBatchInvoices(editBatch.value.id),
    listInvoices(0, 100, false)
  ])
  editRows.value = invoicesRes.data
  availableInvoices.value = availableRes.data.content
}

async function addSelected() {
  adding.value = true
  try {
    const { data } = await addBatchInvoices(editBatch.value.id, selectedIds.value)
    applyBatch(data)
    selectedIds.value = []
    await reloadEditRows()
    ElMessage.success('添加成功')
  } catch (e) {
    ElMessage.error(await errorMessage(e, '添加失败'))
  } finally {
    adding.value = false
  }
}

async function removeInvoice(row) {
  try {
    await ElMessageBox.confirm(
      `确认从批次移除 ${row.invoiceNumber}？该发票将恢复为未使用。`,
      '移除确认',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const { data } = await removeBatchInvoice(editBatch.value.id, row.id)
    applyBatch(data)
    await reloadEditRows()
    ElMessage.success('移除成功')
  } catch (e) {
    ElMessage.error(await errorMessage(e, '移除失败'))
  }
}

async function load() {
  loading.value = true
  try {
    const { data } = await listExportBatches(currentPage.value - 1, pageSize)
    rows.value = data.content
    total.value = data.totalElements
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

/** blob → 隐藏 <a download> + 延迟 revoke（a.click() 仅异步排队下载，立即释放可能 0 字节） */
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function download(row) {
  try {
    const blob = await fetchBatchZip(row.id)
    saveBlob(blob, `发票导出_${row.batchMonth}_${row.id}.zip`)
  } catch (e) {
    ElMessage.error(await errorMessage(e, '下载失败'))
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除 ${row.batchMonth} 批次（${row.invoiceCount} 张）？未被其他批次引用的发票将恢复为未使用。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const { data } = await deleteExportBatch(row.id)
    ElMessage.success(data?.restored ? `删除成功，${data.restored} 张发票已恢复为未使用` : '删除成功')
    if (rows.value.length === 1 && currentPage.value > 1) currentPage.value--
    await load()
  } catch (e) {
    ElMessage.error(await errorMessage(e, '删除失败'))
  }
}

onMounted(load)
</script>

<style scoped>
:deep(.main > div > .el-card) {
  width: 100%;
}
:deep(.el-card__body) {
  padding: 16px;
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
:deep(.el-table .el-button + .el-button) {
  margin-left: 4px;
}
@media (max-width: 768px) {
  .pager {
    justify-content: center;
  }
  .list-toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  /* 编辑批次弹窗：多选与添加按钮改上下堆叠 */
  .edit-add {
    flex-direction: column;
    align-items: stretch;
  }
  .edit-add .el-button {
    width: 100%;
  }
  /* 长标签多选时保住右侧展开箭头：selection 收缩 + tag 不超出，suffix 不被挤走 */
  .edit-add :deep(.el-select__wrapper) {
    flex-wrap: nowrap;
  }
  .edit-add :deep(.el-select__selection) {
    min-width: 0;
    overflow: hidden;
  }
  .edit-add :deep(.el-select__selection .el-tag) {
    max-width: 100%;
  }
  .edit-add :deep(.el-select__suffix) {
    flex-shrink: 0;
  }
}

/* ===== 移动端批次卡片 ===== */
.card-list {
  min-height: 200px;
}
.card-empty {
  padding: 48px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}
.batch-card {
  padding: 12px;
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  margin-bottom: 10px;
}
.batch-card-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  cursor: pointer;
}
.batch-card-month {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.batch-card-total {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}
.batch-card-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.batch-card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 8px;
}
.batch-card-actions .el-button + .el-button {
  margin-left: 8px;
}

/* ===== 移动端弹窗内批次明细行 ===== */
.m-batch-rows {
  min-height: 80px;
  max-height: 46vh;
  overflow: auto;
}
.m-batch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.m-batch-row:last-child {
  border-bottom: none;
}
.m-row-main {
  flex: 1;
  min-width: 0;
}
.m-row-no {
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.m-row-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.m-row-side {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.m-row-amount {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}
.preview-summary {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.edit-add {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}
.edit-add-select {
  flex: 1;
}
.pdf-frame {
  width: 100%;
  height: 75vh;
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
.pdf-nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 10px;
}
.pdf-nav-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  min-width: 60px;
  text-align: center;
}
</style>

<!-- 待选下拉 teleport 到 body，scoped 样式够不到，需全局 -->
<style>
.edit-add-popper {
  max-width: 94vw;
}
.edit-add-popper .el-select-dropdown__item {
  height: auto;
  padding-top: 6px;
  padding-bottom: 6px;
  line-height: 1.4;
}
.edit-add-popper .m-opt-no {
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.edit-add-popper .m-opt-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
