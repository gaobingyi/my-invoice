<template>
  <el-container v-if="routeReady && !isLoginPage" class="layout">
    <el-aside
      :width="isExpanded ? '240px' : '64px'"
      class="sidebar"
      :class="{ collapsed: !isExpanded }"
      @mouseenter="onSidebarEnter"
      @mouseleave="onSidebarLeave"
    >
      <div class="sidebar-head">
        <div class="sidebar-logo">
          <img src="/invoice-icon.svg" class="sidebar-logo-icon" alt="logo" />
          <span v-show="isExpanded">发票管理系统</span>
        </div>
        <el-tooltip
          :content="collapsed ? '展开菜单' : '收起菜单'"
          placement="right"
          :show-after="300"
        >
          <button
            class="sidebar-toggle"
            @click="toggleCollapsed"
            :aria-label="collapsed ? '展开菜单' : '收起菜单'"
          >
            <el-icon :key="collapsed ? 'expand' : 'fold'">
              <fold v-if="!collapsed" /><expand v-else />
            </el-icon>
          </button>
        </el-tooltip>
      </div>
      <el-menu
        :default-active="activeMenu"
        @select="onMenuSelect"
        :collapse="!isExpanded"
        class="sidebar-menu"
      >
        <el-menu-item index="upload">
          <el-icon><upload-filled /></el-icon>
          <template #title>发票上传</template>
        </el-menu-item>
        <el-menu-item index="list">
          <el-icon><tickets /></el-icon>
          <template #title>发票列表</template>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <div class="content">
      <!-- 操作行占常规布局空间（不再悬浮）：悬浮层会盖住滚动后的表格右上角，
           行内「预览/下载/删除」按钮的点击会被透明热区劫持 -->
      <div class="top-actions">
        <el-dropdown trigger="click" @command="onUserCommand">
          <span class="user-entry">
            <el-icon><user-filled /></el-icon>
            <span>{{ username }}</span>
            <el-icon><arrow-down /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <ThemeToggle />
      </div>
      <el-main class="main">
        <router-view />
      </el-main>
    </div>
  </el-container>
  <router-view v-else-if="routeReady" />
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { UploadFilled, Tickets, Fold, Expand, UserFilled, ArrowDown } from '@element-plus/icons-vue'
import { getUsername, setToken, setUsername } from './api/invoice'
import { applyTheme } from './utils/theme'
import ThemeToggle from './components/ThemeToggle.vue'

const router = useRouter()
const route = useRoute()

const collapsed = ref(localStorage.getItem('sidebarCollapsed') === 'true')
const username = ref(getUsername() || 'admin')

const hoverExpand = ref(false)
// 展开态 = 持久展开，或（持久收起时）鼠标悬停临时展开（flyout）
const isExpanded = computed(() => !collapsed.value || hoverExpand.value)

const activeMenu = computed(() => (route.path.startsWith('/list') ? 'list' : 'upload'))
const isLoginPage = computed(() => route.path === '/login')
// 首次导航 resolve 前不渲染，避免暗黑下先闪 Layout 再跳登录页
const routeReady = ref(false)
router.isReady().finally(() => { routeReady.value = true })

function onMenuSelect(index) {
  router.push(index === 'upload' ? '/upload' : '/list')
  if (window.matchMedia('(max-width: 768px)').matches) {
    collapsed.value = true
    localStorage.setItem('sidebarCollapsed', 'true')
  }
}

function onUserCommand(command) {
  if (command === 'logout') {
    setToken(null)
    setUsername(null)
    router.push('/login')
  }
}

function toggleCollapsed() {
  collapsed.value = !collapsed.value
  localStorage.setItem('sidebarCollapsed', String(collapsed.value))
}

function onSidebarEnter() {
  if (collapsed.value) hoverExpand.value = true
}

function onSidebarLeave() {
  hoverExpand.value = false
}

