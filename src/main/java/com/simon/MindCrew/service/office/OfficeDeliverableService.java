package com.simon.MindCrew.service.office;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.crew.entity.AgentTask;
import com.simon.MindCrew.crew.mapper.AgentTaskMapper;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Crew 任务交付物 · 从 agent_task.final_report 导出 PPT / Word / Markdown。
 *
 * 鉴权：任务发起人本人或 admin；否则 403。报告为空 → 400。
 */
@Service
@RequiredArgsConstructor
public class OfficeDeliverableService {

    /** 生成结果：字节 + 文件名 + 内容类型 */
    public record Deliverable(byte[] bytes, String fileName, String contentType) {
    }

    private final AgentTaskMapper agentTaskMapper;
    private final UserService userService;
    private final OfficeDocumentService officeDocumentService;

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern ILLEGAL_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    public Deliverable build(Long taskId, String format) {
        AgentTask task = agentTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Task 不存在");
        }

        Long currentUserId = userService.getCurrentUserId();
        SysUser currentUser = userService.getCurrentUser();
        boolean admin = currentUser != null && currentUser.getRole() != null
                && "admin".equalsIgnoreCase(currentUser.getRole());
        if (!admin && !currentUserId.equals(task.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权导出该任务交付物");
        }

        String report = task.getFinalReport();
        if (report == null || report.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "报告尚未生成");
        }

        String fmt = format == null || format.isBlank() ? "pptx" : format.trim().toLowerCase();
        String title = reportTitle(task);
        String fileBase = "MindCrew-" + sanitize(title) + "-" + taskId + "-"
                + LocalDate.now().format(FILE_DATE);

        switch (fmt) {
            case "md" -> {
                return new Deliverable(report.getBytes(StandardCharsets.UTF_8),
                        fileBase + ".md", OfficeDocumentService.CONTENT_TYPE_MARKDOWN);
            }
            case "docx" -> {
                byte[] bytes = officeDocumentService.toDocx(title, report);
                return new Deliverable(bytes, fileBase + ".docx", OfficeDocumentService.CONTENT_TYPE_DOCX);
            }
            case "pptx" -> {
                byte[] bytes = officeDocumentService.toPptx(title, null, report);
                return new Deliverable(bytes, fileBase + ".pptx", OfficeDocumentService.CONTENT_TYPE_PPTX);
            }
            default -> throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "format 仅支持 pptx / docx / md");
        }
    }

    private String reportTitle(AgentTask task) {
        String query = task.getQuery();
        if (query == null || query.isBlank()) return "MindCrew 调研报告";
        String cleaned = query.trim().replace("\n", " ");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private String sanitize(String value) {
        String cleaned = ILLEGAL_FILENAME.matcher(value).replaceAll("");
        if (cleaned.isBlank()) return "任务报告";
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }
}
