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
      </div>
      <div class="batch-actions">
        <el-switch v-model="showUsed" inline-prompt active-text="已使用" inactive-text="已使用" size="large" />
        <span v-if="selected.length" class="batch-summary">已选 {{ selected.length }} 张 · ¥{{ selectedTotal }}</span>
        <el-button
          class="batch-export-btn"
          type="primary"
          :disabled="!selected.length"
          @click="openExportDialog"
        >打包下载</el-button>
      </div>
    </div>
    <el-table
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
      <el-table-column prop="totalAmount" label="金额" width="100" align="right" />
      <el-table-column prop="taxAmount" label="税额" width="100" align="right" />
      <el-table-column prop="totalWithTax" label="价税合计" width="120" align="right" />
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
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="preview(row)">预览</el-button>
          <el-button link @click="download(row)">下载</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="currentPage"
      @current-change="onPageChange"
    />
  </el-card>

  <el-dialog v-model="previewVisible" :title="previewTitle" width="70%" top="5vh" destroy-on-close @closed="onPreviewClosed">
    <div class="preview-nav">
      <el-button :icon="ArrowLeft" circle :disabled="!hasPrev" @click="goPreview(-1)" title="上一张" />
      <span class="preview-nav-label">{{ previewNavLabel }}</span>
      <el-button :icon="ArrowRight" circle :disabled="!hasNext" @click="goPreview(1)" title="下一张" />
    </div>
    <iframe :src="previewUrl" class="preview-frame" />
  </el-dialog>

  <el-dialog v-model="exportVisible" title="创建导出批次" width="420px">
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
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { listInvoices, deleteInvoice, fetchFile, errorMessage, createExportBatch } from '../api/invoice'

const router = useRouter()

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

async function preview(row) {
  const idx = rows.value.findIndex(r => r.id === row.id)
  await loadPreview(row, idx)
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
.list-refresh { margin-left: 4px; }
.batch-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.batch-actions .el-radio-group {
  margin-right: 4px;
}
:deep(.batch-actions .el-switch) {
  transform: scale(1.1);
  transform-origin: left center;
  margin-right: 4px;
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
/* 窄屏：分页居中；768 与其他处对齐见 src/styles/tokens.css */
@media (max-width: 768px) {
  .pager {
    justify-content: center;
  }
}
.preview-frame {
  width: 100%;
  height: 70vh;
  border: none;
  border-radius: 8px;
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
  gap: 14px;
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
