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
      <el-empty v-if="!rows.length" description="暂无导出批次">
        <el-button type="primary" @click="router.push('/list')">去勾选发票</el-button>
      </el-empty>
      <div v-for="row in rows" :key="row.id" class="batch-card">
        <div class="batch-card-top" @click="openPreview(row)">
          <div class="batch-card-month">{{ row.batchMonth }}</div>
          <div class="batch-card-total">¥{{ money(row.totalWithTax) }}</div>
        </div>
        <div class="batch-card-meta">{{ row.invoiceCount }} 张 · 导出于 {{ formatTime(row.createdAt).slice(0, 16) }}</div>
        <div class="batch-card-actions">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
          <el-button link type="primary" :loading="downloadingId === row.id" @click="download(row)">下载</el-button>
          <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
        </div>
      </div>
    </div>
    <el-table v-else :data="rows" v-loading="loading" stripe>
      <template #empty>
        <el-empty description="暂无导出批次，在发票列表勾选后创建">
          <el-button type="primary" @click="router.push('/list')">去勾选发票</el-button>
        </el-empty>
      </template>
      <el-table-column prop="batchMonth" label="批次月份" width="120" />
      <el-table-column prop="invoiceCount" label="张数" width="90" align="right" />
      <el-table-column prop="totalWithTax" label="价税合计" width="140" align="right" class-name="col-strong">
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
          <el-button link type="primary" :loading="downloadingId === row.id" @click="download(row)">下载</el-button>
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
    >
      <div v-loading="previewLoading">
        <div class="preview-summary" v-if="previewRows.length">
          共 {{ previewRows.length }} 张 · 价税合计 ¥{{ money(previewTotal) }}
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
              <span class="m-row-amount">¥{{ money(row.totalWithTax) }}</span>
              <el-tag v-if="row.used" size="small" type="warning">已使用</el-tag>
            </div>
          </div>
        </div>
        <el-table v-else :data="previewRows" stripe max-height="55vh" empty-text="暂无明细">
          <el-table-column prop="invoiceNumber" label="发票号码" min-width="200">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="previewPdf(row)">{{ row.invoiceNumber }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="invoiceDate" label="开票日期" width="110" />
          <el-table-column prop="sellerName" label="销售方" min-width="170" show-overflow-tooltip />
          <el-table-column prop="totalAmount" label="金额" width="110" align="right">
            <template #default="{ row }">{{ withYuan(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column prop="totalWithTax" label="价税合计" width="120" align="right">
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
          共 {{ editRows.length }} 张 · 价税合计 ¥{{ money(editTotal) }}
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
              <span class="m-row-amount">¥{{ money(row.totalWithTax) }}</span>
              <el-button link type="danger" :disabled="editLoading" @click="removeInvoice(row)">移除</el-button>
            </div>
          </div>
        </div>
        <el-table v-else :data="editRows" stripe max-height="32vh" empty-text="批次为空，请从下方添加发票">
          <el-table-column prop="invoiceNumber" label="发票号码" min-width="200" />
          <el-table-column prop="invoiceDate" label="开票日期" width="110" />
          <el-table-column prop="sellerName" label="销售方" min-width="170" show-overflow-tooltip />
          <el-table-column prop="totalWithTax" label="价税合计" width="120" align="right">
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
                <div class="m-opt-no">{{ inv.invoiceNumber }} · ¥{{ money(inv.totalWithTax) }}</div>
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

    <PdfPreviewDialog ref="pdfPreviewRef" />

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
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  listExportBatches,
  listBatchInvoices,
  addBatchInvoices,
  removeBatchInvoice,
  fetchBatchZip,
  deleteExportBatch,
  listAllUnusedInvoices,
  errorMessage
} from '../api/invoice'
import { useIsMobile } from '../composables/useIsMobile'
import { formatTime, money } from '../utils/format'
import { saveBlob } from '../utils/download'
import PdfPreviewDialog from '../components/PdfPreviewDialog.vue'

const router = useRouter()
const isMobile = useIsMobile()

const rows = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20
const loading = ref(false)
const downloadingId = ref(null)

// ===== 批次预览 =====
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewRows = ref([])
const previewBatch = ref(null)
// PDF 预览（发票号码点击进入），状态全部在 PdfPreviewDialog 内部
const pdfPreviewRef = ref(null)

const previewTitle = computed(() =>
  previewBatch.value ? `批次预览 - ${previewBatch.value.batchMonth}（${previewBatch.value.invoiceCount} 张）` : '批次预览'
)
const previewTotal = computed(() =>
  previewRows.value.reduce((acc, r) => acc + (parseFloat(r.totalWithTax) || 0), 0)
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

function previewPdf(row) {
  const idx = previewRows.value.findIndex(r => r.id === row.id)
  pdfPreviewRef.value?.open(previewRows.value, idx)
}

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
  editRows.value.reduce((acc, r) => acc + (parseFloat(r.totalWithTax) || 0), 0)
)

function invoiceLabel(inv) {
  return `${inv.invoiceNumber} · ${inv.sellerName || '未知销售方'} · ¥${money(inv.totalWithTax)}`
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
    await reloadEditRows(batch.id)
  } catch (e) {
    ElMessage.error(await errorMessage(e, '加载批次明细失败'))
    editVisible.value = false
  } finally {
    editLoading.value = false
  }
}

async function reloadEditRows(batchId) {
  // 添加/移除都会改变发票的 used 状态：批次明细和待选列表（未使用发票）必须一起刷新，
  // 否则移除的发票不会出现在待选里、已添加的仍留在待选中。
  // 待选列表翻页取全量（后端单页上限 100，硬编码单页会漏掉第 101 张之后的票）。
  const [invoicesRes, available] = await Promise.all([
    listBatchInvoices(batchId),
    listAllUnusedInvoices()
  ])
  editRows.value = invoicesRes.data
  availableInvoices.value = available
}

async function addSelected() {
  adding.value = true
  try {
    const { data } = await addBatchInvoices(editBatch.value.id, selectedIds.value)
    applyBatch(data)
    selectedIds.value = []
    await reloadEditRows(editBatch.value.id)
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
    await reloadEditRows(editBatch.value.id)
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

// 金额列统一带人民币符号（解析失败为 null 时保持空白，与移动端卡片一致）
function withYuan(v) {
  const m = money(v)
  return m ? `¥${m}` : ''
}

async function download(row) {
  downloadingId.value = row.id
  try {
    const blob = await fetchBatchZip(row.id)
    saveBlob(blob, `发票导出_${row.batchMonth}_${row.id}.zip`)
  } catch (e) {
    ElMessage.error(await errorMessage(e, '下载失败'))
  } finally {
    downloadingId.value = null
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
:deep(.el-card__body) {
  padding: 16px;
}
/* 价税合计列加重 */
:deep(.el-table .col-strong .cell) {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

/* ===== 移动端批次卡片 ===== */
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
@media (max-width: 768px) {
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
