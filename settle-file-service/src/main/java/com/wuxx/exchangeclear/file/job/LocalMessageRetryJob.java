package com.wuxx.exchangeclear.file.job;

import com.wuxx.exchangeclear.file.service.LocalMessageService;
import com.wuxx.exchangeclear.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageRetryJob {

    private final LocalMessageService localMessageService;

    private final RedissonClient redissonClient;

    @Value("${exchange-clear.reconcile.local-message-enabled:true}")
    private boolean enabled;

    @Value("${exchange-clear.reconcile.lock-wait-seconds:1}")
    private long waitSeconds;

    @Value("${exchange-clear.reconcile.lock-lease-seconds:120}")
    private long leaseSeconds;

    @Scheduled(fixedDelayString = "${exchange-clear.reconcile.local-message-delay-millis:60000}")
    public void retry() {
        if (!enabled) {
            return;
        }
        String lockKey = RedisKeys.reconcileLock("local-message");
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            localMessageService.retryDueMessages();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("本地消息补偿任务异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
