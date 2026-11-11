package com.simon.MindCrew.service.knowledge;

import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 文件存储服务 · 本地开发 / 私有化部署
 *
 * 仅当 application.yml 中 `storage.type=minio`（或未配置，默认）时启用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioService implements FileStorageService {

    private final MinioClient minioClient;

    @Override
    public String type() { return "minio"; }

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    /**
     * 初始化 Bucket，并设置公共读策略（允许直接访问 avatar/ 目录）
     */
    @Override
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("已创建 MinIO Bucket: {}", bucketName);
            }
            // 设置 bucket 公共读策略，使 avatar 图片可以直接访问
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": {"AWS": ["*"]},
                        "Action": ["s3:GetObject"],
                        "Resource": ["arn:aws:s3:::%s/*"]
                      }]
                    }""".formatted(bucketName);
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder().bucket(bucketName).config(policy).build());
        } catch (Exception e) {
            log.warn("MinIO Bucket 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 获取文件的直接公共访问 URL（用于 avatar 等公共资源）
     */
    @Override
    public String getPublicUrl(String objectName) {
        return endpoint + "/" + bucketName + "/" + objectName;
    }

    /**
     * 从本地磁盘文件上传到 MinIO。
     * 用于音频/视频等需要"先有公网 URL 才能调外部 API"的场景。
     */
    @Override
    public String uploadLocalFile(java.nio.file.Path localPath, String directory, String contentType) {
        try {
            String fileName = localPath.getFileName().toString();
            String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
            String objectName = directory + "/" + UUID.randomUUID() + extension;

            try (InputStream in = java.nio.file.Files.newInputStream(localPath)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .stream(in, java.nio.file.Files.size(localPath), -1)
                        .contentType(contentType != null ? contentType : "application/octet-stream")
                        .build());
            }
            log.info("本地文件上传 MinIO: {} -> {}", localPath, objectName);
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("MinIO 上传本地文件失败: " + e.getMessage(), e);
        }
    }

    /** 删除对象 */
    @Override
    public void deleteObject(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName).object(objectName).build());
        } catch (Exception e) {
            log.warn("MinIO 删除对象失败 {}: {}", objectName, e.getMessage());
        }
    }

    /**
     * 上传文件
     * @param file 上传的文件
     * @param directory 目录（如 "knowledge"、"avatar"）
     * @return 文件路径（不含 endpoint）
     */
    @Override
    public String uploadFile(MultipartFile file, String directory) {
        try {
            String originalName = file.getOriginalFilename();
            String extension = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf('.'))
                    : "";
            String objectName = directory + "/" + UUID.randomUUID() + extension;

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());

            log.info("文件上传成功: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件访问 URL（有效期 7 天）
     */
    @Override
    public String getFileUrl(String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectName)
                    .expiry(7, TimeUnit.DAYS)
                    .build());
        } catch (Exception e) {
            log.error("获取文件URL失败", e);
            return endpoint + "/" + bucketName + "/" + objectName;
        }
    }

    /**
     * 获取文件输入流
     */
    @Override
    public InputStream getFileStream(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            log.error("获取文件流失败: {}", objectName, e);
            throw new RuntimeException("文件读取失败");
        }
    }

    /**
     * 删除文件
     */
    @Override
    public void deleteFile(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            log.info("文件删除成功: {}", objectName);
        } catch (Exception e) {
            log.warn("文件删除失败: {}", e.getMessage());
        }
    }
}
