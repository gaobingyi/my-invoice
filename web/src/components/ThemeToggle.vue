<template>
  <el-tooltip
    :content="isDark ? '切换到亮色模式' : '切换到暗色模式'"
    placement="left"
    :show-after="300"
  >
    <button
      type="button"
      class="theme-toggle"
      @click="toggleTheme"
    >
      <el-icon :size="17"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
    </button>
  </el-tooltip>
</template>

<script setup>
import { Sunny, Moon } from '@element-plus/icons-vue'
import { isDark, applyTheme } from '../utils/theme'

function toggleTheme() {
  isDark.value = !isDark.value
  applyTheme()
}
</script>

<style scoped>
.theme-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 50%;
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  cursor: pointer;
  transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease;
}
.theme-toggle:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}
</style>
