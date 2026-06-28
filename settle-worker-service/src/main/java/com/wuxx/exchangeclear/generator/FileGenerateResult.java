package com.wuxx.exchangeclear.generator;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.nio.file.Path;

@Data
@AllArgsConstructor
public class FileGenerateResult {

    private String fileName;

    private Path localFilePath;

    private Long fileSize;

    private String fileMd5;
}
