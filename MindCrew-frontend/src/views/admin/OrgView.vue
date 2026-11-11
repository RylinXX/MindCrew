<template>
  <div class="org-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">组织 & 职位</h2>
          <span class="title-tag">任务 7 · 职位独立 KB</span>
        </div>
        <p class="page-desc">
          部门是组织结构（树形），职位是业务角色（HR 经理 / Java 工程师 ...），与系统角色（admin/auditor/user）独立。
          知识库可以按职位授权可读 / 可写 / 可管理，从而实现 <strong>"HR 文件只对人事看得到，财务文件只对财务看得到"</strong>。
        </p>
      </div>
    </header>

    <div class="org-main">
      <!-- 左侧 部门树 -->
      <section class="left-pane">
        <div class="pane-header">
          <div class="pane-title">
            <span class="pane-icon">
              <el-icon size="15"><OfficeBuilding /></el-icon>
            </span>
            <h3>部门</h3>
            <span class="pane-count">{{ deptList.length }}</span>
          </div>
          <button class="btn-mini" @click="openDeptDialog(null)">
            <el-icon size="13"><Plus /></el-icon> 新增
          </button>
        </div>
        <div class="dept-search">
          <el-input
            v-model="deptKeyword"
            size="small"
            clearable
            placeholder="搜索部门"
            :prefix-icon="Search"
          />
        </div>
        <el-tree
          ref="treeRef"
          :data="deptTree"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          :filter-node-method="filterDeptNode"
          default-expand-all
          highlight-current
          empty-text="暂无部门"
          @node-click="onSelectDept"
          v-loading="loadingDept"
          class="dept-tree"
        >
          <template #default="{ node, data }">
            <div class="tree-node">
              <span class="node-icon">
                <el-icon size="13">
                  <FolderOpened v-if="data.parentId" />
                  <OfficeBuilding v-else />
                </el-icon>
              </span>
              <span class="node-label">{{ data.name }}</span>
              <span v-if="deptPosCount(data.id)" class="node-badge" :title="`${deptPosCount(data.id)} 个职位`">
                {{ deptPosCount(data.id) }}
              </span>
              <div class="tree-actions">
                <button class="icon-btn-sm" @click.stop="openDeptDialog(data)" title="编辑">
                  <el-icon size="12"><Edit /></el-icon>
                </button>
                <button class="icon-btn-sm danger" @click.stop="delDept(data)" title="删除">
                  <el-icon size="12"><Delete /></el-icon>
                </button>
              </div>
            </div>
          </template>
        </el-tree>
        <div class="pane-foot">
          <span>共 {{ deptList.length }} 个部门</span>
          <span>点击部门查看职位</span>
        </div>
      </section>

      <!-- 右侧 职位列表 -->
      <section class="right-pane">
        <div class="pane-header">
          <div class="pane-title">
            <span class="pane-icon pos-icon">
              <el-icon size="15"><Tickets /></el-icon>
            </span>
            <h3>
              {{ selectedDept ? selectedDept.name + ' · 职位' : '全部职位' }}
            </h3>
            <span class="pane-count">{{ positions.length }}</span>
          </div>
          <button class="btn-primary-mini" @click="openPosDialog(null)">
            <el-icon size="13"><Plus /></el-icon> 新增职位
          </button>
        </div>
        <div v-loading="loadingPos" class="pos-grid">
          <article v-for="p in positions" :key="p.id" class="pos-card">
            <header>
              <span class="pos-code">{{ p.code }}</span>
              <span class="pos-level">L{{ p.level }}</span>
            </header>
            <h4>{{ p.name }}</h4>
            <p>{{ p.description || '（无描述）' }}</p>
            <footer>
              <button class="icon-btn" @click="openPosDialog(p)" title="编辑">
                <el-icon><Edit /></el-icon>
              </button>
              <button class="icon-btn danger" @click="delPos(p)" title="删除">
                <el-icon><Delete /></el-icon>
              </button>
            </footer>
          </article>
          <div v-if="!loadingPos && positions.length === 0" class="empty">
            <span class="empty-icon"><el-icon :size="26"><Tickets /></el-icon></span>
            <p>{{ selectedDept ? '该部门还没有职位' : '还没有配置职位' }}</p>
            <span class="empty-hint">点击右上角「新增职位」开始配置</span>
          </div>
        </div>
      </section>
    </div>

    <!-- 部门编辑对话框 -->
    <el-dialog v-model="deptDialogVisible" :title="deptForm.id ? '编辑部门' : '新建部门'" width="480px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="deptForm.name" placeholder="如 技术中心" maxlength="60" />
        </el-form-item>
        <el-form-item label="上级">
          <el-select v-model="deptForm.parentId" placeholder="选一级则留空" clearable style="width:100%">
            <el-option v-for="d in deptList" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="deptForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="deptForm.sortOrder" :min="0" :max="9999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deptDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDept" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 职位编辑对话框 -->
    <el-dialog v-model="posDialogVisible" :title="posForm.id ? '编辑职位' : '新建职位'" width="520px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="posForm.name" placeholder="如 Java 工程师" maxlength="60" />
        </el-form-item>
        <el-form-item label="Code" required>
          <el-input v-model="posForm.code" placeholder="英文唯一标识，如 java_dev" maxlength="40" :disabled="!!posForm.id" />
          <div style="font-size:11px;color:var(--ink-3);margin-top:2px">code 创建后不可改 · 用于 ACL 关联</div>
        </el-form-item>
        <el-form-item label="所属部门">
          <el-select v-model="posForm.departmentId" placeholder="可跨部门 · 留空" clearable style="width:100%">
            <el-option v-for="d in deptList" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="职级">
          <el-input-number v-model="posForm.level" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="posForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="posDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="savePos" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, OfficeBuilding, FolderOpened, Search, Tickets } from '@element-plus/icons-vue'
