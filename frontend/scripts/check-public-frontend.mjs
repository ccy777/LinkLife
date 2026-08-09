#!/usr/bin/env node
/**
 * Lightweight public contract check for the LinkLife frontend.
 *
 * Checks:
 *  - old Vue2 pages / legacy asset directories are absent
 *  - no v-html / innerHTML / eval / document.write
 *  - API base uses /api, token key is correct, no localStorage auth
 *  - no external image/asset hotlinks in runtime source
 *  - seckill business rejection renders a terminal error UI (no permanent
 *    skeleton) and PERSISTED remains the only persisted-success UI state
 *
 * Internal source/leak-word scanning is intentionally NOT part of this
 * public script; it is executed by the release audit tooling outside the
 * public repository.
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const SKIP = new Set(['node_modules', 'dist', '.git'])

function walk(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    if (SKIP.has(name)) continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...walk(p))
    else out.push(p)
  }
  return out
}

const files = walk(root)
const textFiles = files.filter((p) => !/\.(png|jpe?g|gif|webp|svg|ttf|woff2?|ico)$/.test(p))

function read(p) {
  return readFileSync(p, 'utf8')
}

const failures = []

const oldPages = [
  'login.html',
  'login2.html',
  'shop-list.html',
  'shop-detail.html',
  'blog-detail.html',
  'blog-edit.html',
  'info.html',
  'info-edit.html',
  'other-info.html',
  'vue.js',
  'element.js',
  'axios.min.js',
]
const rootNames = new Set(readdirSync(root))
for (const name of oldPages) {
  if (rootNames.has(name)) failures.push(`old asset present: ${name}`)
}

const legacyDirs = ['imgs', 'css', 'fonts']
for (const dir of legacyDirs) {
  if (rootNames.has(dir)) failures.push(`legacy directory present: ${dir}`)
}

const forbidden = ['v-html', 'innerHTML', 'eval(', 'document.write']
for (const p of textFiles) {
  const rel = relative(root, p).replaceAll('\\', '/')
  if (rel.startsWith('scripts/')) continue // the checker itself lists the terms
  const text = read(p)
  for (const term of forbidden) {
    if (text.includes(term)) failures.push(`${rel}: forbidden rendering term "${term}"`)
  }
}

const httpSrc = read(join(root, 'src', 'api', 'http.ts'))
if (!httpSrc.includes("baseURL: '/api'")) failures.push('http.ts: baseURL must be /api')
if (!httpSrc.includes("'linklife.session.token'")) failures.push('http.ts: token key must be linklife.session.token')
if ((httpSrc.match(/body\.data/g) || []).length !== 1) {
  failures.push('http.ts: ApiResult must be unwrapped exactly once in the response interceptor')
}
if (/return res\.data/.test(httpSrc)) {
  failures.push('http.ts: request<T> must not double-unwrap (res.data after interceptor unwrap)')
}
if (!/return res as unknown as T/.test(httpSrc)) {
  failures.push('http.ts: request<T> must return the interceptor result directly')
}
if (!httpSrc.includes("throw new ApplicationError(body.errorMsg || '请求失败')")) {
  failures.push('http.ts: success=false must throw ApplicationError')
}

const shopCard = read(join(root, 'src', 'components', 'shop', 'ShopCard.vue'))
if (/<PriceText :cents="shop\.avgPrice"/.test(shopCard)) {
  failures.push('ShopCard.vue: shop avgPrice must not use cents PriceText')
}
if (!/<YuanPriceText :yuan="shop\.avgPrice"/.test(shopCard)) {
  failures.push('ShopCard.vue: shop avgPrice must render via YuanPriceText (integer yuan)')
}
const formatSrc = read(join(root, 'src', 'utils', 'format.ts'))
if (!formatSrc.includes('export function formatYuanAmount')) {
  failures.push('format.ts: formatYuanAmount helper must exist for integer yuan display')
}
const voucherCard = read(join(root, 'src', 'components', 'shop', 'VoucherCard.vue'))
if (!voucherCard.includes(':cents="voucher.payValue"')) {
  failures.push('VoucherCard.vue: voucher payValue must keep cents-based PriceText (¥47.50 style)')
}

const layout = read(join(root, 'src', 'layouts', 'AppLayout.vue'))
for (const nav of ['发现', '商铺', '动态', '工程验证']) {
  if (!layout.includes(nav)) failures.push(`AppLayout.vue: main navigation missing "${nav}"`)
}
const loginView = read(join(root, 'src', 'views', 'LoginView.vue'))
for (const zh of ['欢迎回来', '手机号', '验证码', '获取验证码', '登录']) {
  if (!loginView.includes(zh)) failures.push(`LoginView.vue: missing Chinese copy "${zh}"`)
}
const engineeringView = read(join(root, 'src', 'views', 'EngineeringView.vue'))
if (!engineeringView.includes('并非实时监控数据')) {
  failures.push('EngineeringView.vue: non-live-telemetry disclaimer must exist')
}

const shopsDir = join(root, 'src', 'assets', 'demo', 'shops')
const momentsDir = join(root, 'src', 'assets', 'demo', 'moments')
for (let i = 1; i <= 14; i += 1) {
  const name = `shop-${String(i).padStart(2, '0')}.svg`
  if (!existsSync(join(shopsDir, name))) failures.push(`demo asset missing: shops/${name}`)
}
for (const id of [4, 5, 6, 7]) {
  const name = `moment-${String(id).padStart(2, '0')}.svg`
  if (!existsSync(join(momentsDir, name))) failures.push(`demo asset missing: moments/${name}`)
}

const photoShopsDir = join(root, 'src', 'assets', 'demo', 'photos', 'shops')
const photoMomentsDir = join(root, 'src', 'assets', 'demo', 'photos', 'moments')
for (let i = 1; i <= 14; i += 1) {
  const name = `shop-${String(i).padStart(2, '0')}.webp`
  if (!existsSync(join(photoShopsDir, name))) failures.push(`photo asset missing: shops/${name}`)
}
for (const id of [4, 5, 6, 7]) {
  const name = `moment-${String(id).padStart(2, '0')}.webp`
  if (!existsSync(join(photoMomentsDir, name))) failures.push(`photo asset missing: moments/${name}`)
}

const demoMediaSrc = read(join(root, 'src', 'data', 'demoMedia.ts'))
for (let i = 1; i <= 14; i += 1) {
  if (!new RegExp(`\\b${i}:`).test(demoMediaSrc)) {
    failures.push(`demoMedia.ts: shop ${i} mapping missing`)
  }
}
for (const id of [4, 5, 6, 7]) {
  if (!new RegExp(`\\b${id}:`).test(demoMediaSrc)) {
    failures.push(`demoMedia.ts: blog ${id} mapping missing`)
  }
}
for (let i = 1; i <= 14; i += 1) {
  const name = `shop-${String(i).padStart(2, '0')}.webp`
  if (!demoMediaSrc.includes(name)) failures.push(`demoMedia.ts: ${name} must be wired to shop ${i}`)
}
const importVarFor = (file) => {
  const m = demoMediaSrc.match(new RegExp(`import (\\w+) from ['"][^'"]*${file}['"]`))
  return m ? m[1] : null
}
const varShop13 = importVarFor('shop-13.webp') // 微光派对空间 theme
const varShop12 = importVarFor('shop-12.webp') // 漫游歌房 theme
if (!varShop13 || !new RegExp(`\\b12:\\s*${varShop13}\\b`).test(demoMediaSrc)) {
  failures.push('demoMedia.ts: shop id 12 must map to shop-13.webp (微光派对空间 theme)')
}
if (!varShop12 || !new RegExp(`\\b13:\\s*${varShop12}\\b`).test(demoMediaSrc)) {
  failures.push('demoMedia.ts: shop id 13 must map to shop-12.webp (漫游歌房 theme)')
}
for (const id of [4, 5, 6, 7]) {
  const name = `moment-${String(id).padStart(2, '0')}.webp`
  if (!demoMediaSrc.includes(name)) failures.push(`demoMedia.ts: ${name} must be wired to blog ${id}`)
}

const mediaUtils = read(join(root, 'src', 'utils', 'media.ts'))
const remoteIdx = mediaUtils.indexOf('const remote = resolveImageUrl(subject.images)')
const demoIdx = mediaUtils.indexOf('const demo = demoMap[subject.id]')
if (remoteIdx === -1 || demoIdx === -1 || demoIdx < remoteIdx) {
  failures.push('media.ts: /api/files/** must take priority over local demo images')
}
if (!/return \{ type: 'fallback'/.test(mediaUtils)) {
  failures.push('media.ts: unknown data must keep a deterministic fallback descriptor')
}

for (const p of files) {
  if (!p.endsWith('.svg')) continue
  const rel = relative(root, p).replaceAll('\\', '/')
  const text = read(p)
  if (/<script/i.test(text)) failures.push(`${rel}: script tag forbidden`)
  if (/on\w+\s*=/.test(text)) failures.push(`${rel}: event handler forbidden`)
  if (/href\s*=/.test(text)) failures.push(`${rel}: href forbidden (external reference)`)
  if (/foreignObject/i.test(text)) failures.push(`${rel}: foreignObject forbidden`)
}

for (const p of files) {
  if (!p.startsWith(join(root, 'src'))) continue
  if (/\.(png|jpe?g|gif|webp|ico)$/.test(p)) continue
  const rel = relative(root, p).replaceAll('\\', '/')
  const text = read(p)
  if (/localStorage/.test(text)) failures.push(`${rel}: localStorage must not be used for auth`)
  for (const [i, line] of text.split('\n').entries()) {
    if (/https?:\/\//.test(line) && !line.includes('www.w3.org/2000/svg')) {
      failures.push(`${rel}:${i + 1}: external URL in runtime source`)
    }
  }
}

const drawer = read(join(root, 'src', 'components', 'voucher', 'SubmissionDrawer.vue'))
if (!drawer.includes('admissionError')) {
  failures.push('SubmissionDrawer.vue: missing admissionError state')
}
if (!drawer.includes('admitting')) {
  failures.push('SubmissionDrawer.vue: missing admitting state')
}
if (!/admissionError\.value\s*=/.test(drawer)) {
  failures.push('SubmissionDrawer.vue: business rejection must set admissionError')
}
if (!drawer.includes('@click="submit"')) {
  failures.push('SubmissionDrawer.vue: user-initiated retry must exist')
}
const persistedGuardIdx = drawer.indexOf("state === 'PERSISTED'")
const successAlertIdx = drawer.indexOf('type="success"')
if (persistedGuardIdx === -1 || successAlertIdx === -1 || successAlertIdx < persistedGuardIdx) {
  failures.push('SubmissionDrawer.vue: unguarded persisted-success alert')
}

if (failures.length > 0) {
  console.error('Frontend contract FAILED:')
  for (const f of failures) console.error(`  - ${f}`)
  process.exit(1)
}

console.log('Frontend contract PASS: old assets absent, no unsafe rendering, correct API/token contract, no external hotlinks, seckill reject state contract ok, demo media assets complete.')
