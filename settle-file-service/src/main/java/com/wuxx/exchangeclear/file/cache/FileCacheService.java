package com.wuxx.exchangeclear.file.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.FileVO;
import com.wuxx.exchangeclear.redis.RedisJsonUtils;
import com.wuxx.exchangeclear.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileCacheService {

    private static final String NULL_VALUE = "NULL";

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    @Value("${exchange-clear.cache.file-list.ttl-seconds:600}")
    private long fileListTtlSeconds;

    @Value("${exchange-clear.cache.file-list.null-ttl-seconds:60}")
    private long fileListNullTtlSeconds;

    @Value("${exchange-clear.cache.file-meta.ttl-seconds:1800}")
    private long fileMetaTtlSeconds;

    @Value("${exchange-clear.cache.file-meta.null-ttl-seconds:60}")
    private long fileMetaNullTtlSeconds;

    @Value("${exchange-clear.lock.cache.wait-millis:100}")
    private long cacheLockWaitMillis;

    @Value("${exchange-clear.lock.cache.lease-seconds:10}")
    private long cacheLockLeaseSeconds;

    public List<FileVO> getFileList(LocalDate settleDate, String memberId, Supplier<List<FileVO>> dbLoader) {
        String key = RedisKeys.fileList(settleDate, memberId);
        String value = getValue(key);
        if (StringUtils.hasText(value)) {
            return RedisJsonUtils.fromJson(value, new TypeReference<List<FileVO>>() {
            });
        }

        String lockKey = RedisKeys.fileListCacheLock(settleDate, memberId);
        return withCacheLock(lockKey, dbLoader, () -> {
            String cachedValue = getValue(key);
            if (StringUtils.hasText(cachedValue)) {
                return RedisJsonUtils.fromJson(cachedValue, new TypeReference<List<FileVO>>() {
                });
            }
            List<FileVO> files = dbLoader.get();
            Duration ttl = files.isEmpty()
                    ? Duration.ofSeconds(fileListNullTtlSeconds)
                    : Duration.ofSeconds(fileListTtlSeconds);
            setValue(key, RedisJsonUtils.toJson(files), ttl);
            return files;
        });
    }

    public FileMetadataDTO getFileMeta(String fileNo, Supplier<FileMetadataDTO> dbLoader) {
        String key = RedisKeys.fileMeta(fileNo);
        String value = getValue(key);
        if (StringUtils.hasText(value)) {
            if (NULL_VALUE.equals(value)) {
                return null;
            }
            return RedisJsonUtils.fromJson(value, FileMetadataDTO.class);
        }

        String lockKey = RedisKeys.fileMetaCacheLock(fileNo);
        return withCacheLock(lockKey, dbLoader, () -> {
            String cachedValue = getValue(key);
            if (StringUtils.hasText(cachedValue)) {
                if (NULL_VALUE.equals(cachedValue)) {
                    return null;
                }
                return RedisJsonUtils.fromJson(cachedValue, FileMetadataDTO.class);
            }
            FileMetadataDTO file = dbLoader.get();
            if (file == null) {
                setValue(key, NULL_VALUE, Duration.ofSeconds(fileMetaNullTtlSeconds));
                return null;
            }
            setValue(key, RedisJsonUtils.toJson(file), Duration.ofSeconds(fileMetaTtlSeconds));
            return file;
        });
    }

    public void evictFileList(LocalDate settleDate, String memberId) {
        try {
            stringRedisTemplate.delete(RedisKeys.fileList(settleDate, memberId));
        } catch (Exception e) {
            log.warn("[REDIS] evict file list cache failed, settleDate={}, memberId={}", settleDate, memberId, e);
        }
    }

    public void evictFileMeta(String fileNo) {
        try {
            stringRedisTemplate.delete(RedisKeys.fileMeta(fileNo));
        } catch (Exception e) {
            log.warn("[REDIS] evict file meta cache failed, fileNo={}", fileNo, e);
        }
    }

    private <T> T withCacheLock(String lockKey, Supplier<T> fallbackLoader, Supplier<T> lockedLoader) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(cacheLockWaitMillis, cacheLockLeaseSeconds * 1000, TimeUnit.MILLISECONDS);
            if (locked) {
                return lockedLoader.get();
            }
            TimeUnit.MILLISECONDS.sleep(50);
            return fallbackLoader.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackLoader.get();
        } catch (Exception e) {
            log.warn("[REDIS] cache fallback to database, lockKey={}", lockKey, e);
            return fallbackLoader.get();
        } finally {
            if (locked) {
                unlock(lock, lockKey);
            }
        }
    }

    private String getValue(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("[REDIS] get cache failed, key={}", key, e);
            return null;
        }
    }

    private void setValue(String key, String value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("[REDIS] set cache failed, key={}", key, e);
        }
    }

    private void unlock(RLock lock, String lockKey) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[REDIS] unlock cache lock failed, lockKey={}", lockKey, e);
        }
    }
}
