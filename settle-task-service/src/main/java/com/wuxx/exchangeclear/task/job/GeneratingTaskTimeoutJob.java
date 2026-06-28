package com.wuxx.exchangeclear.task.job;

import com.wuxx.exchangeclear.redis.RedisKeys;
import com.wuxx.exchangeclear.task.mapper.SettleFileTaskMapper;
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
public class GeneratingTaskTimeoutJob {

    private final SettleFileTaskMapper settleFileTaskMapper;

    private final RedissonClient redissonClient;

    @Value("${exchange-clear.reconcile.generating-timeout-enabled:true}")
    private boolean enabled;

    @Value("${exchange-clear.reconcile.generating-timeout-minutes:30}")
    private int timeoutMinutes;

    @Value("${exchange-clear.reconcile.lock-wait-seconds:1}")
    private long waitSeconds;

    @Value("${exchange-clear.reconcile.lock-lease-seconds:120}")
    private long leaseSeconds;

    @Scheduled(fixedDelayString = "${exchange-clear.reconcile.generating-timeout-delay-millis:60000}")
    public void recoverTimeoutTasks() {
        if (!enabled) {
            return;
        }
        String lockKey = RedisKeys.reconcileLock("generating-timeout");
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            int affected = settleFileTaskMapper.markGeneratingTimeout(timeoutMinutes, "任务生成超时");
            if (affected > 0) {
                log.warn("生成中任务超时补偿完成, affected={}, timeoutMinutes={}", affected, timeoutMinutes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("生成中任务超时补偿异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
