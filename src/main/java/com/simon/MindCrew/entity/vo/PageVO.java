package com.simon.MindCrew.entity.vo;

import lombok.Data;

import java.util.List;

/**
 * 分页结果 VO
 */
@Data
public class PageVO<T> {

    private Long total;

    private Long pages;

    private Long current;

    private Long size;

    private List<T> records;

    public static <T> PageVO<T> of(com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page) {
        PageVO<T> vo = new PageVO<>();
        vo.setTotal(page.getTotal());
        vo.setPages(page.getPages());
        vo.setCurrent(page.getCurrent());
        vo.setSize(page.getSize());
        vo.setRecords(page.getRecords());
        return vo;
    }
}
