package com.linklife.common.core.contract;

/**
 * Stage 4 内部可信 Header 契约常量。
 *
 * <p>只允许 Gateway 注入；业务服务不得信任客户端直接携带的同名 Header。
 * 018B 仅冻结常量，不在本任务实现正式 UserContextFilter。</p>
 */
public final class InternalHeaders {

    public static final String X_LINKLIFE_USER_ID = "X-LinkLife-User-Id";

    /**
     * @deprecated 018C 冻结：Gateway 不传播 nick/icon（昵称可含任意 Unicode，HTTP Header 不应承担
     * 用户资料传播；Social 018E 经 Identity batch API 获取用户摘要）。常量仅保留作 reserved 标记，
     * 正式 Gateway 与业务代码不得使用。
     */
    @Deprecated
    public static final String X_LINKLIFE_USER_NICK = "X-LinkLife-User-Nick";

    /**
     * @deprecated 同 {@link #X_LINKLIFE_USER_NICK}，保留作 reserved 标记，不得使用。
     */
    @Deprecated
    public static final String X_LINKLIFE_USER_ICON = "X-LinkLife-User-Icon";

    private InternalHeaders() {
    }
}
