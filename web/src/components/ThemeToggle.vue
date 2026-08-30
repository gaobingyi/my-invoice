<template>
  <!-- tooltip 仅在支持 hover 的设备渲染：触屏 tap 会触发合成 mouseenter 且永远没有
       mouseleave，tooltip 会挂在屏幕上不消失（用户看到的「不消失的 toast」） -->
  <el-tooltip
    v-if="canHover"
    :content="isDark ? '切换到亮色模式' : '切换到暗色模式'"
    placement="left"
    :show-after="300"
  >
    <button
      type="button"
      class="theme-toggle"
      aria-label="切换主题"
      @click="toggleTheme"
    >
      <el-icon :size="17"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
    </button>
  </el-tooltip>
  <button
    v-else
    type="button"
    class="theme-toggle"
    aria-label="切换主题"
    @click="toggleTheme"
  >
    <el-icon :size="17"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
  </button>
</template>

<script setup>
import { Sunny, Moon } from '@element-plus/icons-vue'
import { isDark, applyTheme } from '../utils/theme'

// 触屏设备（hover: none）不渲染 tooltip，点击只切主题
const canHover = window.matchMedia('(hover: hover)').matches

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
