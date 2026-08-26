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
    <el-table :data="rows" v-loading="loading" stripe empty-text="暂无导出批次，在发票列表勾选后创建">
      <el-table-column prop="batchMonth" label="批次月份" width="120" />
      <el-table-column prop="invoiceCount" label="张数" width="90" align="right" />
      <el-table-column prop="totalWithTax" label="价税合计" width="130" align="right" />
      <el-table-column prop="createdAt" label="导出时间" width="180">
        <template #default="{ row }">
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
          <el-button link type="primary" @click="download(row)">下载</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="previewVisible"
      :title="previewTitle"
      width="860px"
      top="6vh"
      destroy-on-close
      @closed="onPreviewClosed"
    >
      <div v-loading="previewLoading">
        <div class="preview-summary" v-if="previewRows.length">
          共 {{ previewRows.length }} 张 · 价税合计 ¥{{ previewTotal }}
        </div>
        <el-table :data="previewRows" stripe max-height="55vh">
          <el-table-column prop="invoiceNumber" label="发票号码" min-width="200">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="previewPdf(row)">{{ row.invoiceNumber }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="invoiceDate" label="开票日期" width="110" />
          <el-table-column prop="sellerName" label="销售方" min-width="170" show-overflow-tooltip />
          <el-table-column prop="totalAmount" label="金额" width="100" align="right" />
          <el-table-column prop="totalWithTax" label="价税合计" width="110" align="right" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.used" size="small" type="warning">已使用</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog
      v-model="pdfVisible"
      :title="pdfTitle"
      width="70%"
      top="4vh"
      destroy-on-close
      @closed="onPdfClosed"
    >
      <div class="pdf-nav">
        <el-button :icon="ArrowLeft" circle :disabled="!hasPrev" @click="goPdf(-1)" title="上一张" />
        <span class="pdf-nav-label">{{ pdfNavLabel }}</span>
        <el-button :icon="ArrowRight" circle :disabled="!hasNext" @click="goPdf(1)" title="下一张" />
      </div>
      <iframe :src="pdfUrl" class="pdf-frame" />
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
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import {
  listExportBatches,
  listBatchInvoices,
  fetchBatchZip,
  deleteExportBatch,
  fetchFile,
  errorMessage
} from '../api/invoice'

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

function onPreviewClosed() {}

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
}
.preview-summary {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.pdf-frame {
  width: 100%;
  height: 75vh;
  border: none;
  border-radius: 8px;
  box-shadow: var(--shadow-iframe);
  background: var(--el-fill-color-lighter);
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
