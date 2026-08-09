/**
 * Media policy for the public frontend.
 *
 * Only images produced by the current LinkLife upload chain
 * (`/api/files/**`) are rendered as real URLs. Legacy course asset paths are
 * never requested; the UI renders deterministic local visuals instead.
 * Database fields are never modified.
 */
import { BLOG_DEMO_IMAGES, SHOP_DEMO_IMAGES } from '@/data/demoMedia'

export function resolveImageUrl(raw: string | null | undefined): string | null {
  if (!raw) return null
  const first = raw
    .split(',')
    .map((s) => s.trim())
    .find(Boolean)
  if (!first) return null
  if (first.startsWith('/api/files/')) return first
  return null
}

export type MediaDescriptor =
  | { type: 'remote'; url: string; alt: string }
  | { type: 'demo'; url: string; alt: string }
  | { type: 'fallback'; seed: string; label: string }

interface MediaSubject {
  id: number
  images?: string | null
}

function resolveMedia(
  subject: MediaSubject,
  demoMap: Record<number, string>,
  kind: 'shop' | 'blog',
  label: string,
): MediaDescriptor {
  const remote = resolveImageUrl(subject.images)
  if (remote) {
    return { type: 'remote', url: remote, alt: `${label} 封面` }
  }
  const demo = demoMap[subject.id]
  if (demo) {
    return { type: 'demo', url: demo, alt: `${label} 演示封面` }
  }
  return { type: 'fallback', seed: `${kind}:${subject.id}`, label }
}

/** Priority: /api/files/** > fixed demo local image > deterministic fallback. */
export function resolveShopMedia(shop: MediaSubject & { name: string }): MediaDescriptor {
  return resolveMedia(shop, SHOP_DEMO_IMAGES, 'shop', shop.name)
}

export function resolveBlogMedia(blog: MediaSubject & { title: string }): MediaDescriptor {
  return resolveMedia(blog, BLOG_DEMO_IMAGES, 'blog', blog.title)
}

export function hashSeed(input: string): number {
  let h = 0
  for (let i = 0; i < input.length; i += 1) {
    h = (h << 5) - h + input.charCodeAt(i)
    h |= 0
  }
  return Math.abs(h)
}

export function gradientFor(seed: string): string {
  const palettes = [
    'linear-gradient(135deg, #ece9ff 0%, #f6f0ff 100%)',
    'linear-gradient(135deg, #e8f1ff 0%, #f0f7ff 100%)',
    'linear-gradient(135deg, #e4f8f4 0%, #effbf8 100%)',
    'linear-gradient(135deg, #fff2e8 0%, #fff7f0 100%)',
  ]
  return palettes[hashSeed(seed) % palettes.length]
}
