package com.wuxx.exchangeclear.download.limit;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.RateLimitException;
import com.wuxx.exchangeclear.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadLimitService {

    private static final int MEMBER_LIMIT_CODE = 429001;

    private static final int IP_LIMIT_CODE = 429002;

    private static final DefaultRedisScript<Long> LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('incr', KEYS[1]); " +
                    "if current == 1 then redis.call('pexpire', KEYS[1], ARGV[2]); end; " +
                    "if current > tonumber(ARGV[1]) then return 0 else return 1 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final DownloadLimitProperties properties;

    public void check(String memberId, String clientIp) {
        if (properties.getIp().isEnabled()) {
            checkLimit(RedisKeys.ipLimit(clientIp), properties.getIp().getPermitsPerSecond(),
                    IP_LIMIT_CODE, "IP 请求过于频繁，请稍后重试");
        }
        if (properties.getMember().isEnabled()) {
            checkLimit(RedisKeys.memberLimit(memberId), properties.getMember().getPermitsPerSecond(),
                    MEMBER_LIMIT_CODE, "会员请求过于频繁，请稍后重试");
        }
    }

    private void checkLimit(String key, int permitsPerSecond, int code, String message) {
        if (permitsPerSecond <= 0) {
            throw new BizException("限流阈值配置错误：" + key);
        }
        try {
            Long allowed = stringRedisTemplate.execute(
                    LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(permitsPerSecond),
                    "1000");
            if (allowed == null || allowed == 0L) {
                throw new RateLimitException(code, message);
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[REDIS] download rate limit failed, key={}", key, e);
            if (!properties.isFailOpen()) {
                throw new RateLimitException(429, "下载限流服务暂时不可用，请稍后重试");
            }
        }
    }
}
