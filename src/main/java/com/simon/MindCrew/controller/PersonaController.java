package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SystemPersona;
import com.simon.MindCrew.service.PersonaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Soul 人格管理 REST API
 *
 * 路由：/api/v2/persona
 *   GET    /list             列出所有启用的人格
 *   GET    /default          获取当前默认人格
 *   GET    /{id}             查询单个
 *   POST   /                 创建（admin only）
 *   PUT    /{id}             更新（admin only）
 *   POST   /{id}/set-default 设为默认（admin only）
 *   DELETE /{id}             删除（admin only）
 *   POST   /preview          预览 prompt 拼装效果（含反讨好块）
 */
@RestController
@RequestMapping("/api/v2/persona")
@RequiredArgsConstructor
public class PersonaController {

    private final PersonaService personaService;

    // ───────── 读取 ─────────
    @GetMapping("/list")
    public Result<List<SystemPersona>> list() {
        return Result.success(personaService.listAll());
    }

    @GetMapping("/default")
    public Result<SystemPersona> getDefault() {
        return Result.success(personaService.getDefault());
    }

    @GetMapping("/{id}")
    public Result<SystemPersona> getById(@PathVariable Long id) {
        SystemPersona p = personaService.getById(id);
        return p == null ? Result.error("人格不存在") : Result.success(p);
    }

    // ───────── 写入（管理员）─────────
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody PersonaDTO dto) {
        SystemPersona p = dto.toEntity();
        Long id = personaService.create(p);
        return Result.success(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody PersonaDTO dto) {
        SystemPersona p = dto.toEntity();
        p.setId(id);
        personaService.update(p);
        return Result.success();
    }

    @PostMapping("/{id}/set-default")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setDefault(@PathVariable Long id) {
        personaService.setDefault(id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        personaService.delete(id);
        return Result.success();
    }

    // ───────── 预览（管理员调试用）─────────
    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> preview(@RequestBody PersonaDTO dto) {
        SystemPersona p = dto.toEntity();
        String fullPrompt = personaService.buildSystemPrompt(p);
        return Result.success(Map.of(
                "fullPrompt", fullPrompt,
                "promptLength", fullPrompt.length(),
                "antiSycophancyEnabled", Integer.valueOf(1).equals(p.getAntiSycophancy())
        ));
    }

    // ───────── DTO ─────────
    @Data
    public static class PersonaDTO {
        private String name;
        private String description;
        private String systemPrompt;
        private BigDecimal temperature;
        private String modelName;
        private Integer antiSycophancy;
        private Integer enabled;
        private Integer sortOrder;

        public SystemPersona toEntity() {
            SystemPersona p = new SystemPersona();
            p.setName(name);
            p.setDescription(description);
            p.setSystemPrompt(systemPrompt);
            p.setTemperature(temperature == null ? BigDecimal.valueOf(0.7) : temperature);
            p.setModelName(modelName);
            p.setAntiSycophancy(antiSycophancy == null ? 1 : antiSycophancy);
            p.setEnabled(enabled == null ? 1 : enabled);
            p.setSortOrder(sortOrder == null ? 100 : sortOrder);
            return p;
        }
    }
}
