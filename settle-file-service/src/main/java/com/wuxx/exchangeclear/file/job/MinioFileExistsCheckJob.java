package com.wuxx.exchangeclear.file.job;

import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
import com.wuxx.exchangeclear.redis.RedisKeys;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
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
public class MinioFileExistsCheckJob {

    private final SettleFileMapper settleFileMapper;

    private final ObjectStorageService objectStorageService;

    private final RedissonClient redissonClient;

    @Value("${exchange-clear.reconcile.minio-check-enabled:true}")
    private boolean enabled;

    @Value("${exchange-clear.reconcile.minio-check-days:7}")
    private int checkDays;

    @Value("${exchange-clear.reconcile.lock-wait-seconds:1}")
    private long waitSeconds;

    @Value("${exchange-clear.reconcile.lock-lease-seconds:120}")
    private long leaseSeconds;

    @Scheduled(fixedDelayString = "${exchange-clear.reconcile.minio-check-delay-millis:300000}")
    public void check() {
        if (!enabled) {
            return;
        }
        executeWithLock("minio-file-check", this::checkFiles);
    }

    private void checkFiles() {
        for (SettleFile file : settleFileMapper.listRecentFiles(checkDays)) {
            try {
                if (!objectStorageService.exists(file.getStoragePath())) {
                    log.warn("MinIO文件缺失, fileNo={}, status={}, storagePath={}",
                            file.getFileNo(), file.getStatus(), file.getStoragePath());
                }
            } catch (Exception e) {
                log.error("MinIO文件存在性检查异常, fileNo={}, storagePath={}",
                        file.getFileNo(), file.getStoragePath(), e);
            }
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
