package com.linklife.identity.service.impl;

import com.linklife.identity.dto.LoginFormDTO;
import com.linklife.common.core.api.Result;
import com.linklife.identity.dto.UserDTO;
import com.linklife.identity.entity.User;
import com.linklife.identity.mapper.UserMapper;
import com.linklife.common.core.context.UserContext;
import com.linklife.identity.config.RuntimeSecurityProperties;
import com.linklife.identity.security.OtpCodeGenerator;
import com.linklife.identity.security.VerificationCodePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录验证码生命周期：发送冷却、错误次数限制、一次性消费与 Token 写入语义的单元测试。
 * 不依赖真实 MySQL / Redis，全部使用 Mockito。
 */
class UserServiceImplTest {

    private static final String PHONE = "13800138000";
    private static final String CODE_KEY = "identity:login:code:" + PHONE;
    private static final String COOLDOWN_KEY = "identity:login:code:cooldown:" + PHONE;
    private static final String ATTEMPT_KEY = "identity:login:code:attempt:" + PHONE;
    private static final String LOCK_KEY = "identity:lock:login:code:" + PHONE;
    private static final String CODE = "123456";

    private UserServiceImpl service;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private UserMapper userMapper;
    private RedissonClient redissonClient;
    private RLock lock;
    private OtpCodeGenerator otpCodeGenerator;

    @BeforeEach
    void setUp() {
        service = spy(new UserServiceImpl());
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        userMapper = mock(UserMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        otpCodeGenerator = mock(OtpCodeGenerator.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(otpCodeGenerator.generate()).thenReturn(CODE);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        ReflectionTestUtils.setField(service, "otpCodeGenerator", otpCodeGenerator);
        // 默认提供可用受控控制台通道：非生产 + console 开启 + 发布成功
        RuntimeSecurityProperties runtime = new RuntimeSecurityProperties();
        runtime.setConsoleVerificationCodeEnabled(true);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        when(publisher.publishDevCode(anyString(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "runtimeSecurityProperties", runtime);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);
    }

    private LoginFormDTO loginForm(String code) {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(PHONE);
        form.setCode(code);
        return form;
    }

    private User existingUser() {
        User user = new User();
        user.setId(1L);
        user.setPhone(PHONE);
        user.setNickName("nick");
        user.setIcon("icon");
        return user;
    }

    @Test
    void invalidPhoneDoesNotWriteRedis() {
        Result result = service.sendCode("123", null);

        assertThat(result.getSuccess()).isFalse();
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void firstSendCreatesCooldownKey() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isTrue();
        verify(valueOps).setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS);
        verify(valueOps).set(eq(CODE_KEY), anyString(), eq(2L), eq(TimeUnit.MINUTES));
    }

    @Test
    void repeatedSendWithinCooldownRejected() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(false);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送过于频繁，请稍后再试");
        verify(valueOps, never()).set(eq(CODE_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void setIfAbsentNullFailsClosed() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(null);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码服务暂时不可用");
        verify(valueOps, never()).set(eq(CODE_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void codeWriteFailureDeletesCooldownKey() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(eq(CODE_KEY), anyString(), anyLong(), any(TimeUnit.class));

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送失败，请稍后再试");
        verify(redisTemplate).delete(COOLDOWN_KEY);
    }

    @Test
    void successfulSendCleansOldAttemptKey() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isTrue();
        verify(redisTemplate).delete(ATTEMPT_KEY);
    }

    @Test
    void missingCodeFailsWithoutToken() {
        when(valueOps.get(CODE_KEY)).thenReturn(null);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码已过期或不存在");
        verify(hashOps, never()).putAll(anyString(), anyMap());
        verify(service, never()).query();
    }

    @Test
    void wrongCodeIncrementsAttemptAtomically() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(1L);

        Result result = service.login(loginForm("000000"), null);

        assertThat(result.getErrorMsg()).isEqualTo("验证码错误");
        verify(redisTemplate).execute(any(RedisScript.class), eq(Arrays.asList(CODE_KEY, ATTEMPT_KEY)));
    }

    @Test
    void firstFourWrongAttemptsKeepCodeAndAttempt() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(1L, 2L, 3L, 4L);

        for (int i = 0; i < 4; i++) {
            Result result = service.login(loginForm("000000"), null);
            assertThat(result.getErrorMsg()).isEqualTo("验证码错误");
        }
        verify(redisTemplate, times(4)).execute(any(RedisScript.class), anyList());
        verify(redisTemplate, never()).delete(CODE_KEY);
        verify(redisTemplate, never()).delete(ATTEMPT_KEY);
    }

    @Test
    void fifthWrongAttemptDeletesCodeAndAttempt() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(5L);
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        Result result = service.login(loginForm("000000"), null);

        assertThat(result.getErrorMsg()).isEqualTo("验证码错误次数过多，请重新获取");
        verify(redisTemplate).delete(eq(Arrays.asList(CODE_KEY, ATTEMPT_KEY)));
    }

    @Test
    void attemptIncrementNullFailsClosed() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(null);

        Result result = service.login(loginForm("000000"), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("登录失败，请稍后再试");
        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    @Test
    void correctCodeLogsInAndReturnsToken() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenReturn(true);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNotNull();
        verify(hashOps).putAll(startsWith("identity:login:token:"), anyMap());
        verify(redisTemplate).expire(startsWith("identity:login:token:"), eq(36000L), eq(TimeUnit.MINUTES));
    }

    @Test
    void successDeletesCodeAndAttempt() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenReturn(true);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isTrue();
        verify(redisTemplate).delete(CODE_KEY);
        verify(redisTemplate).delete(ATTEMPT_KEY);
    }

