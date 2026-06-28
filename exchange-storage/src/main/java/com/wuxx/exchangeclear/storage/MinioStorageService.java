package com.wuxx.exchangeclear.storage;

import com.wuxx.exchangeclear.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService implements ObjectStorageService {

    private final MinioClient minioClient;

    private final MinioProperties minioProperties;

    private volatile boolean bucketChecked;

    @Override
    public StorageUploadResult upload(Path localFilePath, String objectName) {
        try {
            ensureBucket();
            long fileSize = Files.size(localFilePath);
            try (InputStream inputStream = Files.newInputStream(localFilePath)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(objectName)
                        .stream(inputStream, fileSize, -1)
                        .contentType("text/csv; charset=utf-8")
                        .build());
            }
            return new StorageUploadResult(minioProperties.getBucket(), objectName, fileSize);
        } catch (Exception e) {
            throw new IllegalStateException("上传文件到MinIO失败", e);
        }
    }

    @Override
    public InputStream download(String objectName) {
        try {
            ensureBucket();
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("从MinIO下载文件失败", e);
        }
    }

    @Override
    public boolean exists(String objectName) {
        try {
            ensureBucket();
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectName)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("检查MinIO文件是否存在失败", e);
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketChecked) {
            return;
        }
        synchronized (this) {
            if (bucketChecked) {
                return;
            }
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .build());
                log.info("MinIO bucket created, bucket={}", minioProperties.getBucket());
            }
            bucketChecked = true;
        }
    }
}
