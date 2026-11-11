#!/usr/bin/env python3
"""生成 MindCrew 系统测试报告（DOCX，图文版）。"""
from pathlib import Path
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

BASE = Path(__file__).resolve().parent
ASSETS = BASE / "assets"
OUT = BASE / "MindCrew-测试报告-2026-11-10.docx"

INK = RGBColor(0x26, 0x2C, 0x38)
GRAY = RGBColor(0x6B, 0x72, 0x80)
BRAND = RGBColor(0x3D, 0x5A, 0xFE)
GREEN = RGBColor(0x16, 0xA3, 0x6A)
RED = RGBColor(0xDC, 0x44, 0x44)

CN_FONT = "微软雅黑"


def set_cn(run, size=None, bold=None, color=None, font=CN_FONT):
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color is not None:
        run.font.color.rgb = color
    run.font.name = font
    rPr = run._element.get_or_add_rPr()
    rFonts = rPr.find(qn("w:rFonts"))
    if rFonts is None:
        rFonts = OxmlElement("w:rFonts")
        rPr.append(rFonts)
    rFonts.set(qn("w:eastAsia"), font)
    rFonts.set(qn("w:ascii"), font)
    rFonts.set(qn("w:hAnsi"), font)


def shade(cell, hex_fill):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_fill)
    tcPr.append(shd)


def cell_text(cell, text, size=9.5, bold=False, color=INK, align=WD_ALIGN_PARAGRAPH.LEFT):
    cell.text = ""
    p = cell.paragraphs[0]
    p.alignment = align
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(2)
    run = p.add_run(str(text))
    set_cn(run, size, bold, color)


def fix_table_layout(table, widths):
    tbl = table._tbl
    tblPr = tbl.tblPr
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tblPr.append(layout)
    for row in table.rows:
        for i, w in enumerate(widths):
            row.cells[i].width = Cm(w)
    grid = tbl.find(qn("w:tblGrid"))
    if grid is not None:
        for i, gc in enumerate(grid.findall(qn("w:gridCol"))):
            if i < len(widths):
                gc.set(qn("w:w"), str(int(widths[i] * 567)))


def add_table(doc, headers, rows, widths=None, header_fill="3D5AFE", zebra=True):
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    hdr = table.rows[0].cells
    for i, h in enumerate(headers):
        cell_text(hdr[i], h, 9.5, True, RGBColor(0xFF, 0xFF, 0xFF))
        shade(hdr[i], header_fill)
    for r_idx, row in enumerate(rows):
        cells = table.add_row().cells
        for i, val in enumerate(row):
            color = INK
            bold = False
            if isinstance(val, tuple):
                val, color, bold = val
            cell_text(cells[i], val, 9, bold, color)
            if zebra and r_idx % 2 == 1:
                shade(cells[i], "F5F7FB")
    if widths:
        fix_table_layout(table, widths)
    for r_idx, row in enumerate(table.rows):
        trPr = row._tr.get_or_add_trPr()
        cant = OxmlElement("w:cantSplit")
        trPr.append(cant)
        if r_idx == 0:
            th = OxmlElement("w:tblHeader")
            trPr.append(th)
    doc.add_paragraph()
    return table


def add_heading(doc, text, level=1):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(14 if level == 1 else 10)
    p.paragraph_format.space_after = Pt(6)
    run = p.add_run(text)
    set_cn(run, 15 if level == 1 else 12.5, True, BRAND if level == 1 else INK)
    p.paragraph_format.keep_with_next = True
    if level == 1:
        pPr = p._p.get_or_add_pPr()
        pBdr = OxmlElement("w:pBdr")
        bottom = OxmlElement("w:bottom")
        bottom.set(qn("w:val"), "single")
        bottom.set(qn("w:sz"), "8")
        bottom.set(qn("w:space"), "2")
        bottom.set(qn("w:color"), "D9E0EE")
        pBdr.append(bottom)
        pPr.append(pBdr)
    return p


def add_para(doc, text, size=10.5, color=INK, bold=False, align=WD_ALIGN_PARAGRAPH.JUSTIFY,
             space_after=6, indent=True):
    p = doc.add_paragraph()
    p.alignment = align
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.line_spacing = 1.45
    if indent:
        p.paragraph_format.first_line_indent = Pt(21)
    run = p.add_run(text)
    set_cn(run, size, bold, color)
    return p


