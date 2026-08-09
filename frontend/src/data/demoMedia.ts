/**
 * Static mapping of the fixed public demo seed to local SVG cover assets.
 * Keyed by business id (shop.id / blog.id); never by name matching and
 * never fetched at runtime. User-uploaded `/api/files/**` URLs always take
 * priority over these demo assets (see utils/media.ts).
 *
 * Demo covers now use the realistic local WebP pack
 * (frontend/src/assets/demo/photos/**). The SVG demo covers and the
 * deterministic CSS/SVG fallback are retained.
 */

import shop01 from '@/assets/demo/photos/shops/shop-01.webp'
import shop02 from '@/assets/demo/photos/shops/shop-02.webp'
import shop03 from '@/assets/demo/photos/shops/shop-03.webp'
import shop04 from '@/assets/demo/photos/shops/shop-04.webp'
import shop05 from '@/assets/demo/photos/shops/shop-05.webp'
import shop06 from '@/assets/demo/photos/shops/shop-06.webp'
import shop07 from '@/assets/demo/photos/shops/shop-07.webp'
import shop08 from '@/assets/demo/photos/shops/shop-08.webp'
import shop09 from '@/assets/demo/photos/shops/shop-09.webp'
import shop10 from '@/assets/demo/photos/shops/shop-10.webp'
import shop11 from '@/assets/demo/photos/shops/shop-11.webp'
import shop12 from '@/assets/demo/photos/shops/shop-12.webp'
import shop13 from '@/assets/demo/photos/shops/shop-13.webp'
import shop14 from '@/assets/demo/photos/shops/shop-14.webp'

import moment04 from '@/assets/demo/photos/moments/moment-04.webp'
import moment05 from '@/assets/demo/photos/moments/moment-05.webp'
import moment06 from '@/assets/demo/photos/moments/moment-06.webp'
import moment07 from '@/assets/demo/photos/moments/moment-07.webp'

export const SHOP_DEMO_IMAGES: Record<number, string> = {
  1: shop01,
  2: shop02,
  3: shop03,
  4: shop04,
  5: shop05,
  6: shop06,
  7: shop07,
  8: shop08,
  9: shop09,
  10: shop10,
  11: shop11,
  // Asset-pack manifest labels for shop-12/shop-13 are swapped vs the seed:
  // shop-12.webp depicts the 漫游歌房 theme and shop-13.webp depicts the
  // 微光派对空间 theme, so id 12 maps to shop-13.webp and id 13 to shop-12.webp.
  12: shop13,
  13: shop12,
  14: shop14,
}

export const BLOG_DEMO_IMAGES: Record<number, string> = {
  4: moment04,
  5: moment05,
  6: moment06,
  7: moment07,
}
