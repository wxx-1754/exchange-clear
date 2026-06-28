package com.wuxx.exchangeclear.file.job;

import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
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
public class FileStateReconcileJob {

    private final SettleFileMapper settleFileMapper;

    private final RedissonClient redissonClient;

    @Value("${exchange-clear.reconcile.file-state-enabled:true}")
    private boolean enabled;

    @Value("${exchange-clear.reconcile.lock-wait-seconds:1}")
    private long waitSeconds;

    @Value("${exchange-clear.reconcile.lock-lease-seconds:120}")
    private long leaseSeconds;

    @Scheduled(fixedDelayString = "${exchange-clear.reconcile.file-state-delay-millis:300000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        executeWithLock("file-state", this::logAbnormalFiles);
    }

    private void logAbnormalFiles() {
        for (SettleFile file : settleFileMapper.listInvalidPublishedFiles()) {
            log.warn("已发布文件元数据不完整, fileNo={}, memberId={}, settleDate={}",
                    file.getFileNo(), file.getMemberId(), file.getSettleDate());
        }
        for (SettleFile file : settleFileMapper.listReissuedMissingNewVersion()) {
            log.warn("已重发旧文件未发现新版本文件, fileNo={}, memberId={}, settleDate={}, version={}",
                    file.getFileNo(), file.getMemberId(), file.getSettleDate(), file.getVersion());
        }
    }

    private void executeWithLock(String jobName, Runnable runnable) {
        String lockKey = RedisKeys.reconcileLock(jobName);
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (locked) {
                runnable.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