def add_figure(doc, image, caption, width_cm=16.2):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run()
    run.add_picture(str(ASSETS / image), width=Cm(width_cm))
    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cap.paragraph_format.space_after = Pt(8)
    r = cap.add_run(caption)
    set_cn(r, 9, False, GRAY)


def add_page_number_footer(section):
    footer = section.footer
    p = footer.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r1 = p.add_run("MindCrew 系统测试报告 · 2026-11-10 · 第 ")
    set_cn(r1, 8.5, False, GRAY)
    fld1 = OxmlElement("w:fldSimple")
    fld1.set(qn("w:instr"), "PAGE")
    run_el = OxmlElement("w:r")
    t = OxmlElement("w:t")
    t.text = "1"
    run_el.append(t)
    fld1.append(run_el)
    p._p.append(fld1)
    r2 = p.add_run(" 页")
    set_cn(r2, 8.5, False, GRAY)


# ============================================================
doc = Document()
sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21.0), Cm(29.7)
sec.top_margin, sec.bottom_margin = Cm(2.2), Cm(2.0)
sec.left_margin, sec.right_margin = Cm(2.4), Cm(2.4)
add_page_number_footer(sec)

# ---------- 封面 ----------
for _ in range(3):
    doc.add_paragraph()
t = doc.add_paragraph()
t.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = t.add_run("MindCrew 系统测试报告")
set_cn(r, 30, True, INK)

t2 = doc.add_paragraph()
t2.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = t2.add_run("Agentic RAG 企业知识问答系统 · 本轮新增模块与核心链路")
set_cn(r, 13, False, GRAY)

doc.add_paragraph()
line = doc.add_paragraph()
line.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = line.add_run("━━━━━━━━━━━━━━━━━━━━━━━━━━")
set_cn(r, 10, False, RGBColor(0xC9, 0xD3, 0xE8))

meta = [
    ("报告版本", "V1.0"),
    ("测试日期", "2026-11-10"),
    ("测试对象", "MindCrew 客服中心 / 检索分析 / 数字员工 / AI 问数 / 核心链路修复"),
    ("执行方式", "自动化单元测试 + 构件级构建验证 + 生成物冒烟 + 静态评审"),
    ("执行结论", "通过（64/64 用例通过，构建与生成物验证通过）"),
    ("报告编制", "测试执行 · Lead（大肥鱼）"),
]
table = doc.add_table(rows=0, cols=2)
table.alignment = WD_TABLE_ALIGNMENT.CENTER
for k, v in meta:
    cells = table.add_row().cells
    cell_text(cells[0], k, 10, True, GRAY)
    cell_text(cells[1], v, 10, False, INK)
    cells[0].width = Cm(3.2)
    cells[1].width = Cm(12.5)

doc.add_page_break()

# ---------- 1 概述 ----------
add_heading(doc, "1  测试概述")
add_para(doc, "本次测试针对本轮系统完善交付内容执行，覆盖五个部分：新增的客服中心（含 SSO 单点登录与钉钉渠道）、"
              "新增的检索分析、新增的数字员工交付物（PPT/Word/合同）、新增的 AI 问数，以及为支撑上述能力而修复的核心链路"
              "（消息重复入库、检索日志读取、模型成本与时延埋点）。")
add_para(doc, "测试目标有三个：确认新增模块的核心逻辑正确、边界与异常可控；确认全仓库可正常编译、前端可零错误构建；"
              "确认 PPT/Word/合同等交付物能真实生成并可被 Office 组件重新打开。")

add_heading(doc, "1.1 测试范围", 2)
add_table(doc,
          ["模块", "覆盖内容", "测试形式"],
          [
              ["客服中心", "访客 token 签发/校验/篡改/过期；页面配置解析与公开视图；SSO 回跳防开放重定向；会话字段截断；转人工关键词", "单元测试（15 项）"],
              ["数字员工", "Markdown 解析与行内清洗；合同模板校验；金额中文大写；PPTX/DOCX/合同真实生成与回读", "单元测试 + 生成物冒烟（12 项）"],
              ["AI 问数", "9 个白名单指标目录；描述字段完整性；days/limit 越界钳制", "单元测试（3 项）"],
              ["检索分析", "CSV 导出转义、公式注入防护、换行与空值处理", "单元测试（3 项）"],
              ["RAG 融合", "RRF 去重、排序、TopN、HYBRID 标记", "单元测试（3 项）"],
              ["原有回归", "查询路由、记忆读写、联网搜索降级、KB ID 解析、BM25/向量过滤、Prompt 组装、文档抽取等", "单元测试（28 项）"],
              ["工程构建", "后端全量 javac 编译；前端 vue-tsc + vite 构建；产物体积", "构建验证"],
          ],
          widths=[2.6, 10.6, 3.4])

