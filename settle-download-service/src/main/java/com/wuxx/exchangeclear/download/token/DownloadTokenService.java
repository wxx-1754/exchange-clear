package com.wuxx.exchangeclear.download.token;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.ServiceUnavailableException;
import com.wuxx.exchangeclear.download.config.DownloadSecurityProperties;
import com.wuxx.exchangeclear.download.dto.DownloadTokenPayload;
import com.wuxx.exchangeclear.download.dto.DownloadTokenResponse;
import com.wuxx.exchangeclear.download.limit.DownloadLimitService;
import com.wuxx.exchangeclear.file.client.FileMetadataClient;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.redis.RedisJsonUtils;
import com.wuxx.exchangeclear.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadTokenService {

    private final StringRedisTemplate stringRedisTemplate;

    private final FileMetadataClient fileMetadataClient;

    private final DownloadLimitService downloadLimitService;

    private final DownloadSecurityProperties downloadSecurityProperties;

    public DownloadTokenResponse createToken(String fileNo, String memberId, String clientIp) {
        validateIdentity(memberId, clientIp);
        downloadLimitService.check(memberId, clientIp);

        FileMetadataDTO file = fileMetadataClient.getMetadata(fileNo);
        validateFilePermission(file, memberId);

        String token = UUID.randomUUID().toString().replace("-", "");
        DownloadTokenPayload payload = new DownloadTokenPayload();
        payload.setToken(token);
        payload.setFileNo(fileNo);
        payload.setMemberId(memberId);
        payload.setClientIp(clientIp);
        payload.setExpireAt(LocalDateTime.now().plusSeconds(downloadSecurityProperties.getTokenExpireSeconds()));
        payload.setOneTime(downloadSecurityProperties.isOneTimeToken());

        try {
            stringRedisTemplate.opsForValue().set(
                    RedisKeys.downloadToken(token),
                    RedisJsonUtils.toJson(payload),
                    Duration.ofSeconds(downloadSecurityProperties.getTokenExpireSeconds()));
        } catch (Exception e) {
            log.error("[REDIS] create download token failed, fileNo={}, memberId={}", fileNo, memberId, e);
            throw new ServiceUnavailableException(503001, "下载服务暂时不可用，请稍后重试");
        }

        return new DownloadTokenResponse(token, downloadSecurityProperties.getTokenExpireSeconds());
    }

    public FileMetadataDTO validateToken(String fileNo, String token, String memberId, String clientIp) {
        validateIdentity(memberId, clientIp);
        downloadLimitService.check(memberId, clientIp);
        if (!StringUtils.hasText(token)) {
            throw new BizException("下载 Token 不能为空");
        }

        DownloadTokenPayload payload = readToken(token);
        if (!fileNo.equals(payload.getFileNo())) {
            throw new BizException("下载 Token 与文件不匹配");
        }
        if (!memberId.equals(payload.getMemberId())) {
            throw new BizException("下载 Token 与会员不匹配");
        }
        if (downloadSecurityProperties.isBindIp() && !clientIp.equals(payload.getClientIp())) {
            throw new BizException("下载 Token 与客户端 IP 不匹配");
        }
        if (payload.getExpireAt() != null && payload.getExpireAt().isBefore(LocalDateTime.now())) {
            deleteToken(token);
            throw new BizException("下载 Token 已过期");
        }

        FileMetadataDTO file = fileMetadataClient.getMetadata(fileNo);
        validateFilePermission(file, memberId);
        if (Boolean.TRUE.equals(payload.getOneTime())) {
            deleteToken(token);
        }
        return file;
    }

    private DownloadTokenPayload readToken(String token) {
        String value;
        try {
            value = stringRedisTemplate.opsForValue().get(RedisKeys.downloadToken(token));
        } catch (Exception e) {
            log.error("[REDIS] validate download token failed, token={}", token, e);
            throw new ServiceUnavailableException(503001, "下载服务暂时不可用，请稍后重试");
        }
        if (!StringUtils.hasText(value)) {
            throw new BizException("下载 Token 不存在或已过期");
        }
        return RedisJsonUtils.fromJson(value, DownloadTokenPayload.class);
    }

    private void validateFilePermission(FileMetadataDTO file, String memberId) {
        if (file == null) {
            throw new BizException("文件不存在");
        }
        if (!memberId.equals(file.getMemberId())) {
            throw new BizException("无权下载该文件");
        }
        if (!downloadSecurityProperties.getAllowedStatuses().contains(file.getStatus())) {
            throw new BizException("当前文件状态不允许下载：" + file.getStatus());
        }
    }

    private void validateIdentity(String memberId, String clientIp) {
        if (!StringUtils.hasText(memberId)) {
            throw new BizException("会员标识不能为空");
        }
        if (!StringUtils.hasText(clientIp)) {
            throw new BizException("客户端 IP 不能为空");
        }
    }

    private void deleteToken(String token) {
        try {
            stringRedisTemplate.delete(RedisKeys.downloadToken(token));
        } catch (Exception e) {
            log.warn("[REDIS] delete download token failed, token={}", token, e);
        }
    }
}
