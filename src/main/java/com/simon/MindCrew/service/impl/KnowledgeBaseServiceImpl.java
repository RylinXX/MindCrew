package com.simon.MindCrew.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeBaseService;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.MilvusService;
import com.simon.MindCrew.service.knowledge.TextChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper kbChunkMapper;
    private final MilvusService milvusService;
    private final DocumentExtractor documentExtractor;
    private final TextChunker textChunker;
    private final DocumentProcessTask documentProcessTask;
    // 任务 7 · 职位独立 KB ACL 服务（可为 null 兼容旧测试构造器）
    private final com.simon.MindCrew.service.KbAclService kbAclService;
    private final com.simon.MindCrew.service.UserService userService;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** 音视频入库前置能力（字段注入，避免改动构造器签名影响既有测试） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.simon.MindCrew.service.knowledge.VideoProcessor videoProcessor;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.simon.MindCrew.service.knowledge.AudioTranscriber audioTranscriber;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.simon.MindCrew.service.knowledge.VisionRecognizer visionRecognizer;

    /** 测试用辅助构造器 · ACL/User 服务传 null，测试场景跳过权限校验 */
    KnowledgeBaseServiceImpl(MedKnowledgeBaseMapper knowledgeBaseMapper,
                             MilvusService milvusService,
                             DocumentExtractor documentExtractor,
                             TextChunker textChunker,
                             DocumentProcessTask documentProcessTask) {
        this(knowledgeBaseMapper, null, milvusService, documentExtractor, textChunker, documentProcessTask, null, null);
    }

    Long uploadDocument(MultipartFile file, String category, String description) {
        return uploadDocument(file, file != null ? file.getOriginalFilename() : null, category, description, 0L);
    }

    @Override
    @Transactional
    @com.simon.MindCrew.common.audit.Audited(action = "kb.upload", label = "上传知识库文档", targetType = "kb", targetIdParam = "$return")
    public Long uploadDocument(MultipartFile file, String name, String category, String description, Long userId) {
        // 1. 校验文件类型
        String originalName = file.getOriginalFilename();
        String fileType = getFileExtension(originalName);
        String ft = fileType.toLowerCase();
        boolean isAudio = com.simon.MindCrew.service.knowledge.AudioTranscriber.supportedExtensions().contains(ft);
        boolean isVideo = com.simon.MindCrew.service.knowledge.VideoProcessor.supportedExtensions().contains(ft);
        // 音视频能力检查：当前环境缺 ffmpeg / 公网对象存储 / DashScope ASR/VL Key 时快速失败，
        // 避免文件先落库再异步处理失败（状态从 uploading 变 failed，体验差且难排查）
        if (isAudio || isVideo) {
            String blocked = mediaBlockedReason(isVideo);
            if (blocked != null) {
                throw new BusinessException(com.simon.MindCrew.common.result.ResultCode.PARAM_ERROR.getCode(),
                        "当前环境暂不支持" + (isVideo ? "视频" : "音频") + "知识库：" + blocked);
            }
        }

        if (!DocumentExtractor.supportedExtensions().contains(ft) && !isAudio && !isVideo) {
            throw new BusinessException("不支持的文件格式，当前支持: 文档(" +
                    String.join(", ", DocumentExtractor.supportedExtensions()) + ")、音频(" +
                    String.join(", ", com.simon.MindCrew.service.knowledge.AudioTranscriber.supportedExtensions()) +
                    ")、视频(" + String.join(", ", com.simon.MindCrew.service.knowledge.VideoProcessor.supportedExtensions()) + ")");
        }
        if (userId == null) {
            throw new BusinessException("上传用户不能为空");
        }

        String finalName = StringUtils.isNotBlank(name) ? name : originalName;

        // 2. 保存文件到本地磁盘
        String fileUrl = saveToLocal(file, originalName);

        // 3. 创建知识库记录
        MedKnowledgeBase kb = new MedKnowledgeBase();
        kb.setName(finalName);
        kb.setDescription(description);
        kb.setCategory(category);
        kb.setFileUrl(fileUrl);
        kb.setFileType(fileType);
        kb.setFileSize(file.getSize());
        kb.setStatus("uploading");
        kb.setChunkCount(0);
        kb.setUserId(userId);
        knowledgeBaseMapper.insert(kb);

        // 4. 事务提交后再触发异步任务，避免异步线程读到未提交数据导致状态卡在 uploading
        final Long kbId = kb.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                documentProcessTask.process(kbId);
            }
        });

        log.info("文档上传成功: id={}, name={}, userId={}", kb.getId(), finalName, userId);
        return kb.getId();
    }

    @Override
    public Page<MedKnowledgeBase> listKnowledge(Integer current, Integer size,
                                                   String category, String status) {
        Page<MedKnowledgeBase> page = new Page<>(current, size);

        // 任务 7 · 接口层 ACL 过滤
        List<Long> accessibleIds = null;
        if (userService != null && kbAclService != null) {
            Long uid;
            try { uid = userService.getCurrentUserId(); } catch (Exception e) { uid = null; }
            accessibleIds = (uid == null) ? List.of() : kbAclService.listAccessibleKbIds(uid);
        }

        LambdaQueryWrapper<MedKnowledgeBase> wrapper = new LambdaQueryWrapper<MedKnowledgeBase>()
                .eq(MedKnowledgeBase::getDeleted, 0)
                .eq(StringUtils.isNotBlank(category), MedKnowledgeBase::getCategory, category)
                .eq(StringUtils.isNotBlank(status), MedKnowledgeBase::getStatus, status)
                // 仅当 accessibleIds 不为 null 时启用过滤；空列表用 -1L 占位（保证查不到）
                .in(accessibleIds != null,
                        MedKnowledgeBase::getId,
                        accessibleIds != null && accessibleIds.isEmpty() ? List.of(-1L) : accessibleIds)
                .orderByDesc(MedKnowledgeBase::getCreateTime);
        return knowledgeBaseMapper.selectPage(page, wrapper);
    }

    @Override
    public MedKnowledgeBase getById(Long id) {
        MedKnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || kb.getDeleted() == 1) {
            throw new BusinessException(ResultCode.KNOWLEDGE_NOT_FOUND);
        }
        // 任务 7 · 权限校验 read
        if (userService != null && kbAclService != null) {
            Long me;
            try { me = userService.getCurrentUserId(); } catch (Exception e) { me = null; }
            if (me != null && !kbAclService.canAccess(me, id, com.simon.MindCrew.service.KbAclService.PERM_READ)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该知识库");
            }
        }
        return kb;
    }

    @Override
    @Transactional
    @com.simon.MindCrew.common.audit.Audited(action = "kb.delete", label = "删除知识库", targetType = "kb", targetIdParam = "$arg0")
    public void deleteById(Long id) {
        MedKnowledgeBase kb = getById(id);
        // 删除需要 admin 权限
        if (userService != null && kbAclService != null) {
            Long me;
            try { me = userService.getCurrentUserId(); } catch (Exception e) { me = null; }
            if (me != null && !kbAclService.canAccess(me, id, com.simon.MindCrew.service.KbAclService.PERM_ADMIN)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权删除该知识库（需要 admin 权限）");
            }
        }
        // 删除 Milvus 向量（失败不影响主流程，向量丢失可接受）
        milvusService.deleteByKnowledgeBaseId(id);
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getKbId, id));
        // 删除本地文件
        deleteLocalFile(kb.getFileUrl());
        // 逻辑删除：必须用 deleteById 而非 updateById
        // @TableLogic 字段会被 updateById 的 SET 子句忽略，直接 setDeleted(1)+updateById 无效
        knowledgeBaseMapper.deleteById(id);
        log.info("知识库删除完成: id={}", id);
    }

    @Override
    public List<String> listCategories() {
        return knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<MedKnowledgeBase>()
                        .eq(MedKnowledgeBase::getDeleted, 0)
                        .select(MedKnowledgeBase::getCategory)
                        .groupBy(MedKnowledgeBase::getCategory)
        ).stream().map(MedKnowledgeBase::getCategory).distinct().collect(Collectors.toList());
    }

    @Override
    public void reprocess(Long id) {
        MedKnowledgeBase kb = getById(id);
        if (!"failed".equals(kb.getStatus())) {
            throw new BusinessException("只有处理失败的文档才能重新处理");
        }
        // 先删除旧向量
        milvusService.deleteByKnowledgeBaseId(id);
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getKbId, id));
        updateStatus(kb, "uploading", null);
        documentProcessTask.process(id);
    }

    @Override
    public void updateVisibility(Long id, String visibility) {
        if (visibility == null || !java.util.Set.of("public", "scoped", "private").contains(visibility)) {
            throw new BusinessException("visibility 必须是 public / scoped / private 之一");
        }
        MedKnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || kb.getDeleted() == 1) {
            throw new BusinessException(ResultCode.KNOWLEDGE_NOT_FOUND);
        }
        // 用 LambdaUpdateWrapper 显式 set，避免 mybatis-plus null 字段被忽略导致旧值留存
        knowledgeBaseMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                        .set(MedKnowledgeBase::getVisibility, visibility)
                        .eq(MedKnowledgeBase::getId, id));
        log.info("[KB] 可见性切换 id={} → {}", id, visibility);
    }

    // ==================== 私有方法 ====================

    private void updateStatus(MedKnowledgeBase kb, String status, String errorMsg) {
        kb.setStatus(status);
        kb.setErrorMsg(errorMsg);
        knowledgeBaseMapper.updateById(kb);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "txt";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 保存文件到本地磁盘，返回相对路径（作为 fileUrl 存入数据库）
     */
    /** 音视频入库前置条件检查 · 返回 null 表示可用，否则返回可读原因 */
    private String mediaBlockedReason(boolean video) {
        if (videoProcessor == null) return "视频处理组件未加载";
        if (video && !videoProcessor.isFfmpegReady()) return "缺少 ffmpeg/ffprobe（服务端需安装后重启）";
        if (!videoProcessor.isStorageReachableForAsr()) {
            return "对象存储地址对 DashScope ASR 不可达（MinIO localhost 不可用，需可公网访问的 OSS/MinIO 地址）";
        }
        if (audioTranscriber == null || !audioTranscriber.isConfigured()) {
            return "缺少 DashScope ASR Key（paraformer-v2）";
        }
        if (video && (visionRecognizer == null || !visionRecognizer.isConfigured())) {
            return "缺少 DashScope 视觉 Key（qwen-vl-max，用于关键帧识别）";
        }
        return null;
    }

    private String saveToLocal(MultipartFile file, String originalName) {
        try {
            String ext = getFileExtension(originalName);
            String filename = UUID.randomUUID() + "." + ext;
            Path dir = Paths.get(uploadPath, "knowledge");
            Files.createDirectories(dir);
            Path dest = dir.resolve(filename);
            file.transferTo(dest.toAbsolutePath().toFile());
            // 返回相对路径，供 DocumentProcessTask 读取
            String relativePath = "knowledge/" + filename;
            log.info("文档已保存到本地: {}", dest.toAbsolutePath());
            return relativePath;
        } catch (IOException e) {
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 删除本地文件
     */
    private void deleteLocalFile(String fileUrl) {
        try {
            Path path = Paths.get(uploadPath, fileUrl);
            Files.deleteIfExists(path);
            log.info("本地文件已删除: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("本地文件删除失败: {}", e.getMessage());
        }
    }
}