add_heading(doc, "1.2 测试方法", 2)
add_para(doc, "单元测试使用 JUnit 5 与 Mockito，不启动 Spring 容器，涉及 Redis / MySQL / 外部 LLM 的依赖全部以 Mock 或纯逻辑方式隔离，"
              "保证可离线、可重复执行。生成物测试真实调用 Apache POI 生成文件，再用 POI 重新打开校验结构完整性。"
              "构建验证分别执行 Maven 全量编译与 pnpm run build（含 vue-tsc 类型检查）。")
add_para(doc, "本次未执行需要中间件与在线模型的端到端测试（本机没有 MySQL / Redis / Milvus / LLM Key），"
              "该类验证整理在第八章“未覆盖项与风险”中，作为上线前必做清单。")

# ---------- 2 环境 ----------
add_heading(doc, "2  测试环境")
add_table(doc,
          ["类别", "项目", "版本 / 说明"],
          [
              ["操作系统", "macOS", "Apple Silicon（arm64）"],
              ["后端运行时", "Zulu OpenJDK", "17.0.13"],
              ["构建工具", "Apache Maven", "3.6.1（离线仓库 /tmp/m2repo）"],
              ["应用框架", "Spring Boot", "3.3.5 / Spring AI 1.1.4"],
              ["测试框架", "JUnit Platform Console", "1.10.2（Jupiter 5）"],
              ["文档组件", "Apache POI", "5.2.5"],
              ["前端运行时", "Node.js / pnpm", "v24.19.0 / 12.11.2"],
              ["前端构建", "vue-tsc + Vite", "3.3.14 / 7.3.8"],
              ["中间件", "MySQL / Redis / Milvus / MinIO", "未启动（本次不做端到端）"],
              ["模型服务", "百炼 / Tavily / 钉钉", "未调用（离线）"],
          ],
          widths=[2.8, 5.6, 8.2])

# ---------- 3 结果总览 ----------
add_heading(doc, "3  测试结果总览")
add_para(doc, "自动化用例共 64 项，分布在 22 个测试类中，全部通过，失败 0 项，通过率 100%，总执行耗时 1.72 秒。"
              "其中本轮新增 10 个测试类共 36 项用例，原有回归 12 个类共 28 项用例全部保持通过，未出现回归失败。")
add_table(doc,
          ["统计项", "数值", "说明"],
          [
              ["测试类总数", "22", "JUnit Jupiter 自动发现"],
              ["用例总数", "64", "其中本轮新增 36 项，原有回归 28 项"],
              ["通过 / 失败 / 跳过", ("64 / 0 / 0", GREEN, True), "通过率 100%"],
              ["总执行耗时", "1.72 s", "纯内存测试，无网络与数据库依赖"],
              ["后端编译", ("BUILD SUCCESS", GREEN, True), "237 个 Java 源文件，3.60 s"],
              ["前端构建", ("exit 0", GREEN, True), "vue-tsc 零错误 + vite 构建，5.27 s"],
              ["生成物冒烟", ("4/4 通过", GREEN, True), "PPTX / DOCX / 采购合同 / 服务合同"],
              ["评审缺陷", "15 项", "严重 2、中等 2、轻微 11；已修复 9 项，记录 6 项"],
          ],
          widths=[3.6, 3.2, 9.8])
add_figure(doc, "chart-modules.png", "图 1  自动化用例按模块分布（64 项全部通过）")

