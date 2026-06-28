package com.wuxx.exchangeclear.storage;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StorageUploadResult {

    private String bucket;

    private String objectName;

    private Long fileSize;
}
