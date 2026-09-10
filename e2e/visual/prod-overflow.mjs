// Diagnostic : debordement horizontal sur le site en production, vu d un telephone.
//   node visual/prod-overflow.mjs [url] -> liste les elements plus larges que l ecran, capture prod-*.png
import { chromium, devices } from '@playwright/test'

const base = process.argv[2] ?? 'https://ekuiseo.com'
const paths = ['/', '/search?from=Cotonou&to=Bohicon&fromLat=6.3703&fromLng=2.3912&toLat=7.1786&toLng=2.0667&seats=1', '/login', '/autour']
const browser = await chromium.launch({ channel: 'chrome' })
const context = await browser.newContext({ ...devices['Pixel 5'], locale: 'fr-FR' })
const page = await context.newPage()
for (const path of paths) {
  await page.goto(base + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const report = await page.evaluate(() => {
    const width = window.innerWidth
    const offenders = []
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect()
      if (r.width > 0 && (r.right > width + 1 || r.left < -1)) {
        const cls = (el.getAttribute('class') ?? '').slice(0, 80)
        offenders.push(`${el.tagName.toLowerCase()}.${cls} [${Math.round(r.left)}..${Math.round(r.right)}]`)
      }
    }
    return { width, scrollWidth: document.documentElement.scrollWidth, bodyScrollWidth: document.body.scrollWidth, offenders: offenders.slice(0, 12), build: document.querySelector('script[src*="/assets/index-"]')?.getAttribute('src') }
  })
  console.log(path, JSON.stringify(report, null, 1))
  await page.screenshot({ path: `visual/output/prod${path.replace(/[^a-z]/gi, '_') || '_home'}.png`, fullPage: true })
}
await browser.close()