import { departmentApi, positionApi, type Department, type Position, type DeptNode } from '@/api/orgAcl'

const loadingDept = ref(false)
const loadingPos  = ref(false)
const saving      = ref(false)

const deptTree   = ref<DeptNode[]>([])
const deptList   = ref<Department[]>([])
const positions  = ref<Position[]>([])
const allPositions = ref<Position[]>([])
const selectedDept = ref<Department | null>(null)
const deptKeyword = ref('')
const treeRef = ref()

// 每个部门下的职位数（用全量职位统计，避免切换部门时数字变化）
const deptPosCount = (deptId: number) => allPositions.value.filter(p => p.departmentId === deptId).length

watch(deptKeyword, (v) => treeRef.value?.filter(v))
const filterDeptNode = (value: string, data: DeptNode) =>
  !value || String(data.name || '').includes(value.trim())

async function loadAllPositions() {
  try {
    const res: any = await positionApi.list()
    allPositions.value = res?.data ?? res ?? []
  } catch {
    allPositions.value = []
  }
}

async function loadDepts() {
  loadingDept.value = true
  try {
    const [tree, list]: any = await Promise.all([departmentApi.tree(), departmentApi.list()])
    deptTree.value = tree?.data ?? tree ?? []
    deptList.value = list?.data ?? list ?? []
  } finally { loadingDept.value = false }
}

async function loadPositions(deptId?: number) {
  loadingPos.value = true
  try {
    const res: any = await positionApi.list(deptId)
    positions.value = res?.data ?? res ?? []
  } finally { loadingPos.value = false }
}

function onSelectDept(d: Department) {
  selectedDept.value = d
  loadPositions(d.id)
}

onMounted(async () => {
  await loadDepts()
  await loadPositions()
  await loadAllPositions()
})

// ── 部门 CRUD ──
const deptDialogVisible = ref(false)
const deptForm = reactive<Partial<Department>>({})

function openDeptDialog(d: Department | null) {
  if (d) {
    Object.assign(deptForm, d)
  } else {
    Object.keys(deptForm).forEach(k => (deptForm as any)[k] = undefined)
    deptForm.sortOrder = 100
    deptForm.enabled = 1
  }
  deptDialogVisible.value = true
}

