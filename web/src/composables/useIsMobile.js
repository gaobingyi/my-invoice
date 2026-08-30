import { ref } from 'vue'

// 断点与 tokens.css 的 --bp-mobile 同源：运行时读 CSS 变量，避免两处漂移
function readBp() {
  try {
    const v = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--bp-mobile'))
    return Number.isFinite(v) && v > 0 ? v : 768
  } catch {
    return 768
  }
}

// 模块级单例：所有组件共享同一个 matchMedia 监听，切窗口尺寸时全局同步
const isMobile = ref(false)
let initialized = false

function init() {
  if (initialized) return
  initialized = true
  const mq = window.matchMedia(`(max-width: ${readBp()}px)`)
  isMobile.value = mq.matches
  mq.addEventListener('change', e => { isMobile.value = e.matches })
}

export function useIsMobile() {
  init()
  return isMobile
}
