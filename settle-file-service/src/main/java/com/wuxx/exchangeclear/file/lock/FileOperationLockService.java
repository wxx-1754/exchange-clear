package com.wuxx.exchangeclear.file.lock;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileOperationLockService {

    private final RedissonClient redissonClient;

    @Value("${exchange-clear.publish.lock-wait-seconds:3}")
    private long publishWaitSeconds;

    @Value("${exchange-clear.publish.lock-lease-seconds:300}")
    private long publishLeaseSeconds;

    @Value("${exchange-clear.revoke.lock-wait-seconds:3}")
    private long revokeWaitSeconds;

    @Value("${exchange-clear.revoke.lock-lease-seconds:60}")
    private long revokeLeaseSeconds;

    @Value("${exchange-clear.reissue.lock-wait-seconds:3}")
    private long reissueWaitSeconds;

    @Value("${exchange-clear.reissue.lock-lease-seconds:60}")
    private long reissueLeaseSeconds;

    public <T> T publish(LocalDate settleDate, Supplier<T> supplier) {
        return execute(RedisKeys.filePublishLock(settleDate), "当前结算日期文件正在发布，请稍后重试",
                publishWaitSeconds, publishLeaseSeconds, supplier);
    }

    public <T> T revoke(String fileNo, Supplier<T> supplier) {
        return execute(RedisKeys.fileRevokeLock(fileNo), "当前文件正在撤销，请稍后重试",
                revokeWaitSeconds, revokeLeaseSeconds, supplier);
    }

    public <T> T reissue(String fileNo, Supplier<T> supplier) {
        return execute(RedisKeys.fileReissueLock(fileNo), "当前文件正在重发，请稍后重试",
                reissueWaitSeconds, reissueLeaseSeconds, supplier);
    }

    private <T> T execute(String lockKey, String lockFailedMessage,
                          long waitSeconds, long leaseSeconds, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(lockFailedMessage);
            }
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(lockFailedMessage);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[REDIS] file operation lock failed, lockKey={}", lockKey, e);
            throw new BizException(lockFailedMessage);
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
            log.warn("[REDIS] unlock file operation lock failed, lockKey={}", lockKey, e);
        }
    }
}
