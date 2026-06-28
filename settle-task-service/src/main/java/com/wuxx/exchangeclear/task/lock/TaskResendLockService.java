package com.wuxx.exchangeclear.task.lock;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskResendLockService {

    private final RedissonClient redissonClient;

    @Value("${exchange-clear.lock.task-resend.wait-seconds:3}")
    private long waitSeconds;

    @Value("${exchange-clear.lock.task-resend.lease-seconds:30}")
    private long leaseSeconds;

    public <T> T execute(String taskNo, Supplier<T> supplier) {
        String lockKey = RedisKeys.taskResendLock(taskNo);
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException("任务正在重投中，请稍后重试：" + taskNo);
            }
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("任务重投被中断，请稍后重试：" + taskNo);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[REDIS] task resend lock failed, taskNo={}, lockKey={}", taskNo, lockKey, e);
            throw new BizException("任务重投锁获取失败，请稍后重试：" + taskNo);
        } finally {
            if (locked) {
                unlock(lock, lockKey);
            }
        }
    }

    private void unlock(RLock lock, String lockKey) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[REDIS] unlock task resend lock failed, lockKey={}", lockKey, e);
        }
    }
}