# ---------- 4 用例明细 ----------
add_heading(doc, "4  用例明细")
add_heading(doc, "4.1  本轮新增测试类", 2)
add_table(doc,
          ["测试类", "用例数", "关键覆盖点", "结果"],
          [
              ["CsTokenServiceTest", "5", "访客 token 往返、签名篡改、过期拒识、空凭证、HMAC 已知向量", ("通过", GREEN, True)],
              ["CsSsoRedirectTest", "2", "开放重定向拦截（//、http、javascript、反斜杠）、站内路径放行", ("通过", GREEN, True)],
              ["CsConfigServiceTest", "5", "JSON 容错解析、转人工关键词、非法工作时间兜底、公开视图字段、KB 范围解析", ("通过", GREEN, True)],
              ["CsSessionServiceTest", "3", "超长访客字段截断、空渠道兜底 web、会话号生成", ("通过", GREEN, True)],
              ["ContractTemplateRegistryTest", "5", "模板目录、必填校验、金额非法拦截、合同渲染、金额大写", ("通过", GREEN, True)],
              ["MarkdownParserTest", "3", "三级标题、有序/无序列表、行内标记清洗、空输入", ("通过", GREEN, True)],
              ["OfficeDocumentServiceTest", "4", "PPTX/DOCX/合同生成并用 POI 回读、空内容仅封面", ("通过", GREEN, True)],
              ["MetricCatalogTest", "3", "9 指标白名单、前端字段完整性、days 越界钳制", ("通过", GREEN, True)],
              ["CsvEscapeTest", "3", "公式注入前缀、逗号/引号转义、换行清洗", ("通过", GREEN, True)],
              ["RRFFusionTest", "3", "两路命中优先、HYBRID 标记、TopN 截断、空输入", ("通过", GREEN, True)],
          ],
          widths=[4.3, 1.6, 8.9, 1.8])

add_heading(doc, "4.2  原有回归测试", 2)
add_table(doc,
          ["测试类", "用例数", "覆盖点", "结果"],
          [
              ["QueryRouterToolSelectionTest", "3", "复合问题/追问的工具有限集合路由", ("通过", GREEN, True)],
              ["ExplicitMemoryExtractorTest", "2", "长期偏好提取与普通问题忽略", ("通过", GREEN, True)],
              ["MemoryToolTest", "3", "记忆读写、空负载拒绝、合并持久化", ("通过", GREEN, True)],
              ["WebSearchToolTest", "4", "无 Key/禁用降级、远端错误降级、结果映射", ("通过", GREEN, True)],
              ["MindCrewChatControllerTest", "1", "非法 KB ID 映射为参数错误", ("通过", GREEN, True)],
              ["KbIdsParserTest", "5", "空白/去重/顺序/非法 token", ("通过", GREEN, True)],
              ["KnowledgeBaseServiceImplTest", "1", "Markdown 上传入库", ("通过", GREEN, True)],
              ["DocumentExtractorTest", "2", "Markdown 抽取与扩展名别名", ("通过", GREEN, True)],
              ["PromptAssemblerTest", "1", "记忆与网页引用注入", ("通过", GREEN, True)],
              ["SourcePayloadFactoryTest", "1", "知识库/网页来源载荷", ("通过", GREEN, True)],
              ["VectorRetrieverFilterTest", "3", "Milvus 过滤表达式（分类/KB/空）", ("通过", GREEN, True)],
              ["BM25RetrieverKbScopeTest", "2", "KB 范围拼入关键词检索 SQL", ("通过", GREEN, True)],
          ],
          widths=[5.2, 1.6, 8.2, 1.8])

add_para(doc, "说明：MediRagApplicationTests 为 @SpringBootTest 启动类测试，需真实 MySQL/Redis/Milvus，本次按范围排除；"
              "上线环境需单独执行。")

# ---------- 5 构建与生成物 ----------
add_heading(doc, "5  构建与交付物验证")
add_heading(doc, "5.1  构建耗时", 2)
add_figure(doc, "chart-duration.png", "图 2  各验证阶段耗时（本机离线环境）")
add_table(doc,
          ["验证项", "命令 / 工具", "结果", "产物"],
          [
              ["后端全量编译", "mvn -o -DskipTests compile", ("成功", GREEN, True), "237 个源文件，target/classes 2.8 MB"],
              ["测试编译", "mvn -o -DskipTests test-compile", ("成功", GREEN, True), "23 个测试源文件"],
              ["单元测试", "JUnit Platform Console 1.10.2", ("64/64 通过", GREEN, True), "XML 报告 + 控制台树形报告"],
              ["前端类型检查与构建", "pnpm run build（vue-tsc + vite）", ("exit 0", GREEN, True), "dist 3.5 MB，零 TS 错误"],
          ],
          widths=[3.4, 6.2, 2.6, 4.8])