async function saveDept() {
  if (!deptForm.name?.trim()) return ElMessage.warning('请填部门名')
  saving.value = true
  try {
    if (deptForm.id) await departmentApi.update(deptForm.id, deptForm)
    else             await departmentApi.create(deptForm)
    ElMessage.success('已保存')
    deptDialogVisible.value = false
    await loadDepts()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { saving.value = false }
}

async function delDept(d: Department) {
  try {
    await ElMessageBox.confirm(`确认删除部门「${d.name}」？`, '警告', { type: 'warning' })
    await departmentApi.delete(d.id)
    ElMessage.success('已删除')
    await loadDepts()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '删除失败')
  }
}

// ── 职位 CRUD ──
const posDialogVisible = ref(false)
const posForm = reactive<Partial<Position>>({})

function openPosDialog(p: Position | null) {
  if (p) {
    Object.assign(posForm, p)
  } else {
    Object.keys(posForm).forEach(k => (posForm as any)[k] = undefined)
    posForm.level = 1
    posForm.sortOrder = 100
    posForm.enabled = 1
    if (selectedDept.value) posForm.departmentId = selectedDept.value.id
  }
  posDialogVisible.value = true
}

async function savePos() {
  if (!posForm.name?.trim()) return ElMessage.warning('请填职位名')
  if (!posForm.code?.trim()) return ElMessage.warning('请填 code')
  saving.value = true
  try {
    if (posForm.id) await positionApi.update(posForm.id, posForm)
    else            await positionApi.create(posForm)
    ElMessage.success('已保存')
    posDialogVisible.value = false
    await loadPositions(selectedDept.value?.id)
    await loadAllPositions()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { saving.value = false }
}

async function delPos(p: Position) {
  try {
    await ElMessageBox.confirm(`确认删除职位「${p.name}」？已绑定此职位的用户 / KB 授权将失效。`, '警告', { type: 'warning' })
    await positionApi.delete(p.id)
    ElMessage.success('已删除')
    await loadPositions(selectedDept.value?.id)
    await loadAllPositions()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '删除失败')
  }
}
</script>

<style scoped>
.org-page { padding: 28px 32px 48px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { margin-bottom: 24px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); }
.title-tag { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #ddd6fe, #c4b5fd); color: #5b21b6; }
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 820px; line-height: 1.65; }

.org-main { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 20px; align-items: stretch; }
.left-pane, .right-pane { background: var(--bg-surface); border: 1px solid var(--line);
  border-radius: 14px; padding: 16px; }
