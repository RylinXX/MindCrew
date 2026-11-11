package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.QaConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话 Mapper
 * 对应数据表: qa_conversation
 */
@Mapper
public interface QaConversationMapper extends BaseMapper<QaConversation> {
}