add_heading(doc, "5.2  文档生成物冒烟", 2)
add_para(doc, "数字员工模块使用真实 Apache POI 生成文件，并用 POI 重新打开校验，样例输入为 3 个章节、8 条要点的 Markdown 报告。")
add_table(doc,
          ["生成物", "输入", "体积", "结构校验", "结果"],
          [
              ["PPTX 演示文稿", "标题 + 副标题 + Markdown", "30,146 B", "POI 回读 5 页（封面/目录/章节）", ("通过", GREEN, True)],
              ["DOCX 报告", "标题 + Markdown", "3,188 B", "POI 回读 12 个段落，页脚可创建", ("通过", GREEN, True)],
              ["采购合同 DOCX", "甲方/乙方/采购内容/金额", "3,818 B", "含标题与条款段落，金额大写正确", ("通过", GREEN, True)],
              ["服务合同 DOCX", "甲方/乙方/服务内容/金额", "3,787 B", "含标题与条款段落", ("通过", GREEN, True)],
              ["金额大写", "100000 / 1234 / 100000001", "—", "壹拾万元整 / 壹仟贰佰叁拾肆元整 / 壹亿零壹元整", ("通过", GREEN, True)],
          ],
          widths=[3.2, 4.2, 2.0, 6.0, 1.8])

# ---------- 6 前端产物 ----------
add_heading(doc, "6  前端构建产物分析")
add_para(doc, "前端构建总体积 3.5 MB，类型检查零错误。ECharts 与 Element Plus 为项目既有依赖，本次新增页面均按路由懒加载，"
              "新增的客服、检索分析、数字员工、AI 问数页面各自独立分包，不影响首屏主包体积。")
add_figure(doc, "chart-bundle.png", "图 3  前端构建产物 Top 8")

# ---------- 7 缺陷 ----------
add_heading(doc, "7  评审缺陷与修复记录")
add_para(doc, "本轮采用独立静态评审（逐文件核对 import、方法签名、表字段、路由与权限）配合真实编译验证，共发现 15 项问题，"
              "其中 2 项为会导致数据污染的严重问题，均已修复并复验通过。")
add_figure(doc, "chart-defects.png", "图 4  缺陷严重度与处理状态（15 项：修复 9、记录 6）")
add_table(doc,
          ["编号", "严重级", "问题描述", "状态"],
          [
              ["D01", ("严重", RED, True), "RagPipeline 中 MedMessage 与 QaMessage 同映射 qa_message 表导致每条消息重复入库，污染问答量/检索分析/成本统计", ("已修复", GREEN, True)],
              ["D02", ("严重", RED, True), "ChatController 检索日志接口读取 MedMessage.exist=false 字段，恒为空", ("已修复", GREEN, True)],
              ["D03", ("中等", INK, True), "SSO 一次性 ticket 采用 get 后 delete，并发下可能重复消费", ("已修复", GREEN, True)],
              ["D04", ("中等", INK, True), "SSO 登录后建会话未绑定本地用户（sso_user_id 恒空）", ("已修复", GREEN, True)],
              ["D05", ("轻微", GRAY, True), "公开建会话访客字段未按列长截断，超长输入会 500", ("已修复", GREEN, True)],
              ["D06", ("轻微", GRAY, True), "@Audited targetIdParam 传入 \"id\" 被当作字面值", ("已修复", GREEN, True)],
              ["D07", ("轻微", GRAY, True), "SSO 签名比较非恒定时间，存在时序侧信道", ("已修复", GREEN, True)],
              ["D08", ("轻微", GRAY, True), "重复转人工会产生多条系统提示", ("已修复", GREEN, True)],
              ["D09", ("轻微", GRAY, True), "检索分析 CSV 导出未防公式注入", ("已修复", GREEN, True)],
              ["D10", ("轻微", GRAY, True), "cs_session.source_app_id 未回填（SSO 来源系统）", ("已记录", GRAY, True)],
              ["D11", ("轻微", GRAY, True), "cs_message.retrieval_log 列未写入，客服工作台暂不展示检索日志", ("已记录", GRAY, True)],
              ["D12", ("轻微", GRAY, True), "公开客服页与既有 ChatView 一致使用 v-html，未接 sanitizer", ("已记录", GRAY, True)],
              ["D13", ("轻微", GRAY, True), "cs_config 保存用 updateById，null 字段无法清空（需传空串）", ("已记录", GRAY, True)],
              ["D14", ("轻微", GRAY, True), "external 模式 external_cs_url 为空时静默回落内部 ticket", ("已记录", GRAY, True)],
              ["D15", ("轻微", GRAY, True), "检索分析 days 接口允许 1~365，前端仅暴露 7/30/90", ("已记录", GRAY, True)],
          ],
          widths=[1.4, 1.7, 10.2, 2.3])

