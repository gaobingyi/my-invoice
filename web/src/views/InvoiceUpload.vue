<template>
  <el-card shadow="never">
    <div class="upload-head">
      <div class="upload-title">
        <span class="title-dot"></span>
        <span class="page-title">发票上传</span>
        <span class="upload-hint">支持 PDF 格式 · 单次最多 20 张 · 单张 ≤ 10MB</span>
      </div>
    </div>
    <div class="upload-body">
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        multiple
        :limit="20"
        :show-file-list="false"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
        :on-exceed="onExceed"
        accept="application/pdf"
      >
        <div class="upload-box">
          <div class="upload-icon">
            <el-icon><upload-filled /></el-icon>
          </div>
          <div class="upload-main">{{ isMobile ? '点击选择 PDF 文件' : '拖拽 PDF 到此处，或点击选择文件' }}</div>
          <div class="upload-sub">支持批量选择，选择后可预览再上传</div>
        </div>
      </el-upload>
      <div class="upload-files" v-if="files.length">
        <div class="file-row" v-for="f in files" :key="f.uid">
          <el-icon class="file-icon"><document /></el-icon>
          <span class="file-name">{{ f.name }}</span>
          <span class="file-size">{{ formatSize(f.size) }}</span>
          <!-- 逐文件状态：pending → uploading → success/failed，失败可重试 -->
          <el-tag v-if="f.status === 'uploading'" size="small">上传中…</el-tag>
          <el-tag v-else-if="f.status === 'success'" size="small" type="success">成功</el-tag>
          <template v-else-if="f.status === 'failed'">
            <el-tag size="small" type="danger">失败</el-tag>
            <el-button link type="primary" :disabled="uploading" @click="retryFile(f)">重试</el-button>
          </template>
          <el-icon class="file-remove" @click="removeFile(f)"><close /></el-icon>
        </div>
      </div>
      <div class="upload-actions">
        <el-button
          class="upload-btn"
          type="primary"
          size="large"
          :loading="uploading"
          :disabled="!files.length"
          @click="doUpload"
        >
          {{ uploading ? `上传中（${doneCount}/${files.length}）` : `上传发票（${files.length}）` }}
        </el-button>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled, Document, Close } from '@element-plus/icons-vue'
import { uploadInvoice, errorMessage } from '../api/invoice'
import { useIsMobile } from '../composables/useIsMobile'
import { formatSize } from '../utils/format'

const isMobile = useIsMobile()

const MAX_SIZE = 10 * 1024 * 1024 // 与后端单请求限制一致，选中时即拦截，免等 413

const uploading = ref(false)
const files = ref([])
const uploadRef = ref(null)

const doneCount = computed(() => files.value.filter(f => f.status === 'success').length)

function onFileChange(f) {
  if (files.value.some(x => x.uid === f.uid)) return
  if (!f.name.toLowerCase().endsWith('.pdf')) {
    ElMessage.warning(`${f.name} 不是 PDF 文件，已忽略`)
    uploadRef.value?.handleRemove(f)
    return
  }
  if ((f.size ?? 0) > MAX_SIZE) {
    ElMessage.warning(`${f.name} 超过 10MB，已忽略`)
    uploadRef.value?.handleRemove(f)
    return
  }
  f.status = 'pending'
  files.value.push(f)
}

function onFileRemove(f) {
  files.value = files.value.filter(x => x.uid !== f.uid)
}

// 自定义列表的删除：走 el-upload 的 handleRemove，让内部 list 同步、并触发 on-remove
function removeFile(f) {
  uploadRef.value?.handleRemove(f)
}

function onExceed() {
  ElMessage.warning('一次最多选 20 张（后端单请求限制 10MB，20 张超出请分批）')
}

const router = useRouter()

/** 上传单个文件并维护其状态，失败时保留在列表里供重试 */
async function uploadOne(f) {
  f.status = 'uploading'
  try {
    await uploadInvoice(f.raw)
    f.status = 'success'
    return true
  } catch (e) {
    f.status = 'failed'
    ElMessage.error(`${f.name} 上传失败: ${await errorMessage(e)}`)
    return false
  }
}

async function doUpload() {
  if (!files.value.length) return
  uploading.value = true
  try {
    // 逐张串行：后端按单文件解析落盘，串行让失败定位到具体文件、也避免并发写库锁
    for (const f of [...files.value]) {
      if (f.status === 'success') continue
      await uploadOne(f)
    }
    // 只清掉成功项，失败的留在列表里可查看/重试（走 handleRemove 同步 el-upload 内部列表）
    const ok = files.value.filter(x => x.status === 'success').length
    const failedCount = files.value.length - ok
    for (const f of files.value.filter(x => x.status === 'success')) {
      uploadRef.value?.handleRemove(f)
    }
    if (ok && !failedCount) {
      ElMessage.success(`成功 ${ok} 张`)
      router.push('/list')
    } else if (ok) {
      ElMessage.warning(`成功 ${ok} 张，失败 ${failedCount} 张（可单张重试）`)
    } else {
      ElMessage.error(`上传失败 ${failedCount} 张，请检查 PDF 是否损坏或已存在`)
    }
  } finally {
    uploading.value = false
  }
}

/** 单张重试：只传该文件 */
async function retryFile(f) {
  uploading.value = true
  try {
    const ok = await uploadOne(f)
    if (ok) {
      ElMessage.success(`${f.name} 上传成功`)
      uploadRef.value?.handleRemove(f)
      if (!files.value.length) router.push('/list')
    }
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
:deep(.main > div > .el-card) {
  height: 100%;
  display: flex;
  flex-direction: column;
  width: 100%;
}
:deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px;
  gap: 16px;
}
.upload-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.upload-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.upload-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.upload-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  justify-content: center;
  max-width: 720px;
  width: 100%;
  margin: 0 auto;
}
/* 拖拽区：虚线边框 + 悬停/拖入高亮 + 图标动效 */
.upload-body :deep(.el-upload) {
  display: block;
  width: 100%;
}
.upload-body :deep(.el-upload-dragger) {
  padding: 32px;
  border-radius: 12px;
  border: 2px dashed var(--el-border-color);
  background: var(--el-fill-color-lighter);
  transition: border-color 0.2s ease, background-color 0.2s ease, transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.2s ease;
}
.upload-body :deep(.el-upload-dragger:hover) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(79, 107, 245, 0.12);
}
.upload-body :deep(.el-upload-dragger.is-dragover) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
  transform: scale(1.01);
  box-shadow: 0 12px 32px rgba(79, 107, 245, 0.18);
}
.upload-body :deep(.el-upload-dragger:hover .el-icon) {
  transform: scale(1.1);
}
.upload-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px;
}
.upload-icon {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--brand-gradient);
  color: #fff;
  margin-bottom: 4px;
}
.upload-icon .el-icon {
  font-size: 30px;
}
.upload-body :deep(.el-icon) {
  transition: transform 0.2s ease;
}
.upload-main {
  font-size: 15px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.upload-sub {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.upload-files {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 160px;
  overflow: auto;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
}
.file-icon {
  color: var(--el-color-primary);
}
.file-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}
.file-size {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  margin-right: 4px;
}
.file-remove {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  transition: color 0.2s ease;
}
.file-remove:hover {
  color: var(--el-color-danger);
}
.upload-actions {
  display: flex;
  justify-content: center;
}
.upload-actions .upload-btn {
  min-width: 200px;
}
/* 手机：上传按钮撑满，操作区更好点按 */
@media (max-width: 768px) {
  .upload-actions .upload-btn {
    width: 100%;
  }
}
</style>
