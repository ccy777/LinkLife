package com.linklife.trade.admission;

/**
 * seckill.lua 返回码的领域化判定结果。
 *
 * <p>固定映射：0→ACCEPTED、1→OUT_OF_STOCK、2→DUPLICATE_ORDER、3→NOT_INITIALIZED、
 * 4→NOT_STARTED、5→ENDED、6→UNAVAILABLE；null 与任何未知值一律 fail-closed 为
 * UNAVAILABLE。按完整 long 值匹配，禁止 {@code intValue()} 窄化导致未知码被截断为成功。</p>
 */
public enum SeckillAdmissionDecision {
    ACCEPTED,
    OUT_OF_STOCK,
    DUPLICATE_ORDER,
    NOT_INITIALIZED,
    NOT_STARTED,
    ENDED,
    UNAVAILABLE;

    /**
     * 按完整 long 值映射 Lua 返回码；null 与未知值映射为 UNAVAILABLE。
     */
    public static SeckillAdmissionDecision from(Long code) {
        if (code == null) {
            return UNAVAILABLE;
        }
        long value = code.longValue();
        if (value == 0L) {
            return ACCEPTED;
        }
        if (value == 1L) {
            return OUT_OF_STOCK;
        }
        if (value == 2L) {
            return DUPLICATE_ORDER;
        }
        if (value == 3L) {
            return NOT_INITIALIZED;
        }
        if (value == 4L) {
            return NOT_STARTED;
        }
        if (value == 5L) {
            return ENDED;
        }
        return UNAVAILABLE;
    }
}
