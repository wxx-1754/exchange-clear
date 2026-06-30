package com.wuxx.exchangeclear.download.token;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.download.config.DownloadSecurityProperties;
import com.wuxx.exchangeclear.download.dto.DownloadTokenPayload;
import com.wuxx.exchangeclear.download.dto.DownloadTokenResponse;
import com.wuxx.exchangeclear.download.limit.DownloadLimitService;
import com.wuxx.exchangeclear.enums.FileStatusEnum;
import com.wuxx.exchangeclear.file.client.FileMetadataClient;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.redis.RedisJsonUtils;
import com.wuxx.exchangeclear.redis.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadTokenServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private FileMetadataClient fileMetadataClient;

    @Mock
    private DownloadLimitService downloadLimitService;

    private DownloadTokenService downloadTokenService;

    @BeforeEach
    void setUp() {
        DownloadSecurityProperties properties = new DownloadSecurityProperties();
        properties.setTokenExpireSeconds(300);
        properties.setAllowedStatuses(Collections.singletonList(FileStatusEnum.GENERATED.getCode()));
        downloadTokenService = new DownloadTokenService(
                stringRedisTemplate, fileMetadataClient, downloadLimitService, properties);
    }

    @Test
    void createTokenShouldStorePayloadWhenFileBelongsToMember() {
        when(fileMetadataClient.getMetadata("FILE001")).thenReturn(file("FILE001", "0001", FileStatusEnum.GENERATED.getCode()));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        DownloadTokenResponse response = downloadTokenService.createToken("FILE001", "0001", "127.0.0.1");

        assertEquals(300L, response.getExpireSeconds());
        verify(downloadLimitService).check("0001", "127.0.0.1");
        verify(valueOperations).set(eq(RedisKeys.downloadToken(response.getToken())), any(String.class), any());
    }

    @Test
    void createTokenShouldRejectOtherMemberFile() {
        when(fileMetadataClient.getMetadata("FILE001")).thenReturn(file("FILE001", "0002", FileStatusEnum.GENERATED.getCode()));

        assertThrows(BizException.class, () -> downloadTokenService.createToken("FILE001", "0001", "127.0.0.1"));

        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void validateTokenShouldReturnFileWhenPayloadMatches() {
        DownloadTokenPayload payload = new DownloadTokenPayload();
        payload.setToken("TOKEN001");
        payload.setFileNo("FILE001");
        payload.setMemberId("0001");
        payload.setClientIp("127.0.0.1");
        payload.setExpireAt(LocalDateTime.now().plusMinutes(5));
        payload.setOneTime(false);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.downloadToken("TOKEN001"))).thenReturn(RedisJsonUtils.toJson(payload));
        when(fileMetadataClient.getMetadata("FILE001")).thenReturn(file("FILE001", "0001", FileStatusEnum.GENERATED.getCode()));

        FileMetadataDTO file = downloadTokenService.validateToken("FILE001", "TOKEN001", "0001", "127.0.0.1");

        assertEquals("FILE001", file.getFileNo());
        verify(downloadLimitService).check("0001", "127.0.0.1");
        verify(stringRedisTemplate, never()).delete(RedisKeys.downloadToken("TOKEN001"));
    }

    @Test
    void validateTokenShouldResolveMemberFromTokenWhenHeaderAbsent() {
        // browser native download cannot carry X-Member-Id; memberId is null and the
        // token payload is the member credential
        DownloadTokenPayload payload = new DownloadTokenPayload();
        payload.setToken("TOKEN001");
        payload.setFileNo("FILE001");
        payload.setMemberId("0001");
        payload.setClientIp("127.0.0.1");
        payload.setExpireAt(LocalDateTime.now().plusMinutes(5));
        payload.setOneTime(false);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.downloadToken("TOKEN001"))).thenReturn(RedisJsonUtils.toJson(payload));
        when(fileMetadataClient.getMetadata("FILE001")).thenReturn(file("FILE001", "0001", FileStatusEnum.GENERATED.getCode()));

        FileMetadataDTO file = downloadTokenService.validateToken("FILE001", "TOKEN001", null, "127.0.0.1");

        assertEquals("FILE001", file.getFileNo());
        // rate limit keyed on the member resolved from the token
        verify(downloadLimitService).check("0001", "127.0.0.1");
    }

    @Test
    void validateTokenShouldRejectWhenSuppliedMemberDiffersFromToken() {
        DownloadTokenPayload payload = new DownloadTokenPayload();
        payload.setToken("TOKEN001");
        payload.setFileNo("FILE001");
        payload.setMemberId("0001");
        payload.setClientIp("127.0.0.1");
        payload.setExpireAt(LocalDateTime.now().plusMinutes(5));
        payload.setOneTime(false);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.downloadToken("TOKEN001"))).thenReturn(RedisJsonUtils.toJson(payload));

        assertThrows(BizException.class,
                () -> downloadTokenService.validateToken("FILE001", "TOKEN001", "0002", "127.0.0.1"));
    }

    private FileMetadataDTO file(String fileNo, String memberId, String status) {
        FileMetadataDTO file = new FileMetadataDTO();
        file.setFileNo(fileNo);
        file.setTaskNo("TASK001");
        file.setSettleDate(LocalDate.of(2026, 6, 26));
        file.setMemberId(memberId);
        file.setFileType("TRADE");
        file.setFileName("trade_0001_20260626.csv");
        file.setFileSize(10L);
        file.setFileMd5("md5");
        file.setStorageBucket("exchange-clear");
        file.setStoragePath("20260626/0001/v1/TRADE/trade_0001_20260626.csv");
        file.setVersion(1);
        file.setStatus(status);
        return file;
    }
}