# ---------- 8 未覆盖 ----------
add_heading(doc, "8  未覆盖项与风险")
add_table(doc,
          ["未覆盖项", "原因", "风险", "建议"],
          [
              ["端到端联调（问答/检索/入库）", "本机无 MySQL/Redis/Milvus/LLM", "中", "上线环境按 sql/README.md 初始化后跑全链路冒烟"],
              ["SSO 真实对接", "无客户系统 appId/appSecret", "中", "用 bash 签名脚本对 /api/cs/sso/entry 做一次真实跳转"],
              ["钉钉真实回调", "无钉钉企业应用", "中", "配置真实渠道后用 outgoing 机器人回调验证 markdown 返回"],
              ["浏览器 UI 点击验收", "未启动前后端服务", "低", "部署后按 7 个新页面做手工验收"],
              ["性能与并发压测", "无中间件", "中", "对问答 SSE、检索分析、客服轮询做 50~100 并发压测"],
              ["安全渗透（XSS/越权/提示注入）", "本轮仅静态评审", "中", "上线前对公开客服入口做专项安全测试"],
              ["AI 回答质量评测", "无标准评测集与模型 Key", "中", "沉淀 Golden 集，按命中率/引用准确率回归"],
          ],
          widths=[4.6, 4.0, 1.4, 5.4])

# ---------- 9 结论 ----------
add_heading(doc, "9  结论与建议")
add_para(doc, "本轮交付通过测试：64 项自动化用例全部通过，后端 237 个源文件全量编译成功，前端类型检查零错误并成功构建，"
              "四类文档生成物均真实生成且可被 POI 重新打开，两个会导致数据污染的严重缺陷已修复并复验。"
              "在“无中间件”的本机环境下，已达到可交付的构件级质量门槛。")
add_para(doc, "建议上线前按以下顺序补验：一是初始化数据库并跑通问答、检索分析、客服、导出的端到端链路；"
              "二是用真实签名完成一次 SSO 跳转与一次钉钉回调；三是对公开客服入口做安全与限流专项；"
              "四是接入真实模型后建立问答质量评测集，把检索分析指标纳入每次发布的回归对比。", space_after=10)

# ---------- 附录 ----------
add_heading(doc, "附录 A  复现命令")
for cmd in [
    "export JAVA_HOME=$(ls -d /tmp/jdk17/*/zulu-*.jdk/Contents/Home | head -1)",
    "cd MindCrew/MindCrew",
    "mvn -o -Dmaven.repo.local=/tmp/m2repo -DskipTests compile",
    "mvn -o -Dmaven.repo.local=/tmp/m2repo -DskipTests test-compile",
    "java -jar junit-platform-console-standalone.jar --class-path target/classes:target/test-classes:<deps> \\",
    "     --scan-class-path target/test-classes --exclude-classname '.*MediRagApplicationTests' --details tree",
    "cd MindCrew-frontend && pnpm run build",
]:
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Pt(12)
    p.paragraph_format.space_after = Pt(2)
    r = p.add_run(cmd)
    set_cn(r, 9, False, RGBColor(0x33, 0x3A, 0x4A), font="Menlo")

add_heading(doc, "附录 B  附件清单")
add_table(doc,
          ["文件", "说明"],
          [
              ["assets/chart-modules.png", "图 1 用例模块分布"],
              ["assets/chart-duration.png", "图 2 验证阶段耗时"],
              ["assets/chart-bundle.png", "图 3 前端产物 Top 8"],
              ["assets/chart-defects.png", "图 4 缺陷严重度与状态"],
              ["../本次完善说明.md", "本轮功能完善说明与上线步骤"],
          ],
          widths=[6.0, 9.6])

doc.save(OUT)
print("saved:", OUT)
