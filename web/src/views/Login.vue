<template>
  <div class="login-page">
    <div class="login-bg" aria-hidden="true">
      <span class="orb orb-1"></span>
      <span class="orb orb-2"></span>
      <span class="orb orb-3"></span>
    </div>
    <div class="login-theme-toggle">
      <ThemeToggle />
    </div>
    <div class="login-card">
      <div class="login-title">
        <img src="/invoice-icon.svg" class="login-logo" alt="logo" />
        <span>发票管理系统</span>
      </div>
      <p class="login-sub">上传 PDF 发票 · 自动解析 · 统一管理</p>
      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            :prefix-icon="Lock"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button class="login-btn" type="primary" size="large" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
    </div>
    <p class="login-footer">© 2026 发票管理系统</p>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login, setToken, setUsername } from '../api/invoice'
import ThemeToggle from '../components/ThemeToggle.vue'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const { data } = await login(form.username, form.password)
    setToken(data.token)
    setUsername(data.username)
    router.push('/upload')
  } catch (e) {
    ElMessage.error(e.response?.data || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  overflow: hidden;
  background: linear-gradient(135deg, #eef2ff 0%, #f4f7ff 50%, #eaf0ff 100%);
}
html.dark .login-page {
  background: linear-gradient(135deg, #0d1230 0%, #0a0f2a 50%, #101a3e 100%);
}

/* 漂浮光斑，营造现代清爽氛围 */
.login-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  opacity: 0.5;
}
.orb-1 {
  width: 420px;
  height: 420px;
  background: #6c8cff;
  top: -120px;
  left: -80px;
  animation: float-orb 14s ease-in-out infinite;
}
.orb-2 {
  width: 360px;
  height: 360px;
  background: #9aa8ff;
  bottom: -100px;
  right: -60px;
  animation: float-orb 18s ease-in-out infinite reverse;
}
.orb-3 {
  width: 260px;
  height: 260px;
  background: #4f6bf5;
  top: 42%;
  left: 58%;
  opacity: 0.32;
  animation: float-orb 22s ease-in-out infinite;
}
@keyframes float-orb {
  0%, 100% { transform: translate(0, 0); }
  50% { transform: translate(24px, -30px); }
}

/* 右上角主题切换，与主界面同款样式 */
.login-theme-toggle {
  position: absolute;
  top: 24px;
  right: 24px;
  z-index: 1;
}
.login-card {
  position: relative;
  width: 400px;
  max-width: calc(100vw - 40px);
  padding: 36px 36px 30px;
  border-radius: 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
/* 底部品牌渐变条：增加视觉层次感 */
.login-card::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--brand-gradient);
}
.login-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 21px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.login-logo {
  width: 28px;
  height: 28px;
}
.login-sub {
  margin: 10px 0 26px;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.login-btn {
  width: 100%;
  margin-top: 4px;
  height: 44px;
  font-size: 15px;
  letter-spacing: 6px;
}
.login-footer {
  position: relative;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
