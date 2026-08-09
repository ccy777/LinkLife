package com.linklife.social.client;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.common.core.user.UserSummaryRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Social 集中式用户目录：Social → Identity 唯一同步 RPC 边界（Stage 5A 语义拆分）。
 *
 * <p>Identity 作为同一个下游依赖共享单一 Sentinel resource
 * {@link #RESOURCE_NAME}（social.identity.user-summary）：</p>
 * <ul>
 *   <li>展示增强型 {@link #batchForDisplay(Collection)}：Identity 失败/熔断时记录失败并返回空
 *       user map，Blog 主体继续返回，绝不伪造默认用户；</li>
 *   <li>正确性依赖型 {@link #orderedRequired(Collection)} / {@link #existsRequired(long)}：
 *       Identity 失败/熔断时抛业务不可用（fail-closed），绝不返回空列表/false 伪装成功；</li>
 *   <li>真正 Identity 200 + missing id 时 {@code existsRequired} 才允许返回 false（“目标用户不存在”）。</li>
 * </ul>
 */
@Component
public class IdentityUserDirectory {

    /** Identity 下游单一 Sentinel 资源名：熔断状态由 Social 全体共享。 */
    public static final String RESOURCE_NAME = "social.identity.user-summary";

    private static final String UNAVAILABLE_MESSAGE = "用户服务暂时不可用，请稍后再试";

    private final IdentityUserClient identityUserClient;

    public IdentityUserDirectory(IdentityUserClient identityUserClient) {
        this.identityUserClient = identityUserClient;
    }

    /**
     * 展示增强型批量摘要：Identity 不可用时返回空 map（不向上抛、不伪造用户）。
     */
    public Map<Long, UserSummaryDTO> batchForDisplay(Collection<Long> ids) {
        Set<Long> unique = dedupePositive(ids);
        if (unique.isEmpty()) {
            return Map.of();
        }
        Entry entry = null;
        try {
            entry = SphU.entry(RESOURCE_NAME, EntryType.OUT);
            return doBatch(unique);
        } catch (BlockException ex) {
            // 熔断/流控：展示型降级为空 user map。
            return Map.of();
        } catch (RuntimeException ex) {
            Tracer.trace(ex);
            return Map.of();
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    /**
     * 正确性依赖型批量摘要：Identity 不可用时抛业务异常，不返回空列表伪装成功。
     */
    public List<UserSummaryDTO> orderedRequired(Collection<Long> ids) {
        Set<Long> unique = dedupePositive(ids);
        if (unique.isEmpty()) {
            return List.of();
        }
        Entry entry = null;
        try {
            entry = SphU.entry(RESOURCE_NAME, EntryType.OUT);
            return reorder(doBatch(unique), ids);
        } catch (BlockException ex) {
            throw new BusinessException(UNAVAILABLE_MESSAGE);
        } catch (RuntimeException ex) {
            Tracer.trace(ex);
            throw new BusinessException(UNAVAILABLE_MESSAGE, ex);
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    /**
     * 正确性依赖型存在性：Identity 不可用时抛业务异常；只有 Identity 正常响应且缺失时才返回 false。
     */
    public boolean existsRequired(long id) {
        if (id <= 0) {
            return false;
        }
        Entry entry = null;
        try {
            entry = SphU.entry(RESOURCE_NAME, EntryType.OUT);
            return doBatch(Set.of(id)).containsKey(id);
        } catch (BlockException ex) {
            throw new BusinessException(UNAVAILABLE_MESSAGE);
        } catch (RuntimeException ex) {
            Tracer.trace(ex);
            throw new BusinessException(UNAVAILABLE_MESSAGE, ex);
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private Map<Long, UserSummaryDTO> doBatch(Set<Long> unique) {
        List<UserSummaryDTO> response = identityUserClient.batch(new UserSummaryRequest(unique));
        if (response == null) {
            throw new IllegalStateException("Identity 用户摘要响应为空，fail-closed");
        }
        Map<Long, UserSummaryDTO> byId = new HashMap<>();
        for (UserSummaryDTO dto : response) {
            if (dto != null && dto.id() != null) {
                byId.put(dto.id(), dto);
            }
        }
        return byId;
    }

    private static List<UserSummaryDTO> reorder(Map<Long, UserSummaryDTO> byId, Collection<Long> ids) {
        List<UserSummaryDTO> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            if (id != null && byId.containsKey(id)) {
                result.add(byId.get(id));
            }
        }
        return result;
    }

    private static Set<Long> dedupePositive(Collection<Long> ids) {
        Set<Long> unique = new LinkedHashSet<>();
        if (ids == null) {
            return unique;
        }
        for (Long id : ids) {
            if (id != null && id > 0L) {
                unique.add(id);
            }
        }
        return unique;
    }
}
