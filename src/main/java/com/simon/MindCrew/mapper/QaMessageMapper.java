package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.QaMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息 Mapper
 * 对应数据表: qa_message
 */
@Mapper
public interface QaMessageMapper extends BaseMapper<QaMessage> {
}
