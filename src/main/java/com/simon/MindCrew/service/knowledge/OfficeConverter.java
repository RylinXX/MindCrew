package com.simon.MindCrew.service.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 LibreOffice headless 的 Office 文档格式转换工具。
 *
 * 用途：把 .wps / .doc / .ppt / .xls 等老格式 / 私有格式转成现代 OOXML（.docx/.pptx/.xlsx），
 * 再让 POI 走标准解析路径。
 *
 * 部署前置：
 *   macOS:   brew install libreoffice
 *   Linux:   apt-get install -y libreoffice
 *   验证:   soffice --version
 *
 * 配置 application.yml:
 *   office:
 *     soffice-path: /usr/bin/soffice    # 或 macOS 上 /Applications/LibreOffice.app/Contents/MacOS/soffice
 *     convert-timeout: 120              # 单次转换最大秒数
 */
@Slf4j
@Component
public class OfficeConverter {

    @Value("${office.soffice-path:soffice}")
    private String sofficePath;

    @Value("${office.convert-timeout:120}")
    private int convertTimeoutSeconds;

    /**
     * 把输入流转换为指定目标格式，返回结果文件的 InputStream。
     *
     * @param input  源文件流
     * @param sourceExt  源文件扩展名（不带点），如 "wps" / "doc"
     * @param targetExt  目标扩展名，如 "docx" / "pptx" / "xlsx"
     * @return 转换后的临时文件输入流（调用方负责关闭）
     */
    public InputStream convertTo(InputStream input, String sourceExt, String targetExt) throws IOException {
        Path workDir = Files.createTempDirectory("officeconv-");
        Path srcFile = workDir.resolve("source-" + UUID.randomUUID() + "." + sourceExt);

        try {
            // 把上传流写到磁盘临时文件（soffice 只支持文件输入）
            Files.copy(input, srcFile, StandardCopyOption.REPLACE_EXISTING);

            ProcessBuilder pb = new ProcessBuilder(
                    sofficePath,
                    "--headless",
                    "--convert-to", targetExt,
                    "--outdir", workDir.toString(),
                    srcFile.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(convertTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("LibreOffice 转换超时（>" + convertTimeoutSeconds + "s）: " + sourceExt + " -> " + targetExt);
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = new String(process.getInputStream().readAllBytes());
                throw new IOException("LibreOffice 转换失败 exitCode=" + exitCode + ": " + stderr);
            }

            // soffice 把同名文件（扩展名替换）输出到 outdir
            String baseName = srcFile.getFileName().toString();
            String targetName = baseName.substring(0, baseName.lastIndexOf('.')) + "." + targetExt;
            Path resultFile = workDir.resolve(targetName);

            if (!Files.exists(resultFile)) {
                throw new IOException("LibreOffice 转换后未找到输出文件: " + resultFile);
            }

            log.info("[OfficeConverter] {} -> {}, size={}KB", sourceExt, targetExt, Files.size(resultFile) / 1024);

            // 读到内存后立即清理临时目录
            byte[] data = Files.readAllBytes(resultFile);
            cleanupSilently(workDir);
            return new java.io.ByteArrayInputStream(data);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("LibreOffice 转换被中断", ie);
        } catch (Exception e) {
            cleanupSilently(workDir);
            throw e;
        }
    }

    /** 检查 LibreOffice 是否可用 */
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(sofficePath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("[OfficeConverter] soffice 不可用: {}", e.getMessage());
            return false;
        }
    }

    private void cleanupSilently(Path dir) {
        try {
            if (dir != null && Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                }
            }
        } catch (Exception ignored) {
        }
    }
}
