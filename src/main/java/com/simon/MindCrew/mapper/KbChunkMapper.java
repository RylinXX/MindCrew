package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档切片 Mapper
 * 对应数据表: kb_chunk
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