.left-pane { display: flex; flex-direction: column; min-height: 560px; padding: 0; overflow: hidden; }
.right-pane { display: flex; flex-direction: column; }
.pane-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.left-pane .pane-header { padding: 16px 16px 10px; margin-bottom: 0; }
.pane-title { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.pane-title h3 { font-size: 14px; font-weight: 700; color: var(--ink-1); white-space: nowrap; }
.pane-icon {
  width: 28px; height: 28px; border-radius: 8px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--brand-soft); color: var(--brand);
}
.pane-icon.pos-icon { background: rgba(124, 58, 237, .10); color: #7C3AED; }
.pane-count {
  font-size: 11px; font-weight: 600; color: var(--ink-3);
  background: var(--bg-subtle); border: 1px solid var(--line);
  padding: 1px 8px; border-radius: 999px;
}
.dept-search { padding: 0 16px 10px; }
.btn-mini, .btn-primary-mini {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 5px 10px; font-size: 12px; border-radius: 6px;
  border: 1px solid var(--line); background: var(--bg-elevated);
  color: var(--ink-2); cursor: pointer;
}
.btn-mini:hover { background: var(--bg-hover); }
.btn-primary-mini { background: var(--brand, #3D5AFE); color: #fff; border-color: var(--brand, #3D5AFE); }

.dept-tree { font-size: 13px; flex: 1; min-height: 0; overflow-y: auto; padding: 2px 8px 8px; }
.dept-tree :deep(.el-tree-node__content) {
  height: 36px; border-radius: 8px; padding-right: 6px; margin: 1px 0;
  transition: background 150ms ease;
}
.dept-tree :deep(.el-tree-node__content:hover) { background: var(--bg-hover); }
.dept-tree :deep(.el-tree-node.is-current > .el-tree-node__content) { background: var(--brand-soft); }
.dept-tree :deep(.el-tree-node.is-current .node-label) { color: var(--brand-ink); font-weight: 700; }
.dept-tree :deep(.el-tree-node.is-current .node-icon) { background: #fff; color: var(--brand); }
.dept-tree :deep(.el-tree-node__expand-icon) { color: var(--ink-4); }
.tree-node { display: flex; align-items: center; flex: 1; min-width: 0; gap: 8px; padding-right: 2px; }
.node-icon {
  width: 22px; height: 22px; border-radius: 6px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--bg-subtle); color: var(--ink-3);
  transition: background 150ms ease, color 150ms ease;
}
.node-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-2); }
.node-badge {
  flex-shrink: 0; font-size: 10.5px; font-weight: 600; color: var(--ink-3);
  background: var(--bg-subtle); border: 1px solid var(--line);
  padding: 0 6px; border-radius: 999px; line-height: 16px;
}
.tree-actions { display: flex; gap: 2px; opacity: 0; transition: opacity 150ms ease; }
.tree-node:hover .tree-actions { opacity: 1; }
.pane-foot {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 16px 12px; border-top: 1px dashed var(--line);
  font-size: 11px; color: var(--ink-4); background: var(--bg-page);
}
.icon-btn-sm {
  width: 22px; height: 22px; border-radius: 4px;
  background: transparent; border: none; color: var(--ink-3); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.icon-btn-sm:hover { background: var(--bg-hover); color: var(--ink-1); }
.icon-btn-sm.danger:hover { background: rgba(239,68,68,.1); color: #ef4444; }

.pos-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; min-height: 200px; }
.pos-card {
  background: var(--bg-elevated); border: 1px solid var(--line);
  border-radius: 10px; padding: 14px 16px;
  display: flex; flex-direction: column; gap: 8px;
  transition: box-shadow 0.15s;
}
.pos-card:hover { box-shadow: var(--shadow-md); }
.pos-card header { display: flex; justify-content: space-between; align-items: center; }
.pos-code { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--ink-4);
  background: var(--bg-subtle); padding: 1px 7px; border-radius: 4px; }
.pos-level { font-size: 11px; font-weight: 700; color: #6366f1; }
.pos-card h4 { font-size: 14.5px; font-weight: 700; color: var(--ink-1); }
.pos-card p { font-size: 12px; color: var(--ink-3); line-height: 1.55; min-height: 36px; }
.pos-card footer { display: flex; gap: 4px; justify-content: flex-end; padding-top: 6px; border-top: 1px dashed var(--line); }
.icon-btn {
  width: 28px; height: 28px; border-radius: 6px;
  background: transparent; border: 1px solid transparent; color: var(--ink-3);
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
  transition: all 0.15s;
}
.icon-btn:hover { background: var(--bg-hover); border-color: var(--line); color: var(--ink-1); }
.icon-btn.danger:hover { background: rgba(239,68,68,.1); border-color: #ef4444; color: #ef4444; }

.empty {
  grid-column: 1 / -1; padding: 56px 20px; text-align: center;
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  color: var(--ink-3); font-size: 13px;
}
.empty-icon {
  width: 52px; height: 52px; border-radius: 14px; margin-bottom: 4px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--bg-subtle); color: var(--ink-4);
}
.empty p { font-size: 13px; color: var(--ink-2); font-weight: 600; }
.empty-hint { font-size: 12px; color: var(--ink-4); }
</style>

<style scoped>
@media (max-width: 1100px) {
  .org-main { grid-template-columns: minmax(0, 1fr); }
  .left-pane { min-height: 420px; }
}
</style>
