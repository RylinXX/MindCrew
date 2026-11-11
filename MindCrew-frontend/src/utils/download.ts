/**
 * 文件下载工具 · 数字员工 / 检索分析 / 客服中心共用
 *
 * `content-disposition` 优先取 RFC 5987 的 `filename*=UTF-8''...`，
 * 其次兼容普通 `filename="..."`，都取不到时回落到调用方给的默认文件名。
 */

/** 从 content-disposition 解析文件名（失败返回 null） */
export function parseDispositionFilename(disposition?: string | null): string | null {
  if (!disposition) return null

  // RFC 5987：filename*=UTF-8''%E4%B8%AD%E6%96%87.docx
  const star = /filename\*\s*=\s*([^;]+)/i.exec(disposition)
  if (star?.[1]) {
    let value = star[1].trim().replace(/^["']|["']$/g, '')
    const match = /^([^']*)'[^']*'(.*)$/.exec(value)
    if (match?.[2] !== undefined) value = match[2]
    try {
      return decodeURIComponent(value)
    } catch {
      return value
    }
  }

  // 普通：filename="xxx.docx"
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition)
  if (plain?.[1]) {
    const value = plain[1].trim()
    try {
      return decodeURIComponent(value)
    } catch {
      return value
    }
  }

  return null
}

/**
 * 触发浏览器下载。
 * @param blob      文件内容
 * @param filename  默认文件名（content-disposition 解析失败时使用）
 * @param disposition 响应头 content-disposition（可选，优先用它里面的文件名）
 */
export function downloadBlob(blob: Blob, filename: string, disposition?: string | null): void {
  const finalName = parseDispositionFilename(disposition) || filename || 'download'

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = finalName
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 部分浏览器需要延迟释放，否则下载可能被取消
  window.setTimeout(() => URL.revokeObjectURL(url), 1500)
}
