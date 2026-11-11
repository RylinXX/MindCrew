package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SysPosition;
import com.simon.MindCrew.service.SysPositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/position")
@RequiredArgsConstructor
public class SysPositionController {

    private final SysPositionService service;

    @GetMapping("/list")
    public Result<List<SysPosition>> list(@RequestParam(required = false) Long departmentId) {
        return Result.success(
                departmentId == null ? service.listAll() : service.listByDepartment(departmentId)
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody SysPosition p) {
        return Result.success(service.create(p));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysPosition p) {
        p.setId(id);
        service.update(p);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }
}
