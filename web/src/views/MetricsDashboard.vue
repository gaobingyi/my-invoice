<template>
  <el-card shadow="never">
    <div class="metrics-head">
      <div class="metrics-title">
        <span class="title-dot"></span>
        <span class="page-title">解析指标</span>
        <el-tag size="small" type="info" round effect="plain">共 {{ snap.uploadsTotal ?? 0 }} 次</el-tag>
        <el-button
          class="metrics-refresh"
          :icon="Refresh"
          circle
          :loading="loading"
          title="刷新"
          @click="load"
        />
      </div>
    </div>

    <div v-loading="loading">
      <!-- 第一行：统计卡片 -->
      <el-row :gutter="16" class="stat-row">
        <el-col :xs="12" :sm="8" :md="6">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-ring">
              <el-progress type="dashboard" :percentage="regexRate" :width="72" :stroke-width="6" />
            </div>
            <div class="stat-label">正则解析成功率</div>
            <div class="stat-sub">{{ snap.regex?.success ?? 0 }} / {{ (snap.regex?.success ?? 0) + (snap.regex?.failure ?? 0) }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-ring">
              <el-progress type="dashboard" :percentage="fillRate" :width="72" :stroke-width="6" />
            </div>
            <div class="stat-label">LLM 解析成功率</div>
            <div class="stat-sub">{{ snap.llm?.fillSuccess ?? 0 }} / {{ snap.llm?.triggered ?? 0 }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-ring">
              <el-progress type="dashboard" :percentage="apiRate" :width="72" :stroke-width="6" />
            </div>
            <div class="stat-label">LLM API 成功率</div>
            <div class="stat-sub">{{ snap.llm?.apiSuccess ?? 0 }} / {{ (snap.llm?.apiSuccess ?? 0) + (snap.llm?.apiFailure ?? 0) }}</div>
          </el-card>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <el-card shadow="hover" class="stat-card">
            <div class="stat-value-wrap">
              <span class="stat-value">{{ snap.llm?.avgResponseTimeMs != null ? snap.llm.avgResponseTimeMs + ' ms' : '-' }}</span>
            </div>
            <div class="stat-label">LLM API 平均响应时间</div>
            <div class="stat-sub" v-if="snap.llm?.apiSuccess || snap.llm?.apiFailure">
              {{ (snap.llm?.apiSuccess ?? 0) + (snap.llm?.apiFailure ?? 0) }} 次调用
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 第二行：趋势图 -->
      <el-card shadow="hover" class="trend-card">
        <template #header>
          <span class="card-header">每日趋势（最近 {{ trend.length }} 天）</span>
        </template>
        <div class="trend-chart" v-if="trend.length">
          <div class="trend-bar-row" v-for="day in trend" :key="day.date">
            <div class="trend-date">{{ formatShortDate(day.date) }}</div>
            <div class="trend-bars">
              <div class="trend-bar" :style="{ width: barWidth(day.uploads) }" :title="`${day.uploads} 次上传`">
                <span class="bar-label" v-if="day.uploads">{{ day.uploads }}</span>
              </div>
            </div>
            <div class="trend-rates">
              <el-tag size="small" :type="day.regexSuccess + day.regexFailure > 0 && day.regexSuccess / (day.regexSuccess + day.regexFailure) >= 0.8 ? 'success' : 'warning'" effect="plain">
                正则 {{ pct(day.regexSuccess, day.regexSuccess + day.regexFailure) }}
              </el-tag>
              <el-tag size="small" :type="day.llmTriggered > 0 && day.llmFillSuccess / day.llmTriggered >= 0.5 ? 'success' : 'warning'" effect="plain" v-if="day.llmTriggered">
                LLM {{ pct(day.llmFillSuccess, day.llmTriggered) }}
              </el-tag>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无趋势数据，上传发票后生成" :image-size="64" />
      </el-card>

      <!-- 第三行：正则字段缺失明细 -->
      <el-card shadow="hover" class="misses-card" v-if="fieldMisses.length">
        <template #header>
          <span class="card-header">正则字段缺失明细</span>
        </template>
        <!-- 移动端：单列缺失明细（桌面 el-table 保持原样） -->
        <div v-if="isMobile" class="m-miss-list">
          <div v-for="row in fieldMisses" :key="row.field" class="m-miss-row">
            <div class="m-miss-top">
              <span class="m-miss-label">{{ row.label }}</span>
              <span class="m-miss-count">{{ row.count }} 次 · {{ pct(row.count, snap.uploadsTotal) }}</span>
            </div>
            <el-progress
              class="m-miss-bar"
              :percentage="Math.round(row.count / Math.max(snap.uploadsTotal, 1) * 100)"
              :stroke-width="8"
              :show-text="false"
            />
          </div>
        </div>
        <el-table v-else :data="fieldMisses" stripe size="small" empty-text="无缺失记录">
          <el-table-column prop="label" label="字段名" width="180" />
          <el-table-column prop="count" label="缺失次数" width="120" />
          <el-table-column label="占比" width="120">
            <template #default="{ row }">
              {{ pct(row.count, snap.uploadsTotal) }}
            </template>
          </el-table-column>
          <el-table-column label="占比条" min-width="200">
            <template #default="{ row }">
              <el-progress :percentage="Math.round(row.count / Math.max(snap.uploadsTotal, 1) * 100)" :stroke-width="10" :show-text="false" />
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { fetchParsingMetrics, fetchParsingTrend } from '../api/invoice'
import { useIsMobile } from '../composables/useIsMobile'

const isMobile = useIsMobile()

const loading = ref(false)
const snap = ref({})
const trend = ref([])

const regexRate = computed(() => pctNum(snap.value.regex?.success, (snap.value.regex?.success ?? 0) + (snap.value.regex?.failure ?? 0)))
const fillRate = computed(() => pctNum(snap.value.llm?.fillSuccess, snap.value.llm?.triggered ?? 0))
const apiRate = computed(() => pctNum(snap.value.llm?.apiSuccess, (snap.value.llm?.apiSuccess ?? 0) + (snap.value.llm?.apiFailure ?? 0)))

const FIELD_LABELS = {
  invoiceNumber: '发票号码',
  invoiceDate: '开票日期',
  buyerName: '购买方',
  buyerTaxId: '购买方税号',
  sellerName: '销售方',
  sellerTaxId: '销售方税号',
  category: '项目名称',
  totalAmount: '金额合计',
  taxAmount: '税额合计',
  totalWithTax: '价税合计'
}

const fieldMisses = computed(() => {
  const m = snap.value.regexFieldMisses || {}
  return Object.entries(m)
    .map(([field, count]) => ({ field, label: FIELD_LABELS[field] || field, count }))
    .sort((a, b) => b.count - a.count)
})

function pctNum(num, den) {
  if (!den) return 0
  return Math.round((num / den) * 100)
}

function pct(num, den) {
  if (!den) return '-'
  return Math.round((num / den) * 100) + '%'
}

function barWidth(uploads) {
  const max = Math.max(...trend.value.map(d => d.uploads), 1)
  return Math.round((uploads / max) * 100) + '%'
}

function formatShortDate(d) {
  if (!d) return ''
  const parts = d.split('-')
  return parts.length >= 3 ? `${parts[1]}-${parts[2]}` : d
}

async function load() {
  loading.value = true
  try {
    const [metricsRes, trendRes] = await Promise.all([
      fetchParsingMetrics(),
      fetchParsingTrend(30)
    ])
    snap.value = metricsRes.data
    trend.value = trendRes.data
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.metrics-head {
  margin-bottom: 20px;
}
.metrics-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.metrics-title .el-tag {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  border-color: transparent;
  font-weight: 500;
}

.stat-row { margin-bottom: 16px; }
.stat-card {
  text-align: center;
  padding: 8px 0;
}
.stat-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 16px 8px;
}
.stat-value-wrap {
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  line-height: 1.2;
}
.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.stat-sub {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.stat-ring :deep(.el-progress__text) {
  font-size: 14px !important;
}

.trend-card { margin-bottom: 16px; }
.card-header {
  font-weight: 600;
  font-size: 14px;
}
.trend-chart {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.trend-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.trend-date {
  width: 48px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  text-align: right;
}
.trend-bars {
  flex: 1;
  height: 20px;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
  overflow: hidden;
}
.trend-bar {
  height: 100%;
  background: var(--el-color-primary-light-3);
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding-right: 6px;
  min-width: 20px;
  transition: width 0.3s ease;
}
.bar-label {
  font-size: 11px;
  color: #fff;
  font-weight: 600;
}
.trend-rates {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.misses-card { margin-bottom: 16px; }

/* 移动端字段缺失明细：单列堆叠（字段名+次数/占比 一行，占比条一行） */
.m-miss-row {
  padding: 8px 0;
}
.m-miss-row + .m-miss-row {
  border-top: 1px solid var(--el-border-color-lighter);
}
.m-miss-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.m-miss-label {
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.m-miss-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
</style>
