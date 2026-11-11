package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.CsMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客服中心 · 客服消息 Mapper
 */
@Mapper
public interface CsMessageMapper extends BaseMapper<CsMessage> {
}
