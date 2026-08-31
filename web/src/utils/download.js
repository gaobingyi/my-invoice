/** blob → 隐藏 <a download> 触发下载 + 延迟 revoke。
 * a.click() 仅异步排队下载，立即释放 blob 可能让浏览器取到 0 字节 —— 延迟 1s 给下载流启动留时间。 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
