package com.simon.MindCrew.service.office;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 合同模板注册表
 *
 * 设计要点：
 *  - 模板 = 字段元数据（给前端渲染表单）+ 纯文本正文（{{placeholder}} 占位符）
 *  - 不依赖任何二进制模板文件，DOCX 由 OfficeDocumentService 用 XWPF 程序化排版
 *  - 必填字段校验 + 金额自动转中文大写（覆盖万 / 亿）
 */
@Component
public class ContractTemplateRegistry {

    /** 模板字段定义 */
    public record FieldDef(String key, String label, boolean required) {
    }

    /** 合同模板定义 */
    public record ContractTemplate(String id, String name, String description,
                                   List<FieldDef> fields, List<String> body) {
    }

    /** 渲染结果：标题、合同编号、下载文件名、正文行（"## " 前缀表示小标题） */
    public record RenderedContract(String title, String contractNo, String fileName, List<String> lines) {
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[a-zA-Z0-9_]+}}");
    private static final Pattern ILLEGAL_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\s]");
    /** 合同金额上限 10^16 - 1（覆盖到「万亿」分组） */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000000000000");

    private static final List<ContractTemplate> TEMPLATES = buildTemplates();

    // ============================================================
    // 对外接口
    // ============================================================

    public List<ContractTemplate> templates() {
        return TEMPLATES;
    }

    /** 前端模板列表视图（不暴露正文） */
    public List<Map<String, Object>> templateViews() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (ContractTemplate template : TEMPLATES) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", template.id());
            view.put("name", template.name());
            view.put("description", template.description());
            view.put("fields", template.fields());
            views.add(view);
        }
        return views;
    }

    public ContractTemplate get(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "template 不能为空");
        }
        String id = templateId.trim().toLowerCase();
        for (ContractTemplate template : TEMPLATES) {
            if (template.id().equals(id)) return template;
        }
        throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不支持的合同模板: " + templateId);
    }

    /**
     * 校验字段并渲染正文，返回可直接排版的结果。
     * 必填字段缺失 → BusinessException(PARAM_ERROR)；金额非法 → BusinessException(PARAM_ERROR)。
     */
    public RenderedContract render(String templateId, Map<String, Object> fields) {
        ContractTemplate template = get(templateId);
        Map<String, Object> input = fields == null ? Map.<String, Object>of() : fields;

        Map<String, String> values = new LinkedHashMap<>();
        for (FieldDef field : template.fields()) {
            String value = valueOf(input, field.key());
            if (field.required() && value.isBlank()) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), field.label() + "不能为空");
            }
            values.put(field.key(), value);
        }

        BigDecimal amount = parseAmount(values.get("amount"), "合同金额");
        values.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        values.put("amountCn", chineseAmount(amount));

        applyDefault(values, "signDate", LocalDate.now().format(DATE_FMT));
        applyDefault(values, "deliveryDate", "双方另行约定");
        applyDefault(values, "servicePeriod", "双方另行约定");
        applyDefault(values, "contactA", "—");
        applyDefault(values, "contactB", "—");

        List<String> lines = new ArrayList<>();
        for (String bodyLine : template.body()) {
            String rendered = bodyLine;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            // 未提供的占位符统一显示为破折号，避免正文出现 {{xxx}}
            rendered = PLACEHOLDER.matcher(rendered).replaceAll("—");
            lines.add(rendered);
        }

        String partyB = sanitizeFilename(values.getOrDefault("partyB", ""));
        String date = LocalDate.now().format(FILE_DATE);
        String fileName = template.name() + "-" + partyB + "-" + date + ".docx";
        String contractNo = "MC-" + date + "-"
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        return new RenderedContract(template.name(), contractNo, fileName, lines);
    }

    // ============================================================
    // 金额 · 阿拉伯数字 → 中文大写（覆盖万 / 亿）
    // ============================================================

    private static final char[] DIGITS = {'零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖'};
    /** 组内单位：下标 0 不使用，1=拾，2=佰，3=仟 */
    private static final char[] GROUP_UNITS = {' ', '拾', '佰', '仟'};
    /** 组间单位 */
    private static final String[] GROUP_NAMES = {"", "万", "亿", "万亿"};
    private static final int[] POW10 = {1, 10, 100, 1000};

    public static String chineseAmount(BigDecimal amount) {
        if (amount == null) return "";
        boolean negative = amount.signum() < 0;
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long integerPart = abs.longValue();
        BigDecimal decimalPart = abs.subtract(BigDecimal.valueOf(integerPart));
        int jiao = decimalPart.movePointRight(1).intValue() % 10;
        int fen = decimalPart.movePointRight(2).intValue() % 10;

        StringBuilder sb = new StringBuilder();
        if (negative) sb.append('负');
        if (integerPart == 0) {
            sb.append("零元");
        } else {
            sb.append(chineseInteger(integerPart)).append('元');
        }
        if (jiao == 0 && fen == 0) {
            sb.append('整');
        } else {
            if (jiao > 0) sb.append(DIGITS[jiao]).append('角');
            if (fen > 0) {
                if (jiao == 0) sb.append('零');
                sb.append(DIGITS[fen]).append('分');
            }
        }
        return sb.toString();
    }

    /** 整数部分按四位分组转换（个 / 万 / 亿 / 万亿） */
    private static String chineseInteger(long number) {
        if (number == 0) return "零";
        List<Integer> groups = new ArrayList<>();
        long remaining = number;
        while (remaining > 0) {
            groups.add((int) (remaining % 10000));
            remaining /= 10000;
        }

        StringBuilder sb = new StringBuilder();
        boolean pendingZero = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            int group = groups.get(i);
            if (group == 0) {
                if (sb.length() > 0) pendingZero = true;
                continue;
            }
            if (sb.length() > 0 && (pendingZero || group < 1000)) {
                sb.append('零');
            }
            sb.append(convertGroup(group)).append(GROUP_NAMES[i]);
            pendingZero = false;
        }
        return sb.toString();
    }

    /** 单个四位分组转换，如 1023 → 壹仟零贰拾叁 */
    private static String convertGroup(int group) {
        StringBuilder sb = new StringBuilder();
        boolean pendingZero = false;
        for (int pos = 3; pos >= 0; pos--) {
            int digit = (group / POW10[pos]) % 10;
            if (digit == 0) {
                if (sb.length() > 0 && group % POW10[pos] != 0) pendingZero = true;
            } else {
                if (pendingZero) {
                    sb.append('零');
                    pendingZero = false;
                }
                sb.append(DIGITS[digit]);
                if (pos > 0) sb.append(GROUP_UNITS[pos]);
            }
        }
        return sb.toString();
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private BigDecimal parseAmount(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), label + "不能为空");
        }
        String cleaned = raw.replace("¥", "").replace("￥", "")
                .replace(",", "").replace("，", "")
                .replace("元", "").replace(" ", "").trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), label + "必须为数字");
        }
        if (amount.signum() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), label + "不能为负数");
        }
        if (amount.compareTo(MAX_AMOUNT) >= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), label + "超出可处理范围");
        }
        return amount;
    }

    private String valueOf(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void applyDefault(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            values.put(key, defaultValue);
        }
    }

    private String sanitizeFilename(String value) {
        if (value == null || value.isBlank()) return "未命名";
        String cleaned = ILLEGAL_FILENAME.matcher(value.trim()).replaceAll("");
        if (cleaned.isEmpty()) return "未命名";
        return cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned;
    }

    private static List<ContractTemplate> buildTemplates() {
        List<ContractTemplate> templates = new ArrayList<>();

        templates.add(new ContractTemplate(
                "purchase",
                "采购合同",
                "适用于企业向供应商采购货物或软件的购销合同",
                List.of(
                        new FieldDef("partyA", "甲方（采购方）", true),
                        new FieldDef("partyB", "乙方（供应方）", true),
                        new FieldDef("productName", "采购内容", true),
                        new FieldDef("amount", "合同金额（元）", true),
                        new FieldDef("deliveryDate", "交付日期", false),
                        new FieldDef("signDate", "签订日期", false),
                        new FieldDef("contactA", "甲方联系人", false),
                        new FieldDef("contactB", "乙方联系人", false)
                ),
                List.of(
                        "## 第一条 合同标的",
                        "甲方向乙方采购{{productName}}，具体规格、数量及技术要求以双方确认的订单或附件为准。",
                        "## 第二条 合同金额",
                        "本合同总金额为人民币{{amount}}元（大写：{{amountCn}}），含税价。",
                        "## 第三条 交付",
                        "乙方应于{{deliveryDate}}前完成交付，交付方式与地点由双方另行确认。",
                        "## 第四条 双方联系人",
                        "甲方联系人：{{contactA}}",
                        "乙方联系人：{{contactB}}",
                        "## 第五条 付款方式",
                        "甲方在收到乙方开具的合规发票后十五个工作日内支付合同款项。",
                        "## 第六条 质量保证",
                        "乙方保证所交付标的物符合约定标准；质量保证期内出现质量问题，乙方应免费修复或更换。",
                        "## 第七条 违约责任",
                        "任何一方违反本合同约定，应赔偿守约方因此遭受的直接损失。",
                        "## 第八条 争议解决",
                        "因本合同引起的争议，双方应友好协商解决；协商不成的，提交甲方所在地人民法院诉讼解决。",
                        "## 第九条 合同生效",
                        "本合同自双方签字盖章之日起生效，一式两份，双方各执一份，具有同等法律效力。",
                        "",
                        "甲方（盖章）：____________________",
                        "授权代表：{{contactA}}",
                        "签订日期：{{signDate}}",
                        "",
                        "乙方（盖章）：____________________",
                        "授权代表：{{contactB}}",
                        "签订日期：{{signDate}}"
                )));

        templates.add(new ContractTemplate(
                "service",
                "服务合同",
                "适用于技术咨询、系统运维、知识库运营等服务类合作",
                List.of(
                        new FieldDef("partyA", "甲方（委托方）", true),
                        new FieldDef("partyB", "乙方（服务方）", true),
                        new FieldDef("serviceName", "服务内容", true),
                        new FieldDef("amount", "合同金额（元）", true),
                        new FieldDef("servicePeriod", "服务期限", false),
                        new FieldDef("signDate", "签订日期", false),
                        new FieldDef("contactA", "甲方联系人", false),
                        new FieldDef("contactB", "乙方联系人", false)
                ),
                List.of(
                        "## 第一条 服务内容",
                        "乙方为甲方提供{{serviceName}}服务，具体服务范围以双方确认的服务清单为准。",
                        "## 第二条 服务期限",
                        "服务期限：{{servicePeriod}}。",
                        "## 第三条 服务费用",
                        "本合同服务费用总额为人民币{{amount}}元（大写：{{amountCn}}），含税价。",
                        "## 第四条 双方联系人",
                        "甲方联系人：{{contactA}}",
                        "乙方联系人：{{contactB}}",
                        "## 第五条 双方义务",
                        "甲方应按约定提供必要的资料与配合；乙方应按约定标准、进度完成服务并保证服务质量。",
                        "## 第六条 保密条款",
                        "双方对在履行本合同过程中知悉的对方商业秘密及数据负有保密义务，未经对方书面同意不得披露。",
                        "## 第七条 违约责任",
                        "任何一方违反本合同约定，应赔偿守约方因此遭受的直接损失。",
                        "## 第八条 争议解决",
                        "因本合同引起的争议，双方应友好协商解决；协商不成的，提交甲方所在地人民法院诉讼解决。",
                        "## 第九条 合同生效",
                        "本合同自双方签字盖章之日起生效，一式两份，双方各执一份，具有同等法律效力。",
                        "",
                        "甲方（盖章）：____________________",
                        "授权代表：{{contactA}}",
                        "签订日期：{{signDate}}",
                        "",
                        "乙方（盖章）：____________________",
                        "授权代表：{{contactB}}",
                        "签订日期：{{signDate}}"
                )));

        return List.copyOf(templates);
    }
}
