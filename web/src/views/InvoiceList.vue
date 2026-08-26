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
    </div>
    <el-table :data="rows" v-loading="loading" stripe empty-text="暂无发票，上传 PDF 后在这里查看">
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
    <iframe :src="previewUrl" class="preview-frame" />
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listInvoices, deleteInvoice, fetchFile, errorMessage } from '../api/invoice'

const rows = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20
const loading = ref(false)
const previewVisible = ref(false)
const previewUrl = ref('')
const previewTitle = ref('')

async function load() {
  loading.value = true
  try {
    const { data } = await listInvoices(currentPage.value - 1, pageSize)
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
  // 替换前先 revoke，避免连续预览时上一次的 blob 仍驻留内存
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  try {
    const { url } = await fetchFile(row.id, 'inline')
    previewUrl.value = url
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
</style>
