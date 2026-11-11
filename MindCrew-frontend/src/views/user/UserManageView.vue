<template>
  <div class="user-manage-page">
    <!-- 搜索栏 -->
    <el-card class="search-card">
      <el-row :gutter="16" align="middle">
        <el-col :span="8">
          <el-input
            v-model="keyword"
            placeholder="搜索用户名、昵称、手机号"
            :prefix-icon="Search"
            clearable
            @change="loadUsers"
          />
        </el-col>
        <el-col :span="4">
          <el-select v-model="roleFilter" placeholder="全部角色" clearable @change="loadUsers">
            <el-option label="管理员" value="admin" />
            <el-option label="普通用户" value="user" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-button type="primary" :icon="Refresh" @click="loadUsers">刷新</el-button>
        </el-col>
        <el-col :span="8" style="text-align:right">
          <el-statistic title="用户总数" :value="total" />
        </el-col>
      </el-row>
    </el-card>

    <!-- 用户列表 -->
    <el-card class="table-card">
      <el-table
        :data="users"
        v-loading="loading"
        stripe
        row-key="id"
        style="width: 100%"
      >
        <el-table-column label="用户" min-width="180">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="36" :src="row.avatar">
                {{ row.nickname?.charAt(0) }}
              </el-avatar>
              <div class="user-cell-info">
                <div class="cell-name">{{ row.nickname || row.username }}</div>
                <div class="cell-username">@{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="phone" label="手机号" width="140">
          <template #default="{ row }">
            {{ row.phone || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="getRoleType(row.role)">{{ getRoleLabel(row.role) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              @change="(val: boolean) => toggleStatus(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column prop="lastLogin" label="最后登录" width="160">
          <template #default="{ row }">
            {{ row.lastLogin ? formatDate(row.lastLogin) : '未登录' }}
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="注册时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>

        <!-- 任务 7 · 部门 / 职位 列 -->
        <el-table-column label="部门 / 职位" width="200">
          <template #default="{ row }">
            <div class="org-col">
              <div class="org-line">
                <el-icon size="11" color="#7C3AED"><OfficeBuilding /></el-icon>
                <span>{{ deptNameOf(row.departmentId) }}</span>
              </div>
              <div class="org-line">
                <el-icon size="11" color="#0EA5E9"><UserFilled /></el-icon>
                <span>{{ positionNameOf(row.positionId) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <!-- 任务 13.6 · 本月用量列 -->
        <el-table-column label="本月用量" width="170">
          <template #default="{ row }">
            <button class="usage-cell" @click="$router.push(`/user-usage/${row.id}`)">
              <span class="cost">¥{{ formatMoney(usageMap[row.id]?.costCny) }}</span>
              <span class="chats">{{ usageMap[row.id]?.chatCount ?? 0 }} 对话</span>
              <el-icon size="11" color="#94a3b8"><ArrowRight /></el-icon>
            </button>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" text @click="openRoleDialog(row)">调整角色</el-button>
            <el-button size="small" type="warning" text @click="openOrgDialog(row)">分配职位</el-button>
            <el-button size="small" type="info" text @click="$router.push(`/user-usage/${row.id}`)">用量详情</el-button>
            <el-button
              size="small"
              type="danger"
              text
              @click="disableUser(row)"
              v-if="row.status === 1 && row.role !== 'admin'"
            >禁用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="current"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="loadUsers"
        />
      </div>
    </el-card>

    <!-- 调整角色弹窗 -->
    <el-dialog v-model="roleDialogVisible" title="调整用户角色" width="400px">
      <el-form :model="roleForm" label-width="80px">
        <el-form-item label="用户">
          <span>{{ roleForm.username }}</span>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="roleForm.role" style="width:100%">
            <el-option label="管理员" value="admin" />
            <el-option label="普通用户" value="user" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 任务 7 · 分配部门/职位弹窗 -->
    <el-dialog v-model="orgDialogVisible" title="分配部门 / 职位" width="480px">
      <div class="org-hint">
        部门 + 职位决定该用户能访问哪些"职位独立"的知识库。系统角色（管理员/普通用户）独立管理。
      </div>
      <el-form :model="orgForm" label-width="80px">
        <el-form-item label="用户">
          <span>{{ orgForm.username }}</span>
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="orgForm.departmentId" placeholder="留空 = 未分配" clearable style="width:100%">
            <el-option v-for="d in allDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="职位">
          <el-select v-model="orgForm.positionId" placeholder="留空 = 仅能看 public KB" clearable style="width:100%">
            <el-option v-for="p in allPositions" :key="p.id" :label="`${p.name} (${p.code})`" :value="p.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orgDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveOrg">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, OfficeBuilding, UserFilled, ArrowRight } from '@element-plus/icons-vue'
import { userApi, type UserInfo } from '@/api/user'
import { departmentApi, positionApi, type Department, type Position } from '@/api/orgAcl'
import { usageApi } from '@/api/usage'

const loading = ref(false)
const users = ref<UserInfo[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const roleFilter = ref('')

const roleDialogVisible = ref(false)
const roleForm = reactive({ id: 0, username: '', role: '' })

// 任务 7 · 部门 / 职位
const allDepts = ref<Department[]>([])
const allPositions = ref<Position[]>([])
const orgDialogVisible = ref(false)
const orgForm = reactive<{ id: number; username: string; departmentId: number | null; positionId: number | null }>(
  { id: 0, username: '', departmentId: null, positionId: null }
)

const deptNameOf = (id?: number | null) => {
  if (!id) return '—'
  return allDepts.value.find(d => d.id === id)?.name || '—'
}
const positionNameOf = (id?: number | null) => {
  if (!id) return '—'
  return allPositions.value.find(p => p.id === id)?.name || '—'
}

const loadOrgData = async () => {
  try {
    const [dRes, pRes]: any = await Promise.all([departmentApi.list(), positionApi.list()])
    allDepts.value = dRes?.data ?? dRes ?? []
    allPositions.value = pRes?.data ?? pRes ?? []
  } catch (e: any) {
    ElMessage.warning('部门/职位字典加载失败：' + (e?.message || ''))
  }
}

const openOrgDialog = (row: UserInfo) => {
  orgForm.id = row.id
  orgForm.username = row.username
  orgForm.departmentId = row.departmentId ?? null
  orgForm.positionId = row.positionId ?? null
  orgDialogVisible.value = true
}

const saveOrg = async () => {
  try {
    await userApi.updateUserOrg(orgForm.id, orgForm.departmentId, orgForm.positionId)
    ElMessage.success('已保存 · 该用户的知识库访问范围已重新计算')
    orgDialogVisible.value = false
    await loadUsers()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  }
}

const loadUsers = async () => {
  loading.value = true
  try {
    const result = await userApi.listUsers({
      current: current.value,
      size: pageSize.value,
      keyword: keyword.value || undefined
    })
    users.value = result.records
    total.value = result.total
    // 任务 13.6 · 拉每个用户的本月用量（并发）
    loadUsageBatch(users.value.map(u => u.id))
  } finally {
    loading.value = false
  }
}

// 任务 13.6 · 用量缓存（按 userId 索引）
const usageMap = ref<Record<number, { chatCount: number; costCny: number }>>({})

const loadUsageBatch = async (userIds: number[]) => {
  // 并发拉，失败不阻塞列表
  const now = new Date()
  const firstDay = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10)
  const today    = now.toISOString().slice(0, 10)
  for (const id of userIds) {
    usageApi.userSummary(id, firstDay, today)
      .then((res: any) => {
        const d = res?.data ?? res
        usageMap.value[id] = {
          chatCount: d?.chatCount ?? 0,
          costCny:   Number(d?.costCny ?? 0),
        }
      })
      .catch(() => { /* 字典缺/无数据时静默 · 显示 0 */ })
  }
}

const formatMoney = (n?: number) => (n == null ? '0.0000' : Number(n).toFixed(4))

onMounted(async () => {
  await loadOrgData()
  await loadUsers()
})

const getRoleType = (role: string) => {
  return role === 'admin' ? 'danger' : ''
}

const getRoleLabel = (role: string) => {
  const map: Record<string, string> = { admin: '管理员', user: '用户' }
  return map[role] || role
}

const formatDate = (dateStr: string) => {
  return new Date(dateStr).toLocaleDateString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

const toggleStatus = async (row: UserInfo, val: boolean) => {
  try {
    await userApi.updateUserStatus(row.id, val ? 1 : 0)
    row.status = val ? 1 : 0
    ElMessage.success(`已${val ? '启用' : '禁用'}用户 ${row.username}`)
  } catch {}
}

const disableUser = async (row: UserInfo) => {
  await ElMessageBox.confirm(`确认禁用用户 ${row.username}？`, '警告', { type: 'warning' })
  await toggleStatus(row, false)
}

const openRoleDialog = (row: UserInfo) => {
  roleForm.id = row.id
  roleForm.username = row.username
  roleForm.role = row.role
  roleDialogVisible.value = true
}

const saveRole = async () => {
  try {
    await userApi.updateUserRole(roleForm.id, roleForm.role)
    ElMessage.success('角色更新成功')
    roleDialogVisible.value = false
    loadUsers()
  } catch {}
}
</script>

<style scoped>
.user-manage-page { display: flex; flex-direction: column; gap: 16px; height: 100%; overflow-y: auto; padding: 24px; }
.search-card, .table-card { border-radius: 12px; }

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cell-name { font-weight: 600; font-size: 14px; color: var(--ink-1); }
.cell-username { font-size: 12px; color: #64748b; }

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* 任务 7 · 部门/职位列 */
.org-col { display: flex; flex-direction: column; gap: 4px; }
.org-line { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ink-2, #475569); }
/* 任务 13.6 · 本月用量列 */
.usage-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--bg-elevated);
  border: 1px solid var(--line);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
  font-size: 12px;
}
.usage-cell:hover { background: var(--brand-soft); border-color: var(--brand-soft-2); }
.usage-cell .cost { color: #b45309; font-weight: 700; font-family: 'JetBrains Mono', monospace; }
.usage-cell .chats { color: var(--ink-3); font-size: 11px; }

.org-hint {
  padding: 10px 12px;
  margin-bottom: 14px;
  background: rgba(124, 58, 237, 0.08);
  border: 1px solid rgba(124, 58, 237, 0.2);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--ink-2, #475569);
  line-height: 1.55;
}
</style>
