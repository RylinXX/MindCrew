package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.SysDepartment;
import com.simon.MindCrew.mapper.SysDepartmentMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 部门服务 · 任务 7
 * 支持树形结构（一级 / 二级 / 三级...）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysDepartmentService {

    private final SysDepartmentMapper mapper;

    public List<SysDepartment> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<SysDepartment>()
                .orderByAsc(SysDepartment::getSortOrder));
    }

    /** 构造树形结构 · 用于前端 el-tree */
    public List<DeptNode> tree() {
        List<SysDepartment> all = listAll();
        Map<Long, DeptNode> nodeMap = new LinkedHashMap<>();
        for (SysDepartment d : all) {
            DeptNode n = new DeptNode();
            n.setId(d.getId());
            n.setName(d.getName());
            n.setParentId(d.getParentId());
            n.setDescription(d.getDescription());
            n.setSortOrder(d.getSortOrder());
            n.setEnabled(d.getEnabled());
            n.setChildren(new ArrayList<>());
            nodeMap.put(d.getId(), n);
        }
        List<DeptNode> roots = new ArrayList<>();
        for (DeptNode n : nodeMap.values()) {
            if (n.getParentId() == null) {
                roots.add(n);
            } else {
                DeptNode parent = nodeMap.get(n.getParentId());
                if (parent != null) parent.getChildren().add(n);
                else roots.add(n);    // 孤儿节点也展示
            }
        }
        return roots;
    }

    public SysDepartment getById(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    public Long create(SysDepartment d) {
        if (d.getEnabled() == null) d.setEnabled(1);
        if (d.getSortOrder() == null) d.setSortOrder(100);
        mapper.insert(d);
        return d.getId();
    }

    public void update(SysDepartment d) {
        mapper.updateById(d);
    }

    public void delete(Long id) {
        // 拦截：有子部门不能删
        Long childCount = mapper.selectCount(new LambdaQueryWrapper<SysDepartment>()
                .eq(SysDepartment::getParentId, id));
        if (childCount > 0) {
            throw new IllegalStateException("该部门下还有 " + childCount + " 个子部门，请先删除子部门");
        }
        mapper.deleteById(id);
    }

    @Data
    public static class DeptNode {
        private Long id;
        private String name;
        private Long parentId;
        private String description;
        private Integer sortOrder;
        private Integer enabled;
        private List<DeptNode> children;
    }
}
