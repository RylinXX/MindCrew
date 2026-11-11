package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.CsSsoUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客服中心 · SSO 外部账号映射 Mapper
 */
@Mapper
public interface CsSsoUserMapper extends BaseMapper<CsSsoUser> {
}
