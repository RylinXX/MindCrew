package com.simon.MindCrew.service.knowledge;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.*;
import io.milvus.param.dml.DeleteParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Milvus 向量数据库服务
 * 负责 Collection 初始化、向量插入、向量检索、向量删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient milvusClient;

    @Value("${milvus.collection-name}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private Integer dimension;

    /** 是否启用 Milvus。关闭后向量能力降级为空，关键词检索不受影响（本地无向量库时使用） */
    @Value("${milvus.enabled:true}")
    private boolean enabled;

    /**
     * 初始化 Collection（建表）
     */
    public void initCollection() {
        if (!enabled) {
            log.debug("[Milvus] enabled=false，跳过 {}", Thread.currentThread().getStackTrace()[1].getMethodName());
            return;
        }
        try {
            R<Boolean> hasCollection = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(collectionName).build());

            if (Boolean.TRUE.equals(hasCollection.getData())) {
                log.info("Milvus Collection 已存在: {}", collectionName);
                return;
            }

            // 定义字段
            FieldType idField = FieldType.newBuilder()
                    .withName("id")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(64)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();

            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(dimension)
                    .build();

            FieldType kbIdField = FieldType.newBuilder()
                    .withName("knowledge_base_id")
                    .withDataType(DataType.Int64)
                    .build();

            FieldType categoryField = FieldType.newBuilder()
                    .withName("category")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(50)
                    .build();

            FieldType contentTypeField = FieldType.newBuilder()
                    .withName("content_type")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(30)
                    .build();

            FieldType contentField = FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(4096)
                    .build();

            FieldType chapterField = FieldType.newBuilder()
                    .withName("chapter")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(200)
                    .build();

            FieldType pageField = FieldType.newBuilder()
                    .withName("page_number")
                    .withDataType(DataType.Int64)
                    .build();

            // 创建 Collection
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withDescription("MindCrew 通用知识切片向量库")
                    .withShardsNum(2)
                    .addFieldType(idField)
                    .addFieldType(embeddingField)
                    .addFieldType(kbIdField)
                    .addFieldType(categoryField)
                    .addFieldType(contentTypeField)
                    .addFieldType(contentField)
                    .addFieldType(chapterField)
                    .addFieldType(pageField)
                    .build();

            R<RpcStatus> createResult = milvusClient.createCollection(createParam);
            if (createResult.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("创建Collection失败: " + createResult.getMessage());
            }

            // 创建向量索引 (HNSW)
            IndexType indexType = IndexType.HNSW;
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .withIndexType(indexType)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"M\": 16, \"efConstruction\": 64}")
                    .build();

            milvusClient.createIndex(indexParam);

            // 加载到内存
            milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());

            log.info("Milvus Collection 创建成功: {}", collectionName);
        } catch (Exception e) {
            log.warn("Milvus 初始化失败（请确保 Milvus 已启动）: {}", e.getMessage());
        }
    }

    /**
     * 批量插入向量
     * @param chunks 文本切片列表
     * @param embeddings 对应的向量列表
     */
    public void insertVectors(List<TextChunker.TextChunk> chunks, List<List<Float>> embeddings) {
        if (!enabled) {
            log.debug("[Milvus] enabled=false，跳过 {}", Thread.currentThread().getStackTrace()[1].getMethodName());
            return;
        }
        if (chunks.isEmpty()) return;

        List<String> ids = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        List<Long> kbIds = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<String> contentTypes = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<String> chapters = new ArrayList<>();
        List<Long> pageNumbers = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.TextChunk chunk = chunks.get(i);
            ids.add(UUID.randomUUID().toString().replace("-", ""));
            vectors.add(embeddings.get(i));
            kbIds.add(chunk.getKnowledgeBaseId());
            categories.add(nullToEmpty(chunk.getCategory()));
            contentTypes.add(nullToEmpty(chunk.getContentType()));
            // 内容截断防超长
            String content = chunk.getContent();
            contents.add(content.length() > 4000 ? content.substring(0, 4000) : content);
            chapters.add(nullToEmpty(chunk.getChapter()));
            pageNumbers.add((long) chunk.getPageNumber());
        }

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(Arrays.asList(
                        new InsertParam.Field("id", ids),
                        new InsertParam.Field("embedding", vectors),
                        new InsertParam.Field("knowledge_base_id", kbIds),
                        new InsertParam.Field("category", categories),
                        new InsertParam.Field("content_type", contentTypes),
                        new InsertParam.Field("content", contents),
                        new InsertParam.Field("chapter", chapters),
                        new InsertParam.Field("page_number", pageNumbers)
                ))
                .build();

        R<MutationResult> result = milvusClient.insert(insertParam);
        if (result.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("向量插入失败: " + result.getMessage());
        }
        log.info("向量插入成功: {}条", chunks.size());
    }

    /**
     * 按知识库ID删除所有向量
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        if (!enabled) {
            log.debug("[Milvus] enabled=false，跳过 {}", Thread.currentThread().getStackTrace()[1].getMethodName());
            return;
        }
        try {
            String expr = "knowledge_base_id == " + knowledgeBaseId;
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build();
            milvusClient.delete(deleteParam);
            log.info("已删除知识库 {} 的所有向量", knowledgeBaseId);
        } catch (Exception e) {
            log.warn("向量删除失败: {}", e.getMessage());
        }
    }

    /**
     * 向量语义检索（Top-K）
     */
    public List<SearchResult> search(List<Float> queryVector, String categoryFilter,
                                      int topK) {
        // 此方法将在 Phase 3 RAG链路中完整实现
        // 这里提供基础框架
        return new ArrayList<>();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 检索结果记录
     */
    public record SearchResult(String id, float score, String content, String category,
                                String contentType, String chapter, int pageNumber, long knowledgeBaseId) {}
}
