package com.linklife.shared.outbox;

/**
 * 本地 Outbox 发布端口（shared 通用接口，不依赖任何业务模块）。
 *
 * <p>实现方（trade 的 MySqlOutboxPublisher）必须在调用方已有 MySQL 本地事务内插入
 * tb_outbox_event：insert 影响行数必须为 1，异常向上抛出并参与整体回滚；
 * 不得开启 REQUIRES_NEW、不得操作 Redis、不得吞 DuplicateKeyException。</p>
 */
public interface OutboxPublisher {

    /**
     * 发布一条 PENDING Outbox 事件（参与调用方 MySQL 事务）。
     *
     * @param command 经过校验的发布命令
     */
    void publish(OutboxPublishCommand command);
}
