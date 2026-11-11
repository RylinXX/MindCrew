package com.simon.MindCrew.service.knowledge;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 对象存储统一接口。
 *
 * 两个实现：
 *   - MinioStorageService  · 本地开发 / 私有化部署
 *   - OssStorageService    · 阿里云生产
 *
 * 切换方式：`application.yml` 设 `storage.type: minio | oss`
 *
 * 业务代码不要直接依赖 MinioService / OssService，统一依赖本接口。
 */
public interface FileStorageService {

    /** 初始化 Bucket（首次启动时调用，幂等） */
    void initBucket();

    /** 上传 MultipartFile（用户上传文档场景） · 返回对象名（不带 endpoint） */
    String uploadFile(MultipartFile file, String directory);

    /** 上传本地磁盘文件 · 返回对象名 */
    String uploadLocalFile(Path localPath, String directory, String contentType);

    /** 获取带签名的访问 URL（默认 7 天） · 用于私有读对象 */
    String getFileUrl(String objectName);

    /** 获取直接公共访问 URL · 用于 public-read 的对象（如 avatar） */
    String getPublicUrl(String objectName);

    /** 获取对象输入流 · 用于服务端读取后再处理 */
    InputStream getFileStream(String objectName);

    /** 删除对象 · 失败仅记日志不抛异常 */
    void deleteObject(String objectName);

    /** 删除对象 · 同 deleteObject，兼容历史调用 */
    default void deleteFile(String objectName) {
        deleteObject(objectName);
    }

    /** 实现类型名（用于日志和监控） */
    String type();
}