onMounted(() => {
  applyTheme()
  if (window.matchMedia('(max-width: 768px)').matches && !localStorage.getItem('sidebarCollapsed')) {
    collapsed.value = true
  }
})
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: var(--el-bg-color-page); }
.layout { height: 100vh; overflow: hidden; }
.main {
  padding: 0;
  overflow: auto;
  width: 100%;
  background: var(--el-bg-color);
}
/* 主区域卡片与边框零间隙，四角圆角移除；背景与菜单区统一（亮 #fff / 暗 #141414） */
.main .el-card {
  border-radius: 0;
  background: var(--el-bg-color);
}
/* 内容区：撑满剩余高度 */
.content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}
/* 右上角操作：占常规布局空间的行（justify-content 对齐右侧），
   不用 absolute 悬浮 —— 悬浮层会在列表滚动后遮挡右上角的操作按钮点击 */
.top-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  padding: 12px 24px;
}
.user-entry {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--el-text-color-regular);
  font-size: 14px;
  outline: none;
  border-radius: 999px;
  padding: 6px 12px;
  transition: background-color 0.2s ease, color 0.2s ease;
}
.user-entry:hover { background: var(--el-fill-color-light); color: var(--el-color-primary); }
/* 子级撑满：el-main 内部 div 默认 block，子元素 height:100% 失效 */
.main > div { display: flex; flex-direction: column; min-height: 100%; }
.main > div > * { flex: 1; min-height: 0; }
/* 页面切换动效：挂载入场动画（不用 Vue <transition>：页面内含 el-dialog 且
   teleport 关闭时遮罩是视图子孙节点，会干扰 transitionend 判定导致二次导航空白） */
.main > div:not(.el-overlay) {
  animation: page-in 0.25s ease;
}
@keyframes page-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
}
.main::-webkit-scrollbar { width: 8px; height: 8px; }
.main::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 4px; }
.main::-webkit-scrollbar-thumb:hover { background: var(--el-border-color-light); }
/* 左侧菜单：占满高度，无外边框；overflow hidden 防止收起时任何内部横向溢出带出滚动条 */
.sidebar {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-light);
  transition: width 0.2s;
}
.sidebar .el-menu {
  border-right: none;
  padding: 8px 0;
}
/* 顶部：logo + 折叠按钮。展开时左右排布，收起时上下居中 */
.sidebar-head {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  height: 60px;
  flex-shrink: 0;
}
.sidebar.collapsed .sidebar-head {
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  height: auto;
  padding: 14px 0 10px;
}
.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  font-size: 19px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
}
/* 展开态：logo 图标/文字左缘与菜单项图标/文字对齐（head padding 8 + 24 = 菜单项 12+20=32） */
.sidebar:not(.collapsed) .sidebar-logo {
  padding-left: 24px;
}
.sidebar.collapsed .sidebar-logo {
  flex: none;
}
.sidebar-logo-icon {
  width: 26px;
  height: 26px;
}
/* 折叠按钮：小方形图标按钮，与 logo 同行 */
.sidebar-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  background: none;
  color: var(--el-text-color-regular);
  transition: background-color 0.2s ease, color 0.2s ease;
}
.sidebar-toggle:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
/* 图标切换时轻微旋转入场 */
.sidebar-toggle .el-icon {
  animation: sidebar-icon-in 0.3s ease;
}
@keyframes sidebar-icon-in {
  from { transform: rotate(-90deg); opacity: 0.4; }
  to { transform: rotate(0); opacity: 1; }
}
/* 菜单项：胶囊选中态 + 左侧主色指示条，取代 EP 默认 2px 底部指示线 */
.sidebar .el-menu-item {
  position: relative;
  height: 44px;
  line-height: 44px;
  margin: 4px 12px;
  border-radius: 8px;
  transition: background-color 0.2s ease, color 0.2s ease;
}
.sidebar .el-menu-item:hover {
  background: var(--el-fill-color-light);
}
.sidebar .el-menu-item.is-active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.sidebar .el-menu-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 2px;
  background: var(--el-color-primary);
}
/* 折叠时胶囊贴边，去掉水平 margin 与 EP 默认 padding，图标居中 */
.sidebar .el-menu--collapse .el-menu-item {
  margin: 4px 0;
  padding: 0;
  justify-content: center;
}
.sidebar .el-menu--collapse .el-menu-item.is-active::before {
  width: 2px;
  height: 14px;
  left: 2px;
}
</style>
