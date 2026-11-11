package com.simon.MindCrew.service.knowledge;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.CreateBucketRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.UUID;

/**
 * 阿里云 OSS 文件存储服务 · 生产环境推荐
 *
 * 仅当 application.yml 中 `storage.type=oss` 时启用。
 *
 * 配置示例：
 *   storage:
 *     type: oss
 *   oss:
 *     endpoint: https://oss-cn-hangzhou.aliyuncs.com
 *     region: cn-hangzhou
 *     bucket: docmind-prod
 *     access-key: ${OSS_ACCESS_KEY}
 *     secret-key: ${OSS_SECRET_KEY}
 *     custom-domain:                # 可选，配 CDN 加速域名
 *     url-expire-days: 7
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "oss")
public class OssService implements FileStorageService {

    private final OSS ossClient;

    @Value("${oss.bucket}")
    private String bucket;

    @Value("${oss.endpoint}")
    private String endpoint;

    @Value("${oss.custom-domain:}")
    private String customDomain;

    @Value("${oss.url-expire-days:7}")
    private int urlExpireDays;

    @Override
    public String type() { return "oss"; }

    @Override
    public void initBucket() {
        try {
            if (!ossClient.doesBucketExist(bucket)) {
                CreateBucketRequest req = new CreateBucketRequest(bucket);
                req.setCannedACL(CannedAccessControlList.Private);
                ossClient.createBucket(req);
                log.info("[OSS] 已创建 Bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("[OSS] Bucket 初始化失败: {}", e.getMessage());
        }
    }

    @Override
    public String uploadFile(MultipartFile file, String directory) {
        try {
            String originalName = file.getOriginalFilename();
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.')) : "";
            String objectName = directory + "/" + UUID.randomUUID() + ext;

            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(file.getSize());
            if (file.getContentType() != null) meta.setContentType(file.getContentType());

            ossClient.putObject(new PutObjectRequest(bucket, objectName, file.getInputStream(), meta));
            log.info("[OSS] 上传成功: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.error("[OSS] 上传失败", e);
            throw new RuntimeException("OSS 上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadLocalFile(Path localPath, String directory, String contentType) {
        try {
            String fileName = localPath.getFileName().toString();
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
            String objectName = directory + "/" + UUID.randomUUID() + ext;

            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(Files.size(localPath));
            meta.setContentType(contentType != null ? contentType : "application/octet-stream");

            try (InputStream in = Files.newInputStream(localPath)) {
                ossClient.putObject(new PutObjectRequest(bucket, objectName, in, meta));
            }
            log.info("[OSS] 本地文件上传: {} -> {}", localPath, objectName);
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("OSS 本地文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileUrl(String objectName) {
        try {
            // 生成有效期 N 天的预签名 URL
            long expireMs = System.currentTimeMillis() + urlExpireDays * 24L * 3600L * 1000L;
            GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, objectName);
            req.setExpiration(new Date(expireMs));
            req.setMethod(HttpMethod.GET);

            URL url = ossClient.generatePresignedUrl(req);
            String urlStr = url.toString();

            // 如果配了自定义域名，把 endpoint 替换掉（CDN 加速场景）
            if (customDomain != null && !customDomain.isBlank()) {
                String hostPrefix = bucket + "." + endpoint.replaceFirst("^https?://", "");
                urlStr = urlStr.replace(hostPrefix, customDomain.replaceFirst("^https?://", ""));
            }
            return urlStr;
        } catch (OSSException | ClientException e) {
            log.error("[OSS] 生成签名 URL 失败: {}", objectName, e);
            return getPublicUrl(objectName);
        }
    }

    @Override
    public String getPublicUrl(String objectName) {
        String base = (customDomain != null && !customDomain.isBlank())
                ? customDomain.replaceAll("/+$", "")
                : (endpoint.replaceFirst("^https?://", "https://" + bucket + ".").replaceAll("/+$", ""));
        return base + "/" + objectName;
    }

    @Override
    public InputStream getFileStream(String objectName) {
        try {
            return ossClient.getObject(bucket, objectName).getObjectContent();
        } catch (Exception e) {
            log.error("[OSS] 读取流失败: {}", objectName, e);
            throw new RuntimeException("OSS 文件读取失败");
        }
    }

    @Override
    public void deleteObject(String objectName) {
        try {
            ossClient.deleteObject(bucket, objectName);
        } catch (Exception e) {
            log.warn("[OSS] 删除失败 {}: {}", objectName, e.getMessage());
        }
    }
}
