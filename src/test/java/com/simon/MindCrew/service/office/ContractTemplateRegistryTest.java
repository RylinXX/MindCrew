package com.simon.MindCrew.service.office;

import com.simon.MindCrew.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数字员工 · 合同模板渲染与金额大写单元测试
 */
class ContractTemplateRegistryTest {

    private final ContractTemplateRegistry registry = new ContractTemplateRegistry();

    @Test
    void templateCatalogExposesTwoTemplates() {
        assertEquals(2, registry.templateViews().size());
        assertEquals("purchase", registry.get("purchase").id());
        assertEquals("service", registry.get("SERVICE").id());
        assertThrows(BusinessException.class, () -> registry.get("unknown"));
    }

    @Test
    void purchaseContractRendersRequiredFieldsAndAmountCn() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("partyA", "甲方公司");
        fields.put("partyB", "乙方公司");
        fields.put("productName", "知识库系统");
        fields.put("amount", "100000");

        ContractTemplateRegistry.RenderedContract rendered = registry.render("purchase", fields);
        assertEquals("采购合同", rendered.title());
        assertTrue(rendered.fileName().contains("采购合同"));
        assertTrue(rendered.lines().stream().anyMatch(line -> line.contains("壹拾万元整")));
    }

    @Test
    void missingRequiredFieldRejected() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("partyA", "甲方公司");
        fields.put("partyB", "乙方公司");
        // 缺 productName / amount
        BusinessException ex = assertThrows(BusinessException.class,
                () -> registry.render("purchase", fields));
        assertEquals(400, ex.getCode());
    }

    @Test
    void invalidAmountRejected() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("partyA", "甲方");
        fields.put("partyB", "乙方");
        fields.put("productName", "系统");
        fields.put("amount", "abc");
        assertThrows(BusinessException.class, () -> registry.render("purchase", fields));
    }

    @Test
    void chineseAmountCoversUnits() {
        assertEquals("壹拾万元整", ContractTemplateRegistry.chineseAmount(new BigDecimal("100000")));
        assertEquals("壹仟贰佰叁拾肆元整", ContractTemplateRegistry.chineseAmount(new BigDecimal("1234")));
        assertEquals("壹亿零壹元整", ContractTemplateRegistry.chineseAmount(new BigDecimal("100000001")));
    }
}
