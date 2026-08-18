package com.linklife.trade.application;

import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseReasonCode;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 订单生命周期服务：当前用户取消自己的未支付订单（Stage 3D 入口）。
 *
 * <p>Stage 3E（017D）起不再自行维护订单关闭 CAS 与 affected=0 回查，
 * 统一委托 {@link OrderCloseTransactionService}：在同一个 MySQL 本地事务内完成
 * 订单 UNPAID → CANCELED、MySQL 库存 +1、状态日志与 Outbox 事件写入。</p>
 *
 * <p>本入口只负责：参数/登录校验、从服务端上下文取得 userId、构造统一关闭命令、
 * 将事务内核结果映射为外部语义；userId 不接受客户端传入。</p>
 */
@Component
public class OrderLifecycleService {

    public static final String ORDER_NOT_FOUND = "订单不存在";
    public static final String STATUS_NOT_CANCELABLE = "当前订单状态不可取消";
    public static final String NOT_LOGGED_IN = "请先登录";

    private static final String PARAM_ERROR = "orderId 必须大于 0";
    private static final String DATA_INCONSISTENT = "订单状态数据不一致，fail-closed";

    @Resource
    private OrderCloseTransactionService orderCloseTransactionService;

    /**
     * 可测试时间源：默认系统时钟，测试通过注入固定 Clock 验证确定的 now。
     */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * 取消当前用户自己的未支付订单（委托统一关闭事务内核）。成功或幂等成功时返回订单 ID。
     *
     * @param orderId 订单 ID，必须大于 0
     * @return 订单 ID
     * @throws BusinessException 未登录、订单不存在/他人订单、当前订单状态不可取消
     * @throws IllegalStateException 数据不一致（fail-closed）；内核异常原样传播
     */
    public long cancelByCurrentUser(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException(PARAM_ERROR);
        }
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            throw new BusinessException(NOT_LOGGED_IN);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        OrderCloseCommand command = new OrderCloseCommand(
                orderId, currentUserId, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.USER_CANCEL, now);
        OrderCloseResult result = orderCloseTransactionService.close(command);
        return mapResult(orderId, result);
    }

    /**
     * 将统一关闭事务结果穷尽映射为外部语义。
     */
    private long mapResult(long orderId, OrderCloseResult result) {
        switch (result) {
            case CLOSED:
            case ALREADY_CANCELED:
                return orderId;
            case NOT_FOUND:
                throw new BusinessException(ORDER_NOT_FOUND);
            case NOT_CLOSABLE:
                throw new BusinessException(STATUS_NOT_CANCELABLE);
            case DATA_INCONSISTENT:
                throw new IllegalStateException(DATA_INCONSISTENT + "，orderId=" + orderId);
        }
        throw new IllegalStateException(DATA_INCONSISTENT + "，orderId=" + orderId);
    }
}
