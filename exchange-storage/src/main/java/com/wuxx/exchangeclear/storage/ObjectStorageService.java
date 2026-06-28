package com.wuxx.exchangeclear.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface ObjectStorageService {

    StorageUploadResult upload(Path localFilePath, String objectName);

    InputStream download(String objectName);

    boolean exists(String objectName);
}
