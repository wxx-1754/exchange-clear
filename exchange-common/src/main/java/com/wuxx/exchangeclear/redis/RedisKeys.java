package com.wuxx.exchangeclear.redis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class RedisKeys {

    private static final DateTimeFormatter SETTLE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String DEFAULT_PREFIX = "exchange-clear";

    private RedisKeys() {
    }

    public static String fileList(LocalDate settleDate, String memberId) {
        return DEFAULT_PREFIX + ":file:list:" + format(settleDate) + ":" + memberId;
    }

    public static String fileMeta(String fileNo) {
        return DEFAULT_PREFIX + ":file:meta:" + fileNo;
    }

    public static String downloadToken(String token) {
        return DEFAULT_PREFIX + ":download:token:" + token;
    }

    public static String memberLimit(String memberId) {
        return DEFAULT_PREFIX + ":limit:member:" + memberId;
    }

    public static String ipLimit(String ip) {
        return DEFAULT_PREFIX + ":limit:ip:" + ip;
    }

    public static String taskResendLock(String taskNo) {
        return DEFAULT_PREFIX + ":lock:task:resend:" + taskNo;
    }

    public static String filePublishLock(LocalDate settleDate) {
        return DEFAULT_PREFIX + ":lock:file:publish:" + format(settleDate);
    }

    public static String fileRevokeLock(String fileNo) {
        return DEFAULT_PREFIX + ":lock:file:revoke:" + fileNo;
    }

    public static String fileReissueLock(String fileNo) {
        return DEFAULT_PREFIX + ":lock:file:reissue:" + fileNo;
    }

    public static String reconcileLock(String jobName) {
        return DEFAULT_PREFIX + ":lock:reconcile:" + jobName;
    }

    public static String fileListCacheLock(LocalDate settleDate, String memberId) {
        return DEFAULT_PREFIX + ":lock:cache:file-list:" + format(settleDate) + ":" + memberId;
    }

    public static String fileMetaCacheLock(String fileNo) {
        return DEFAULT_PREFIX + ":lock:cache:file-meta:" + fileNo;
    }

    private static String format(LocalDate settleDate) {
        return settleDate.format(SETTLE_DATE_FORMATTER);
    }
}
