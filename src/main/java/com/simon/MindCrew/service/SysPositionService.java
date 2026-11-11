package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.SysPosition;
import com.simon.MindCrew.mapper.SysPositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 职位服务 · 任务 7
 * 职位是业务角色（HR 经理、Java 工程师 ...），区别于 sys_user.role 系统角色（admin/user）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPositionService {

    private final SysPositionMapper mapper;

    public List<SysPosition> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<SysPosition>()
                .eq(SysPosition::getEnabled, 1)
                .orderByAsc(SysPosition::getSortOrder));
    }

    public List<SysPosition> listByDepartment(Long departmentId) {
        return mapper.selectList(new LambdaQueryWrapper<SysPosition>()
                .eq(departmentId != null, SysPosition::getDepartmentId, departmentId)
                .eq(SysPosition::getEnabled, 1)
                .orderByAsc(SysPosition::getSortOrder));
    }

    public SysPosition getById(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    public SysPosition getByCode(String code) {
        if (code == null) return null;
        return mapper.selectOne(new LambdaQueryWrapper<SysPosition>()
                .eq(SysPosition::getCode, code)
                .last("LIMIT 1"));
    }

    public Long create(SysPosition p) {
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getLevel() == null) p.setLevel(1);
        if (p.getSortOrder() == null) p.setSortOrder(100);
        if (p.getCode() == null || p.getCode().isBlank()) {
            throw new IllegalArgumentException("code 必填");
        }
        // 唯一性检查
        if (getByCode(p.getCode()) != null) {
            throw new IllegalArgumentException("code 已存在: " + p.getCode());
        }
        mapper.insert(p);
        return p.getId();
    }

    public void update(SysPosition p) {
        mapper.updateById(p);
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }
}