    @Test
    void successDoesNotDeleteCooldown() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenReturn(true);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isTrue();
        verify(redisTemplate, never()).delete(COOLDOWN_KEY);
    }

    @Test
    void tokenWriteFailureKeepsCodeAndNoToken() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("登录失败，请稍后再试");
        assertThat(result.getData()).isNull();
        verify(redisTemplate, never()).delete(CODE_KEY);
    }

    @Test
    void codeCannotBeReusedAfterSuccessfulLogin() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenReturn(true);

        Result first = service.login(loginForm(CODE), null);
        assertThat(first.getSuccess()).isTrue();

        Result second = service.login(loginForm(CODE), null);
        assertThat(second.getSuccess()).isFalse();
        assertThat(second.getErrorMsg()).isEqualTo("验证码已过期或不存在");
    }

    @Test
    void sendCodeAcquiresPhoneLockBeforeCooldownCheck() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);

        service.sendCode(PHONE, null);

        InOrder inOrder = inOrder(lock, valueOps);
        inOrder.verify(lock).tryLock();
        inOrder.verify(valueOps).setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS);
    }

    @Test
    void loginAcquiresPhoneLockBeforeReadingCode() {
        when(valueOps.get(CODE_KEY)).thenReturn(null);

        service.login(loginForm(CODE), null);

        InOrder inOrder = inOrder(lock, valueOps);
        inOrder.verify(lock).tryLock();
        inOrder.verify(valueOps).get(CODE_KEY);
    }

    @Test
    void sendCodeLockNotAcquiredSkipsRedis() {
        when(lock.tryLock()).thenReturn(false);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请求处理中，请稍后再试");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void loginLockNotAcquiredSkipsRedisAndToken() {
        when(lock.tryLock()).thenReturn(false);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请求处理中，请稍后再试");
        verify(valueOps, never()).get(anyString());
        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    @Test
    void unlockOnlyWhenHeldByCurrentThread() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        service.sendCode(PHONE, null);

        verify(lock).isHeldByCurrentThread();
        verify(lock, never()).unlock();
    }

    @Test
    void setIfAbsentThrowsFailsClosed() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码服务暂时不可用");
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void attemptResetFailureDeletesNewCodeAndCooldown() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete(ATTEMPT_KEY);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送失败，请稍后再试");
        verify(redisTemplate).delete(CODE_KEY);
        verify(redisTemplate).delete(COOLDOWN_KEY);
    }

    @Test
    void wrongCodeExecutesSingleLuaWithoutJavaGetExpireIncrementExpire() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(1L);

        Result result = service.login(loginForm("000000"), null);

        assertThat(result.getErrorMsg()).isEqualTo("验证码错误");
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList());
        verify(redisTemplate, never()).getExpire(anyString(), any(TimeUnit.class));
        verify(valueOps, never()).increment(anyString());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void luaMinusOneMapsToCodeExpired() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(-1L);

        Result result = service.login(loginForm("000000"), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码已过期或不存在");
        verify(redisTemplate, never()).delete(anyCollection());
        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    @Test
    void luaFailClosedValuesMapToStableFailure() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);

        for (Long code : new Long[]{-2L, -3L, 0L, -9L}) {
            when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(code);
            Result result = service.login(loginForm("000000"), null);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("登录失败，请稍后再试");
        }
        verify(redisTemplate, never()).delete(anyCollection());
        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    @Test
    void multiKeyDeleteNullDoesNotReturnLimitMessage() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(5L);
        when(redisTemplate.delete(anyCollection())).thenReturn(null);

        Result result = service.login(loginForm("000000"), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("登录失败，请稍后再试");
        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    @Test
    void createUserSaveFalseDoesNotWriteToken() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenReturn(0);

        assertThatThrownBy(() -> service.login(loginForm(CODE), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用户保存失败");

        verify(hashOps, never()).putAll(anyString(), anyMap());
        verify(redisTemplate, never()).expire(startsWith("identity:login:token:"), anyLong(), any(TimeUnit.class));
    }

    @Test
    void codeDeleteExceptionRevokesTokenAndFails() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete(CODE_KEY);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("登录失败，请稍后再试");
        assertThat(result.getData()).isNull();
        verify(redisTemplate).delete(startsWith("identity:login:token:"));
    }

    @Test
    void codeDeleteFalseStillSucceedsWithoutReusableCode() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenReturn(false);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNotNull();
    }

    @Test
    void inOrderVerifiesLockReadTokenConsumeUnlock() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenReturn(true);

        service.login(loginForm(CODE), null);

        InOrder inOrder = inOrder(lock, valueOps, hashOps, redisTemplate);
        inOrder.verify(lock).tryLock();
        inOrder.verify(valueOps).get(CODE_KEY);
        inOrder.verify(hashOps).putAll(startsWith("identity:login:token:"), anyMap());
        inOrder.verify(redisTemplate).delete(CODE_KEY);
        inOrder.verify(lock).isHeldByCurrentThread();
        inOrder.verify(lock).unlock();
    }

    @Test
    void secondLoginLockFailureCannotReturnToken() {
        when(lock.tryLock()).thenReturn(true, false);
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenReturn(true);

        Result first = service.login(loginForm(CODE), null);
        assertThat(first.getSuccess()).isTrue();

        Result second = service.login(loginForm(CODE), null);
        assertThat(second.getSuccess()).isFalse();
        assertThat(second.getErrorMsg()).isEqualTo("请求处理中，请稍后再试");
        assertThat(second.getData()).isNull();
        verify(valueOps, times(1)).get(CODE_KEY);
    }

    @Test
    void getLockThrowsSendCodeFailsClosed() {
        doThrow(new RuntimeException("redisson down")).when(redissonClient).getLock(LOCK_KEY);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码服务暂时不可用");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void getLockReturnsNullSendCodeFailsClosed() {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(null);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码服务暂时不可用");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void getLockThrowsLoginFailsClosed() {
        doThrow(new RuntimeException("redisson down")).when(redissonClient).getLock(LOCK_KEY);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("登录失败，请稍后再试");
        verify(valueOps, never()).get(anyString());
        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    @Test
    void getLockReturnsNullLoginFailsClosed() {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(null);

        Result result = service.login(loginForm(CODE), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("登录失败，请稍后再试");
        verify(valueOps, never()).get(anyString());
        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    @Test
    void attemptDeleteNullCleansCodeAndCooldownAndFails() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(null);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送失败，请稍后再试");
        verify(redisTemplate).delete(CODE_KEY);
        verify(redisTemplate).delete(COOLDOWN_KEY);
    }

    @Test
    void luaOneToFourMapsToCodeError() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);

        for (Long code : new Long[]{1L, 2L, 3L, 4L}) {
            when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(code);
            Result result = service.login(loginForm("000000"), null);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("验证码错误");
        }
    }

    @Test
    void luaFiveTriggersSingleMultiKeyDelete() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(5L);
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        Result result = service.login(loginForm("000000"), null);

        assertThat(result.getErrorMsg()).isEqualTo("验证码错误次数过多，请重新获取");
        verify(redisTemplate, times(1)).delete(anyCollection());
        verify(redisTemplate).delete(eq(Arrays.asList(CODE_KEY, ATTEMPT_KEY)));
    }

    @Test
    void multiKeyDeleteZeroOneTwoAllConfirmed() {
        when(valueOps.get(CODE_KEY)).thenReturn(CODE);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(5L);

        for (Long deleted : new Long[]{0L, 1L, 2L}) {
            when(redisTemplate.delete(anyCollection())).thenReturn(deleted);
            Result result = service.login(loginForm("000000"), null);

            assertThat(result.getErrorMsg()).isEqualTo("验证码错误次数过多，请重新获取");
        }
    }

    @Test
    void logoutValidTokenDeletesNewAndLegacySessionAndClearsContext() {
        when(redisTemplate.delete("identity:login:token:tok-1")).thenReturn(true);
        when(redisTemplate.delete("login:token:tok-1")).thenReturn(true);
        UserContext.set(1L);
        try {
            Result result = service.logout("tok-1");

            assertThat(result.getSuccess()).isTrue();
            verify(redisTemplate).delete("identity:login:token:tok-1");
            verify(redisTemplate).delete("login:token:tok-1");
            assertThat(UserContext.getUserId()).isNull();
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void logoutMissingSessionIsIdempotentSuccess() {
        when(redisTemplate.delete(anyString())).thenReturn(false);

        Result result = service.logout("tok-absent");

        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void logoutDeleteNullFailsClosed() {
        when(redisTemplate.delete(anyString())).thenReturn(null);
        UserContext.set(1L);
        try {
            Result result = service.logout("tok-1");

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("注销失败，请稍后再试");
            // null 结果不得伪装注销成功：不清空当前线程用户
            assertThat(UserContext.getUserId()).isNotNull();
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void logoutDeleteThrowsFailsClosed() {
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete(anyString());
        UserContext.set(1L);
        try {
            Result result = service.logout("tok-1");

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("注销失败，请稍后再试");
            assertThat(UserContext.getUserId()).isNotNull();
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void blankTokenDoesNotDeleteLoginTokenPrefix() {
        Result result = service.logout("   ");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("未登录");
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void logoutResponseDoesNotExposeToken() {
        when(redisTemplate.delete("identity:login:token:secret-token")).thenReturn(true);

        Result result = service.logout("secret-token");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        assertThat(result.getErrorMsg()).isNull();
    }

    @Test
    void consoleCodeDisabledDoesNotPublishCode() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        RuntimeSecurityProperties disabled = new RuntimeSecurityProperties();
        disabled.setConsoleVerificationCodeEnabled(false);
        ReflectionTestUtils.setField(service, "runtimeSecurityProperties", disabled);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        service.sendCode(PHONE, null);

        verify(publisher, never()).publishDevCode(anyString(), anyString());
    }

    @Test
    void consoleDisabledFailsBeforeTouchingRedis() {
        RuntimeSecurityProperties disabled = new RuntimeSecurityProperties();
        disabled.setConsoleVerificationCodeEnabled(false);
        ReflectionTestUtils.setField(service, "runtimeSecurityProperties", disabled);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码服务暂不可用");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(publisher, never()).publishDevCode(anyString(), anyString());
    }

    @Test
    void missingPublisherFailsBeforeTouchingRedis() {
        ReflectionTestUtils.setField(service, "verificationCodePublisher", null);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码服务暂不可用");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void publishFalseDeletesCodeAndCooldownAndFails() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        when(publisher.publishDevCode(anyString(), anyString())).thenReturn(false);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送失败，请稍后再试");
        verify(redisTemplate).delete(CODE_KEY);
        verify(redisTemplate).delete(COOLDOWN_KEY);
    }

    @Test
    void publishThrowsDeletesCodeAndCooldownAndFails() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        doThrow(new RuntimeException("publisher down"))
                .when(publisher).publishDevCode(anyString(), anyString());
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送失败，请稍后再试");
        verify(redisTemplate).delete(CODE_KEY);
        verify(redisTemplate).delete(COOLDOWN_KEY);
    }

    @Test
    void publishTrueReturnsSuccessAndKeepsCode() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        when(publisher.publishDevCode(anyString(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isTrue();
        verify(redisTemplate, never()).delete(CODE_KEY);
        verify(redisTemplate, never()).delete(COOLDOWN_KEY);
    }

    @Test
    void productionPublisherRefusalNeverReturnsSuccess() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        when(publisher.publishDevCode(anyString(), anyString())).thenReturn(false);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
    }

    @Test
    void cleanupFailureStillReturnsFailureWithoutLeakingCode() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        when(redisTemplate.delete(CODE_KEY)).thenThrow(new RuntimeException("redis down"));
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        when(publisher.publishDevCode(anyString(), anyString())).thenReturn(false);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送失败，请稍后再试");
        assertThat(result.getData()).isNull();
    }

    @Test
    void nonProdConsoleCodeEnabledPublishesViaControlledPath() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);
        RuntimeSecurityProperties runtime = new RuntimeSecurityProperties();
        runtime.setConsoleVerificationCodeEnabled(true);
        VerificationCodePublisher publisher = mock(VerificationCodePublisher.class);
        ReflectionTestUtils.setField(service, "runtimeSecurityProperties", runtime);
        ReflectionTestUtils.setField(service, "verificationCodePublisher", publisher);

        service.sendCode(PHONE, null);

        verify(publisher).publishDevCode(eq(PHONE), anyString());
    }

    @Test
    void sendCodeResultDoesNotContainCode() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.delete(ATTEMPT_KEY)).thenReturn(true);

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        assertThat(result.getErrorMsg()).isNull();
    }

    @Test
    void sendCodeFailureResultDoesNotContainCode() {
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(eq(CODE_KEY), anyString(), anyLong(), any(TimeUnit.class));

        Result result = service.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("验证码发送失败，请稍后再试");
    }

    @Test
    void userServiceImplLogsDoNotPassRawPhone() throws Exception {
        String source = new String(
                java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("src/main/java/com/linklife/identity/service/impl/UserServiceImpl.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        // 日志调用不得把原始 phone 直接作为参数传入（必须经 SensitiveDataMasker.maskPhone）
        String rawPhoneMarker = "phone" + "={}" + ", phone";
        assertThat(source).doesNotContain(rawPhoneMarker);
        assertThat(source).contains("SensitiveDataMasker.maskPhone(phone)");
    }
}
