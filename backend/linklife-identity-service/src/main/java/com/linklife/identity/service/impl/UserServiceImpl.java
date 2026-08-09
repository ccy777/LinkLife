package com.linklife.identity.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.contract.SessionKeyContract;
import com.linklife.common.core.util.SystemConstants;
import com.linklife.identity.dto.LoginFormDTO;
import com.linklife.identity.dto.UserDTO;
import com.linklife.identity.entity.User;
import com.linklife.identity.mapper.UserMapper;
import com.linklife.identity.config.RuntimeSecurityProperties;
import com.linklife.identity.security.OtpCodeGenerator;
import com.linklife.identity.service.IUserService;
import com.linklife.identity.security.RegexUtils;
import com.linklife.identity.security.SensitiveDataMasker;
import com.linklife.identity.security.VerificationCodePublisher;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.linklife.identity.redis.IdentityRedisConstants.*;
import static com.linklife.common.core.util.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final DefaultRedisScript<Long> LOGIN_CODE_ATTEMPT_SCRIPT;

    static {
        LOGIN_CODE_ATTEMPT_SCRIPT = new DefaultRedisScript<>();
        LOGIN_CODE_ATTEMPT_SCRIPT.setLocation(new ClassPathResource("login-code-attempt.lua"));
        LOGIN_CODE_ATTEMPT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RuntimeSecurityProperties runtimeSecurityProperties;
    @Resource
    private VerificationCodePublisher verificationCodePublisher;
    @Resource
    private OtpCodeGenerator otpCodeGenerator;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.获取手机号共享锁（getLock、null 校验与 tryLock 在同一 fail-closed 边界内）
        RLock lock;
        try {
            lock = acquirePhoneCodeLock(phone);
        } catch (Exception e) {
            log.error("获取手机号验证码锁失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("验证码服务暂时不可用");
        }
        if (lock == null) {
            return Result.fail("请求处理中，请稍后再试");
        }
        try {
            return sendCodeUnderLock(phone);
        } finally {
            unlockPhoneCodeLock(lock, phone);
        }
    }

    private Result sendCodeUnderLock(String phone) {
        String cooldownKey = LOGIN_CODE_COOLDOWN_KEY + phone;
        String codeKey = LOGIN_CODE_KEY + phone;
        String attemptKey = LOGIN_CODE_ATTEMPT_KEY + phone;
        // 0.写前诚实交付判断：唯一可用通道是受控开发控制台输出；
        // 通道不可用（console 关闭/发布器缺失）时直接失败，不触碰 Redis
        if (runtimeSecurityProperties == null
                || !runtimeSecurityProperties.isConsoleVerificationCodeEnabled()
                || verificationCodePublisher == null) {
            log.warn("验证码交付通道不可用（console 开关关闭或发布器缺失），拒绝发送 phone={}",
                    SensitiveDataMasker.maskPhone(phone));
            return Result.fail("验证码服务暂不可用");
        }
        // 1.原子建立发送冷却（setIfAbsent，TTL 60s）；null 或异常 fail-closed，不发送、不覆盖验证码
        Boolean cooldownCreated;
        try {
            cooldownCreated = stringRedisTemplate.opsForValue().setIfAbsent(
                    cooldownKey, "1", LOGIN_CODE_COOLDOWN_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("建立验证码冷却失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("验证码服务暂时不可用");
        }
        if (cooldownCreated == null) {
            return Result.fail("验证码服务暂时不可用");
        }
        if (!cooldownCreated) {
            return Result.fail("验证码发送过于频繁，请稍后再试");
        }
        // 2.生成并写入新验证码，同时重置旧错误尝试计数
        String code = otpCodeGenerator.generate();
        try {
            stringRedisTemplate.opsForValue().set(codeKey, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
            Boolean attemptDeleted = stringRedisTemplate.delete(attemptKey);
            if (attemptDeleted == null) {
                // 状态未知：不得返回发送成功
                throw new IllegalStateException("验证码 attempt 重置结果未知");
            }
            if (Boolean.FALSE.equals(attemptDeleted)) {
                // 原本无错误计数，视为正常
                log.debug("发送验证码时无旧错误计数 phone={}", SensitiveDataMasker.maskPhone(phone));
            }
        } catch (Exception e) {
            // 新验证码写入或 attempt 重置失败：不返回发送成功，尽力删除本次新验证码与冷却
            try {
                stringRedisTemplate.delete(codeKey);
                stringRedisTemplate.delete(cooldownKey);
            } catch (Exception deleteEx) {
                log.warn("清理新验证码/冷却失败 phone={}", SensitiveDataMasker.maskPhone(phone), deleteEx);
            }
            log.error("验证码写入或 attempt 重置失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("验证码发送失败，请稍后再试");
        }
        // 3.验证码输出：只有发布器确认成功才返回发送成功；
        // 发布返回 false（如生产拒绝）或抛异常：清理本次验证码与冷却并返回失败
        boolean published;
        try {
            published = verificationCodePublisher.publishDevCode(phone, code);
        } catch (Exception e) {
            cleanupCodeAndCooldown(codeKey, cooldownKey, phone);
            log.error("验证码发布异常，清理本次验证码与冷却 phone={}",
                    SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("验证码发送失败，请稍后再试");
        }
        if (!published) {
            cleanupCodeAndCooldown(codeKey, cooldownKey, phone);
            log.warn("验证码交付被拒绝（如生产环境），清理本次验证码与冷却 phone={}",
                    SensitiveDataMasker.maskPhone(phone));
            return Result.fail("验证码发送失败，请稍后再试");
        }
        return Result.ok();
    }

    /**
     * 发布失败后的 Redis 清理：尽力删除本次验证码与冷却。
     * 清理失败只记录脱敏手机号，不输出验证码，不把清理异常转换为成功。
     */
    private void cleanupCodeAndCooldown(String codeKey, String cooldownKey, String phone) {
        try {
            stringRedisTemplate.delete(codeKey);
        } catch (Exception e) {
            log.warn("清理本次验证码失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
        }
        try {
            stringRedisTemplate.delete(cooldownKey);
        } catch (Exception e) {
            log.warn("清理本次冷却失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
        }
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.获取手机号共享锁（getLock、null 校验与 tryLock 在同一 fail-closed 边界内）
        RLock lock;
        try {
            lock = acquirePhoneCodeLock(phone);
        } catch (Exception e) {
            log.error("获取手机号验证码锁失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("登录失败，请稍后再试");
        }
        if (lock == null) {
            return Result.fail("请求处理中，请稍后再试");
        }
        try {
            return loginUnderLock(loginForm, phone);
        } finally {
            unlockPhoneCodeLock(lock, phone);
        }
    }

    private Result loginUnderLock(LoginFormDTO loginForm, String phone) {
        String codeKey = LOGIN_CODE_KEY + phone;
        String attemptKey = LOGIN_CODE_ATTEMPT_KEY + phone;
        // 1.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null) {
            // 验证码不存在或已过期：不创建用户、不生成 Token、不增加失败计数
            return Result.fail("验证码已过期或不存在");
        }
        if (!cacheCode.equals(code)) {
            // 验证码不匹配：原子增加错误尝试次数，达到上限后清理并强制重新获取
            return handleWrongCode(phone, codeKey, attemptKey);
        }

        // 2.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 3.判断用户是否存在
        if (user == null) {
            // 不存在，创建新用户并保存；save 返回 false 时抛异常，不生成 Token
            user = createUserWithPhone(phone);
        }

        // 4.保存用户信息到 redis中
        // 4.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 4.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 4.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        try {
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            // 4.4.设置token有效期
            Boolean ttlSet = stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
            if (ttlSet == null || !ttlSet) {
                throw new IllegalStateException("Token 有效期设置失败");
            }
        } catch (Exception e) {
            // Token 写入失败：不返回登录成功、不删除验证码、不返回 Token；尽力清理可能残留的 Token Key
            try {
                stringRedisTemplate.delete(tokenKey);
            } catch (Exception deleteEx) {
                log.warn("清理残留 Token Key 失败 phone={}", SensitiveDataMasker.maskPhone(phone), deleteEx);
            }
            log.error("登录 Token 写入 Redis 失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("登录失败，请稍后再试");
        }

        // 5.一次性消费验证码（仍在手机号锁内）：只有 Token 会话写入成功后才删除
        Boolean codeDeleted;
        try {
            codeDeleted = stringRedisTemplate.delete(codeKey);
        } catch (Exception e) {
            // 验证码状态未知：不得返回登录成功，尽力撤销刚创建的 Token
            try {
                stringRedisTemplate.delete(tokenKey);
            } catch (Exception deleteEx) {
                log.warn("撤销残留 Token Key 失败 phone={}", SensitiveDataMasker.maskPhone(phone), deleteEx);
            }
            log.error("登录成功后验证码消费失败（删除异常），撤销 Token phone={}", SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("登录失败，请稍后再试");
        }
        if (codeDeleted == null) {
            // 未知状态 fail-closed：不返回登录成功
            try {
                stringRedisTemplate.delete(tokenKey);
            } catch (Exception deleteEx) {
                log.warn("撤销残留 Token Key 失败 phone={}", SensitiveDataMasker.maskPhone(phone), deleteEx);
            }
            log.error("登录成功后验证码消费结果未知，撤销 Token phone={}", SensitiveDataMasker.maskPhone(phone));
            return Result.fail("登录失败，请稍后再试");
        }
        if (Boolean.FALSE.equals(codeDeleted)) {
            // 验证码已自然过期或不存在，不存在再次复用风险，可继续
            log.info("验证码在消费前已过期或不存在，按成功处理 phone={}", SensitiveDataMasker.maskPhone(phone));
        }
        // 6.验证码已不可复用后 best-effort 删除 attempt Key；不删除 cooldown
        try {
            stringRedisTemplate.delete(attemptKey);
        } catch (Exception e) {
            log.warn("登录成功后清理错误尝试计数失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
        }

        // 7.返回token
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        // 空 Token：不拼接任何前缀直接删除，返回稳定失败（固定契约）
        if (StrUtil.isBlank(token)) {
            return Result.fail("未登录");
        }
        String newKey = LOGIN_USER_KEY + token;
        String legacyKey = SessionKeyContract.LEGACY_SESSION_PREFIX + token;
        Boolean deleted;
        try {
            deleted = stringRedisTemplate.delete(newKey);
        } catch (Exception e) {
            // 不把 Token 写入日志
            log.error("注销删除会话失败", e);
            return Result.fail("注销失败，请稍后再试");
        }
        if (deleted == null) {
            log.error("注销删除会话结果未知");
            return Result.fail("注销失败，请稍后再试");
        }
        // best-effort 删除 Stage3 legacy 兼容 Key（同 token）
        try {
            stringRedisTemplate.delete(legacyKey);
        } catch (Exception e) {
            log.warn("注销清理 legacy 会话失败", e);
        }
        UserContext.clear();
        return Result.ok();
    }

    /**
     * 验证码不匹配：通过 Lua 原子完成错误计数与剩余 TTL 继承，达到上限后用一次多 Key DEL 清理。
     * 任何 Redis 未知状态 fail-closed，不创建用户、不生成 Token。
     */
    private Result handleWrongCode(String phone, String codeKey, String attemptKey) {
        // 1.一次 Lua 调用完成 PTTL 校验、INCR 与 PEXPIRE（返回契约见 login-code-attempt.lua）
        Long attempts;
        try {
            attempts = stringRedisTemplate.execute(
                    LOGIN_CODE_ATTEMPT_SCRIPT, Arrays.asList(codeKey, attemptKey));
        } catch (Exception e) {
            log.error("验证码错误计数脚本执行失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
            return Result.fail("登录失败，请稍后再试");
        }
        if (attempts == null) {
            log.warn("验证码错误计数脚本返回 null phone={}", SensitiveDataMasker.maskPhone(phone));
            return Result.fail("登录失败，请稍后再试");
        }
        if (attempts == -1L) {
            // 验证码不存在、已过期或 TTL 非正
            return Result.fail("验证码已过期或不存在");
        }
        if (attempts <= 0L) {
            // -2/-3/0/未知负值 fail-closed
            log.warn("验证码错误计数脚本返回异常值 phone={}, attempts={}",
                    SensitiveDataMasker.maskPhone(phone), attempts);
            return Result.fail("登录失败，请稍后再试");
        }
        // 2.达到或超过上限：一次多 Key DEL 删除验证码与 attempt Key
        if (attempts >= LOGIN_CODE_MAX_ATTEMPTS) {
            Long deleted;
            try {
                deleted = stringRedisTemplate.delete(Arrays.asList(codeKey, attemptKey));
            } catch (Exception e) {
                log.error("验证码错误次数达到上限后多 Key 清理失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
                return Result.fail("登录失败，请稍后再试");
            }
            if (deleted == null) {
                // 清理结果未知 fail-closed，不伪装成已经可靠锁定
                log.error("验证码错误次数达到上限后多 Key 清理结果未知 phone={}", SensitiveDataMasker.maskPhone(phone));
                return Result.fail("登录失败，请稍后再试");
            }
            return Result.fail("验证码错误次数过多，请重新获取");
        }
        return Result.fail("验证码错误");
    }

    /**
     * 统一锁获取：getLock、RLock null 校验与 tryLock 在同一保护边界内。
     * 返回 null 表示 tryLock 未获取到锁；getLock null/异常或 tryLock 异常由调用方 fail-closed。
     */
    private RLock acquirePhoneCodeLock(String phone) {
        RLock lock = redissonClient.getLock(LOGIN_CODE_LOCK_KEY + phone);
        if (lock == null) {
            throw new IllegalStateException("获取手机号验证码锁对象失败：getLock 返回 null");
        }
        boolean locked = lock.tryLock();
        return locked ? lock : null;
    }

    private void unlockPhoneCodeLock(RLock lock, String phone) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放手机号验证码锁失败 phone={}", SensitiveDataMasker.maskPhone(phone), e);
        }
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("未登录");
        }
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("未登录");
        }
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        boolean saved = save(user);
        if (!saved) {
            // 用户未落库：不生成 Token、不写 Token Redis Hash
            throw new IllegalStateException("用户保存失败，不生成 Token");
        }
        return user;
    }
}
