package com.wuxx.exchangeclear.download.controller;

import com.wuxx.exchangeclear.download.service.FileDownloadService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;

@Validated
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final FileDownloadService fileDownloadService;

    @GetMapping("/files/{fileNo}")
    public void download(@PathVariable String fileNo, HttpServletResponse response) {
        fileDownloadService.download(fileNo, response);
    }
}
