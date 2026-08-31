<template>
  <!-- 发票 PDF 预览弹窗（InvoiceList / ExportBatchList 共用）。
       桌面 iframe 走浏览器原生查看器；移动端 vue-pdf-embed 内嵌渲染（移动浏览器 iframe 不渲染 PDF）。
       用法：ref.open(rows, index)，行需含 { id, invoiceNumber }；翻张基于传入数组。 -->
  <el-dialog
    v-model="visible"
    :title="title"
    :width="isMobile ? '100%' : '70%'"
    :top="isMobile ? '0' : '5vh'"
    destroy-on-close
    @closed="onClosed"
  >
    <div class="pdf-nav">
      <el-button :icon="ArrowLeft" circle :disabled="!hasPrev" @click="go(-1)" title="上一张（←）" />
      <span class="pdf-nav-label">{{ navLabel }}</span>
      <el-button :icon="ArrowRight" circle :disabled="!hasNext" @click="go(1)" title="下一张（→）" />
    </div>
    <iframe v-if="!isMobile" :src="url" class="pdf-frame" />
    <div v-else class="pdf-embed" v-loading="!rendered" element-loading-text="加载中">
      <VuePdfEmbed
        v-if="url"
        :source="url"
        @rendered="rendered = true"
        @rendering-failed="onRenderError"
      />
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount, defineAsyncComponent } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { fetchFile } from '../api/invoice'
import { useIsMobile } from '../composables/useIsMobile'

// pdf.js 体积大，异步进 chunk，只有移动端预览真正用到
const VuePdfEmbed = defineAsyncComponent(() => import('vue-pdf-embed'))

const isMobile = useIsMobile()

const visible = ref(false)
const url = ref('')
const title = ref('')
const rows = ref([])
const index = ref(-1)
const rendered = ref(false)

const hasPrev = computed(() => index.value > 0)
const hasNext = computed(() => index.value >= 0 && index.value < rows.value.length - 1)
const navLabel = computed(() =>
  index.value >= 0 ? `${index.value + 1} / ${rows.value.length}` : ''
)

defineExpose({
  /** 打开预览：rows 为当前列表（用于上一张/下一张导航），idx 为起始行下标 */
  open(rowsList, idx) {
    rows.value = rowsList
    loadRow(rowsList[idx], idx)
  }
})

async function loadRow(row, idx) {
  // 替换前先 revoke，避免连续预览时上一次的 blob 驻留内存
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''
  rendered.value = false
  try {
    const { url: u } = await fetchFile(row.id, 'inline')
    url.value = u
    index.value = idx
    title.value = `发票预览 - ${row.invoiceNumber}`
    visible.value = true
  } catch {
    ElMessage.error('加载预览失败')
  }
}

function go(delta) {
  const i = index.value + delta
  if (i < 0 || i >= rows.value.length) return
  loadRow(rows.value[i], i)
}

function onClosed() {
  // destroy-on-close 销毁 iframe，但 blob URL 需手动 revoke 才释放 PDF 字节
  if (url.value) {
    URL.revokeObjectURL(url.value)
    url.value = ''
  }
  index.value = -1
  rows.value = []
}

function onRenderError() {
  rendered.value = true
  ElMessage.error('PDF 渲染失败')
}

// 弹窗打开期间支持 ←/→ 键翻张
function onKey(e) {
  if (e.key === 'ArrowLeft') go(-1)
  else if (e.key === 'ArrowRight') go(1)
}
watch(visible, v => {
  if (v) window.addEventListener('keydown', onKey)
  else window.removeEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style scoped>
.pdf-frame {
  width: 100%;
  height: 72vh;
  border: none;
  border-radius: 8px;
  box-shadow: var(--shadow-iframe);
  background: var(--el-fill-color-lighter);
}
/* 移动端 pdf.js 内嵌预览：整页渲染后按容器宽度缩放，纵向滚动 + 手势缩放 */
.pdf-embed {
  height: 72vh;
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
