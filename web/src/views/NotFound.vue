<template>
  <div class="not-found">
    <div class="not-found-inner">
      <div class="nf-code">404</div>
      <div class="nf-divider"></div>
      <p class="nf-text">页面不存在或已被移除</p>
      <el-button type="primary" round @click="router.push('/')">返回首页</el-button>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<style scoped>
/* 外层承接 App.vue 的 .main > div > * { flex:1 }，只做居中；
   App.vue 布局规则会把 .main 的直接子元素逐个拉伸，必须再用内层容器包住内容 */
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
}
.not-found-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--el-text-color-secondary);
}
.nf-code {
  font-size: 80px;
  font-weight: 700;
  letter-spacing: 6px;
  background: var(--brand-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  line-height: 1;
}
.nf-divider {
  width: 48px;
  height: 3px;
  border-radius: 2px;
  background: var(--brand-gradient);
  opacity: 0.6;
}
.nf-text {
  font-size: 15px;
}
</style>
