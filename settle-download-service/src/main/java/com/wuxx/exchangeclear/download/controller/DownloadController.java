package com.wuxx.exchangeclear.download.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.download.dto.CreateDownloadTokenRequest;
import com.wuxx.exchangeclear.download.dto.DownloadTokenResponse;
import com.wuxx.exchangeclear.download.service.FileDownloadService;
import com.wuxx.exchangeclear.download.token.DownloadTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final FileDownloadService fileDownloadService;

    private final DownloadTokenService downloadTokenService;

    @PostMapping("/token")
    public Result<DownloadTokenResponse> createToken(@RequestHeader("X-Member-Id") String memberId,
                                                     @RequestHeader(value = "X-Client-IP", required = false) String clientIp,
                                                     @Valid @RequestBody CreateDownloadTokenRequest request,
                                                     HttpServletRequest servletRequest) {
        return Result.success(downloadTokenService.createToken(
                request.getFileNo(), memberId, resolveClientIp(clientIp, servletRequest)));
    }

    @GetMapping("/files/{fileNo}")
    public void download(@PathVariable String fileNo,
                         @RequestParam String token,
                         @RequestHeader(value = "X-Member-Id", required = false) String memberId,
                         @RequestHeader(value = "X-Client-IP", required = false) String clientIp,
                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                         @RequestHeader(value = "User-Agent", required = false) String userAgent,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        fileDownloadService.download(
                fileNo, token, memberId, resolveClientIp(clientIp, request), requestId, userAgent, response);
    }

    private String resolveClientIp(String clientIp, HttpServletRequest request) {
        if (clientIp != null && !clientIp.trim().isEmpty()) {
            return clientIp;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
