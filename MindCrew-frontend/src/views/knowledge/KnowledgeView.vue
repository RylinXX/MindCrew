<template>
  <div class="knowledge-page page-container">
    <!-- 顶部统计卡 -->
    <div class="stat-strip">
      <div class="stat-item" v-for="s in statCards" :key="s.label">
        <div class="stat-icon-wrap" :style="{ background: s.bg, border: `1px solid ${s.border}` }">
          <el-icon size="16" :color="s.color"><component :is="s.icon" /></el-icon>
        </div>
        <div>
          <div class="stat-val" :style="{ color: s.color }">{{ s.value }}</div>
          <div class="stat-lbl">{{ s.label }}</div>
        </div>
      </div>
    </div>

    <!-- 操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-select v-model="filterCategory" placeholder="全部分类" clearable size="default" style="width:160px" @change="loadList">
          <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
        <el-select v-model="filterStatus" placeholder="全部状态" clearable size="default" style="width:130px" @change="loadList">
          <el-option label="处理中" value="processing" />
          <el-option label="就绪"   value="ready" />
          <el-option label="失败"   value="failed" />
        </el-select>
        <el-button :icon="Refresh" @click="loadList" circle />
      </div>
      <el-button type="primary" @click="uploadDialogVisible = true">
        <el-icon><Upload /></el-icon>
        上传文档
      </el-button>
    </div>

    <!-- 知识库卡片网格 -->
    <div v-loading="loading" class="kb-grid">
      <div
        v-for="item in knowledgeList"
        :key="item.id"
        class="kb-card"
        @click="openDetail(item)"
      >
        <!-- 状态指示灯 -->
        <div class="kb-card-header">
          <div class="kb-file-icon">
            <el-icon size="20" :color="getFileColor(item.fileType)"><component :is="getFileIcon(item.fileType)" /></el-icon>
          </div>
          <div class="header-tags">
            <!-- 任务 7 · 可见性徽标 · 图标 + 文字（不用 emoji） -->
            <span
              class="vis-badge"
              :class="`vis-${(item as any).visibility || 'public'}`"
              :title="visibilityTip((item as any).visibility)"
            >
              <el-icon size="11"><component :is="visibilityIcon((item as any).visibility)" /></el-icon>
              <span>{{ visibilityLabel((item as any).visibility) }}</span>
            </span>
            <el-tag :type="getStatusType(item.status)" size="small" effect="light">
              {{ getStatusLabel(item.status) }}
            </el-tag>
          </div>
        </div>

        <div class="kb-card-body">
          <div class="kb-name" :title="item.name">{{ item.name }}</div>
          <div
            v-if="item.status === 'failed' && item.errorMsg"
            class="kb-error-inline"
          >
            <div class="kb-error-row" @click.stop="showErrorDetail(item)">
              <el-icon size="12"><WarningFilled /></el-icon>
              <span class="kb-error-text">{{ item.errorMsg }}</span>
              <span class="kb-error-more">查看 ›</span>
            </div>
            <div class="kb-error-actions" @click.stop>
              <el-tooltip content="重新处理" placement="top">
                <button class="kb-inline-btn" @click.stop="reprocess(item.id)">
                  <el-icon><Refresh /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip content="删除" placement="top">
                <button class="kb-inline-btn danger" @click.stop="deleteItem(item.id)">
                  <el-icon><Delete /></el-icon>
                </button>
              </el-tooltip>
            </div>
          </div>
          <div v-else class="kb-desc">{{ cardDesc(item) }}</div>
        </div>

        <div class="kb-card-footer">
          <div class="kb-meta">
            <span class="meta-tag" :style="{ borderColor: getCategoryColor(item.category) + '55', color: getCategoryColor(item.category) }">
              {{ getCategoryLabel(item.category) }}
              <el-icon v-if="(item as any).categoryUserSet === 1" size="10" class="lock-mark" title="用户手动锁定"><Lock /></el-icon>
              <el-icon v-else-if="item.category" size="10" class="ai-mark" title="AI 推荐"><MagicStick /></el-icon>
            </span>
            <span class="meta-chunks">
              <el-icon size="11"><Document /></el-icon>
              {{ item.chunkCount ?? '-' }} 切片
            </span>
          </div>
          <!-- AI 提取的 tags -->
          <div v-if="parseTags((item as any).tags).length" class="kb-tags-row">
            <span v-for="t in parseTags((item as any).tags).slice(0, 4)" :key="t" class="kb-tag-chip">#{{ t }}</span>
          </div>
          <div class="kb-time">{{ formatDate(item.createTime) }}</div>
        </div>

        <!-- 操作蒙层（失败状态不显示蒙层，按钮已 inline 显示） -->
        <div v-if="item.status !== 'failed'" class="kb-card-overlay" @click.stop="openDetail(item)">
          <el-tooltip content="详情 / 可见性 / ACL 授权">
            <button class="overlay-btn primary" @click.stop="openDetail(item)">
              <el-icon><Setting /></el-icon>
              <span class="overlay-btn-label">详情 / 配置</span>
            </button>
          </el-tooltip>
          <el-tooltip content="删除">
            <button class="overlay-btn danger" @click.stop="deleteItem(item.id)">
              <el-icon><Delete /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </div>

      <!-- 上传卡（始终显示） -->
      <div class="kb-card upload-placeholder" @click="uploadDialogVisible = true">
        <el-icon size="28" color="#334155"><Plus /></el-icon>
        <span>上传新文档</span>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="!loading && knowledgeList.length === 0" class="empty-state">
      <el-icon size="48" color="#1e293b"><FolderOpened /></el-icon>
      <p class="empty-title">知识库为空</p>
      <p class="empty-desc">上传文档后，MindCrew 将自动解析并建立向量索引</p>
      <el-button type="primary" @click="uploadDialogVisible = true">立即上传</el-button>
    </div>

    <!-- 分页 -->
    <div class="pagination" v-if="total > pageSize">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        background
        layout="prev, pager, next"
        @current-change="loadList"
      />
    </div>

    <!-- ===== 上传弹窗 ===== -->
    <el-dialog v-model="uploadDialogVisible" title="上传文档到知识库" width="480px" :close-on-click-modal="false">
      <el-form ref="uploadFormRef" :model="uploadForm" :rules="uploadRules" label-width="90px">
        <!-- 拖拽上传区 -->
        <el-form-item prop="file">
          <div
            class="drop-zone"
            :class="{ dragging: isDragging, 'has-file': !!uploadFile }"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="handleDrop"
            @click="triggerFileInput"
          >
            <input ref="fileInputRef" type="file" accept=".pdf,.docx,.doc,.pptx,.ppt,.xlsx,.xls,.csv,.wps,.html,.htm,.jpg,.jpeg,.png,.webp,.bmp,.gif,.mp3,.wav,.m4a,.aac,.flac,.opus,.ogg,.amr,.mp4,.mov,.mkv,.avi,.flv,.webm,.m4v,.txt,.md,.markdown" style="display:none" @change="handleFileChange" />
            <template v-if="!uploadFile">
              <el-icon size="32" color="#334155"><UploadFilled /></el-icon>
              <div class="drop-text">拖拽文件到此，或<span class="drop-link">点击选择</span></div>
              <div class="drop-hint">支持 PDF / Word / PowerPoint / Excel / WPS / HTML / 图片 / <strong>音频</strong> / <strong>视频</strong> / <strong>微信聊天</strong> / 文本</div>
            </template>
            <template v-else>
              <el-icon size="24" :color="getFileColor(getFileExt(uploadFile.name))"><component :is="getFileIcon(getFileExt(uploadFile.name))" /></el-icon>
              <div class="file-selected-name">{{ uploadFile.name }}</div>
              <div class="file-selected-size">{{ formatFileSize(uploadFile.size) }}</div>
              <button class="file-clear" @click.stop="clearFile">
                <el-icon size="14"><Close /></el-icon>
              </button>
            </template>
          </div>
        </el-form-item>

        <el-form-item label="文档分类">
          <el-select v-model="uploadForm.category" placeholder="留空 · 由 AI 自动判断" clearable style="width:100%">
            <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
          <div style="font-size:11px;color:#94a3b8;margin-top:4px">
            手动选择会"锁定"分类，AI 不会覆盖；留空则文档处理完成后 LLM 自动判类、提取标签、生成摘要。
          </div>
        </el-form-item>

        <el-form-item label="文档描述">
          <el-input v-model="uploadForm.description" type="textarea" :rows="3" placeholder="简要描述文档内容（可选）" />
        </el-form-item>

        <!-- 上传进度 -->
        <el-form-item v-if="uploading">
          <div class="upload-progress-wrap">
            <div class="progress-info">
              <span>{{ uploadProgress < 100 ? '上传中...' : '向量化处理中...' }}</span>
              <span class="progress-pct">{{ uploadProgress }}%</span>
            </div>
            <el-progress :percentage="uploadProgress" :striped="uploadProgress < 100" :striped-flow="uploadProgress < 100" color="#38bdf8" />
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="closeUpload">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">
          {{ uploading ? '处理中...' : '立即上传' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 详情弹窗 ===== -->
    <el-dialog v-model="detailVisible" title="文档详情" width="560px" class="kb-detail-dialog">
      <div v-if="currentDoc" class="detail-grid">
        <div class="detail-row span2">
          <span class="detail-key">文档名称</span>
          <span class="detail-val">{{ currentDoc.name }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">文件类型</span>
          <span class="detail-val">{{ currentDoc.fileType?.toUpperCase() || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">文件大小</span>
          <span class="detail-val">{{ formatFileSize(currentDoc.fileSize) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">分类</span>
          <span class="detail-val">
            <el-select
              :model-value="currentDoc.category"
              size="small"
              style="width:140px"
              @change="(v: string) => changeDocCategory(currentDoc!.id, v)"
            >
              <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
            <span v-if="(currentDoc as any).categoryUserSet === 1" class="cat-lock-tip">
              <el-icon size="11"><Lock /></el-icon> 已锁定
            </span>
            <span v-else class="cat-ai-tip">
              <el-icon size="11"><MagicStick /></el-icon> AI 推荐
            </span>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-key">切片数量</span>
          <span class="detail-val" style="color:#38bdf8;font-weight:700">{{ currentDoc.chunkCount ?? '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">状态</span>
          <span class="detail-val">
            <el-tag :type="getStatusType(currentDoc.status)" size="small">{{ getStatusLabel(currentDoc.status) }}</el-tag>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-key">上传时间</span>
          <span class="detail-val">{{ formatDate(currentDoc.createTime) }}</span>
        </div>
        <div class="detail-row span2">
          <span class="detail-key">描述</span>
          <span class="detail-val">{{ currentDoc.description || '暂无' }}</span>
        </div>

        <!-- 任务 7 · 可见性 + ACL 授权（卡片式选择） -->
        <div class="detail-row span2 vis-block">
          <span class="detail-key">
            <el-icon size="13"><Lock /></el-icon>
            <span>可见性</span>
          </span>
          <div class="vis-cards">
            <button
              v-for="v in visibilityOptions"
              :key="v.value"
              type="button"
              class="vis-card"
              :class="{ active: ((currentDoc as any).visibility || 'public') === v.value }"
              @click="changeVisibility(currentDoc!.id, v.value)"
            >
              <el-icon size="18" class="vis-icon"><component :is="v.icon" /></el-icon>
              <div class="vis-card-body">
                <div class="vis-card-title">{{ v.title }}</div>
                <div class="vis-card-desc">{{ v.desc }}</div>
              </div>
              <el-icon v-if="((currentDoc as any).visibility || 'public') === v.value" size="14" class="vis-check">
                <CircleCheckFilled />
              </el-icon>
            </button>
          </div>
        </div>

        <div v-if="((currentDoc as any).visibility || 'public') === 'scoped'" class="detail-row span2 acl-perms">
          <span class="detail-key">
            <el-icon size="13"><Tickets /></el-icon>
            <span>授权对象</span>
          </span>
          <span class="detail-val">
            <div v-if="aclLoading" style="font-size:12px;color:var(--ink-3)">加载中…</div>
            <template v-else>
              <!-- 已授权列表 · 按 subject 类型区分显示 -->
              <div v-for="(entry, idx) in aclEntries" :key="idx" class="acl-row">
                <span class="acl-subject-tag" :class="entry.departmentId ? 'dept' : 'pos'">
                  <el-icon size="11"><component :is="entry.departmentId ? 'OfficeBuilding' : 'User'" /></el-icon>
                  <span>{{ entry.departmentId ? '部门' : '职位' }}</span>
                </span>
                <span class="acl-pos">{{ aclSubjectLabel(entry) }}</span>
                <el-select v-model="entry.permission" size="small" style="width:90px"
                           @change="onAclEntryChange">
                  <el-option label="read"  value="read" />
                  <el-option label="write" value="write" />
                  <el-option label="admin" value="admin" />
                </el-select>
                <button class="acl-del" @click="removeAclEntryAt(idx)" title="移除">
                  <el-icon size="12"><Close /></el-icon>
                </button>
              </div>

              <!-- 添加授权 · Tab 切换职位/部门 -->
              <div class="acl-add-block">
                <el-radio-group v-model="addSubjectType" size="small" class="acl-tabs">
                  <el-radio-button label="position">
                    <el-icon size="11" style="vertical-align:-1px;margin-right:3px"><User /></el-icon>
                    按职位
                  </el-radio-button>
                  <el-radio-button label="department">
                    <el-icon size="11" style="vertical-align:-1px;margin-right:3px"><OfficeBuilding /></el-icon>
                    按部门
                  </el-radio-button>
                </el-radio-group>

                <div class="acl-add-row">
                  <el-select v-if="addSubjectType === 'position'"
                             v-model="newAclPositionId" placeholder="选择职位" size="small" style="width:220px" clearable filterable>
                    <el-option v-for="p in availableAclPositions" :key="p.id"
                               :label="`${p.name} (${p.code})`" :value="p.id" />
                  </el-select>
                  <el-select v-else
                             v-model="newAclDepartmentId" placeholder="选择部门" size="small" style="width:220px" clearable filterable>
                    <el-option v-for="d in availableAclDepartments" :key="d.id"
                               :label="d.name" :value="d.id" />
                  </el-select>
                  <el-select v-model="newAclPermission" size="small" style="width:90px">
                    <el-option label="read"  value="read" />
                    <el-option label="write" value="write" />
                    <el-option label="admin" value="admin" />
                  </el-select>
                  <el-button size="small" type="primary" @click="addAclEntry"
                             :disabled="addSubjectType === 'position' ? !newAclPositionId : !newAclDepartmentId">
                    添加授权
                  </el-button>
                </div>
              </div>
              <div class="acl-tip">
                <strong>职位级</strong>·精确到单个角色（如 Java 工程师）<br />
                <strong>部门级</strong>·覆盖该部门 + <strong>所有子部门</strong> 的全部用户<br />
                read 仅查问答 · write 可上传修改 · admin 可删除及再授权
              </div>
            </template>
          </span>
        </div>
        <div v-if="(currentDoc as any).summary" class="detail-row span2">
          <span class="detail-key">AI 摘要</span>
          <span class="detail-val" style="line-height:1.6">{{ (currentDoc as any).summary }}</span>
        </div>
        <div v-if="parseTags((currentDoc as any).tags).length" class="detail-row span2">
          <span class="detail-key">AI 标签</span>
          <span class="detail-val">
            <el-tag
              v-for="t in parseTags((currentDoc as any).tags)"
              :key="t"
              size="small"
              effect="plain"
              style="margin:2px 6px 2px 0"
            >#{{ t }}</el-tag>
          </span>
        </div>
        <div class="detail-row span2" v-if="currentDoc.status === 'ready'">
          <span class="detail-key">操作</span>
          <span class="detail-val" style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
            <el-button size="small" :icon="MagicStick" @click="reclassifyDoc(currentDoc.id)">
              重新分类
            </el-button>
            <el-button size="small" type="primary" :icon="Link" @click="openApiAccessDialog(currentDoc)">
              API 接入
            </el-button>
            <span style="font-size:11px;color:var(--ink-3)">让第三方系统通过 API key 接入这份知识库</span>
          </span>
        </div>
        <div v-if="currentDoc.errorMsg" class="detail-row span2 error-block">
          <span class="detail-key" style="color:#f87171">⚠ 失败原因</span>
          <span class="detail-val error-msg">{{ currentDoc.errorMsg }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button :icon="Edit" @click="openEditDoc">编辑信息</el-button>
        <el-button type="primary" :icon="Tickets" @click="openChunks">
          切片管理（{{ currentDoc?.chunkCount ?? 0 }}）
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 任务 11.6 · KB 专属 API 接入弹窗 ===== -->
    <el-dialog v-model="apiKeyDialogVisible" :title="`API 接入 · ${apiKeyTargetKb?.name || ''}`" width="780px" :close-on-click-modal="false" class="api-key-dialog">
      <div class="api-hint">
        每个知识库可以颁发<strong>独立的 API Key</strong>给第三方系统接入。Key 只能访问本 KB 的数据，
        按月配额计费，可随时吊销。<span class="api-hint-warn">⚠ 完整 key 仅在生成时显示一次，请妥善保管。</span>
      </div>

      <div class="api-keys-list" v-loading="apiKeysLoading">
        <article v-for="k in apiKeys" :key="k.id" class="api-key-card" :class="`status-${k.status}`">
          <header>
            <div class="ak-name">{{ k.name }}</div>
            <span class="ak-status" :class="`s-${k.status}`">{{ statusLabel(k.status) }}</span>
          </header>
          <div class="ak-prefix">
            <code>{{ k.keyPrefix }}······</code>
            <span class="ak-prefix-tip">完整 key 仅生成时可见 · 已隐藏</span>
          </div>
          <div class="ak-meta">
            <span>本月: <b>{{ k.monthUsed }}</b> / {{ k.monthlyQuota }}</span>
            <span>累计: {{ k.totalCalls }}</span>
            <span v-if="k.lastUsedAt">最近调用: {{ formatDate(k.lastUsedAt) }}</span>
          </div>
          <div class="ak-actions">
            <el-button size="small" :icon="DocumentCopy" @click="showCurlExample(k)">查看接入示例</el-button>
            <el-button size="small" type="danger" plain :icon="Close" :disabled="k.status !== 'active'" @click="revokeKey(k)">
              吊销
            </el-button>
          </div>
        </article>
        <div v-if="!apiKeysLoading && apiKeys.length === 0" class="empty">
          该知识库还没有 API Key · 点击下方按钮生成第一个
        </div>
      </div>

      <template #footer>
        <el-button @click="apiKeyDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Plus" @click="openIssueDialog">
          生成新 API Key
        </el-button>
      </template>
    </el-dialog>

    <!-- 任务 11.6 · 生成新 Key 弹窗 -->
    <el-dialog v-model="issueDialogVisible" :title="`为「${apiKeyTargetKb?.name}」生成 API Key`" width="560px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="issueForm.name" placeholder="如 客户 X 接入 · 内部 CRM 集成" maxlength="80" />
        </el-form-item>
        <el-form-item label="月调用上限">
          <el-input-number v-model="issueForm.monthlyQuota" :min="100" :max="1000000" :step="1000" style="width:180px" />
          <span style="margin-left:8px;color:var(--ink-3);font-size:12px">超出后该 key 当月停用</span>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="issueForm.expireAt" type="datetime" placeholder="留空 = 永不过期" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="issueForm.description" type="textarea" :rows="2" placeholder="给运维 / 自己看的备注 · 选填" maxlength="300" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="issuing" @click="confirmIssue">生成 Key</el-button>
      </template>
    </el-dialog>

    <!-- 任务 11.6 · 生成结果展示（完整 key 仅此一次） -->
    <el-dialog v-model="issuedResultVisible" title="⚠ 完整 API Key（仅显示一次）" width="640px" :close-on-click-modal="false" :show-close="false">
      <div class="issued-warn">
        <el-icon :size="18"><WarningFilled /></el-icon>
        <span>请立即复制保存。关闭后此完整 key 永久不可见，DB 仅存 SHA-256 hash。</span>
      </div>
      <div class="issued-key">
        <code>{{ issuedRawKey }}</code>
        <el-button size="small" type="primary" :icon="DocumentCopy" @click="copyRawKey">复制</el-button>
      </div>
      <div class="issued-curl">
        <div class="curl-title">接入示例（curl）：</div>
        <pre>{{ buildCurlExample(issuedRawKey, apiKeyTargetKb?.id) }}</pre>
      </div>
      <template #footer>
        <el-button type="primary" @click="closeIssuedResult">我已复制保存</el-button>
      </template>
    </el-dialog>

    <!-- 接入示例查看（旧 key） -->
    <el-dialog v-model="curlExampleVisible" title="接入示例" width="640px">
      <div class="issued-warn" style="background:rgba(245,158,11,0.08);border-color:rgba(245,158,11,0.3);color:#b45309">
        <el-icon :size="14"><InfoFilled /></el-icon>
        <span>完整 key 已隐藏。示例中的 <code>YOUR_API_KEY</code> 请替换为你保存的完整 key。</span>
      </div>
      <div class="issued-curl">
        <div class="curl-title">/v3/chat · 问答</div>
        <pre>{{ buildCurlExample('YOUR_API_KEY', apiKeyTargetKb?.id) }}</pre>
        <div class="curl-title" style="margin-top:14px">/v3/search · 纯检索</div>
        <pre>{{ buildSearchExample('YOUR_API_KEY', apiKeyTargetKb?.id) }}</pre>
      </div>
    </el-dialog>

    <!-- ===== 编辑文档信息 ===== -->
    <el-dialog v-model="editDocVisible" title="编辑文档信息" width="520px" :close-on-click-modal="false">
      <el-form label-width="72px">
        <el-form-item label="名称" required>
          <el-input v-model="editDocForm.name" maxlength="200" placeholder="文档名称" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="editDocForm.category" style="width:100%" clearable placeholder="留空不修改">
            <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editDocForm.description" type="textarea" :rows="3" maxlength="500" placeholder="文档描述（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDocVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingDoc" @click="saveDocEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 切片管理 ===== -->
    <el-dialog
      v-model="chunkDialogVisible"
      :title="`切片管理 · ${chunkDoc?.name || ''}`"
      width="860px"
      top="6vh"
      destroy-on-close
      class="chunk-dialog"
    >
      <div class="chunk-toolbar">
        <el-input
          v-model="chunkKeyword"
          placeholder="搜索本知识库的切片内容"
          clearable
          :prefix-icon="Search"
          style="width:300px"
          @keyup.enter="searchChunks"
          @clear="loadChunks"
        />
        <el-button :icon="Search" @click="searchChunks">搜索</el-button>
        <span class="chunk-total">共 {{ chunkDoc?.chunkCount ?? chunkTotal }} 条切片</span>
      </div>
      <div class="chunk-list" v-loading="chunkLoading">
        <article v-for="c in chunks" :key="c.id" class="chunk-item">
          <header>
            <span class="chunk-index">{{ chunkMetaText(c) }}</span>
            <div class="chunk-actions">
              <el-button size="small" text :icon="Edit" @click="openChunkEdit(c)">编辑</el-button>
              <el-button size="small" text type="danger" :icon="Delete" @click="deleteChunk(c)">删除</el-button>
            </div>
          </header>
          <p class="chunk-text">{{ c.content }}</p>
        </article>
        <div v-if="!chunkLoading && chunks.length === 0" class="empty">
          {{ chunkSearchMode ? '没有匹配的切片' : '该文档还没有切片' }}
        </div>
      </div>
      <div class="chunk-pager" v-if="!chunkSearchMode && chunkTotal > chunkPageSize">
        <el-pagination
          v-model:current-page="chunkPage"
          :page-size="chunkPageSize"
          :total="chunkTotal"
          layout="prev, pager, next"
          @current-change="loadChunks"
        />
      </div>
      <template #footer>
        <span class="chunk-foot-tip">编辑后关键词/BM25 检索即时生效；如启用向量检索，请重新处理该文档以同步向量。</span>
        <el-button @click="chunkDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ===== 编辑切片 ===== -->
    <el-dialog v-model="chunkEditVisible" title="编辑切片内容" width="760px" :close-on-click-modal="false">
      <el-input v-model="chunkEditForm.content" type="textarea" :rows="12" resize="vertical" placeholder="切片内容" />
      <div class="chunk-edit-tip">保存后 MySQL 全文索引会自动更新，关键词检索立即生效；向量库中的旧向量不会自动更新。</div>
      <template #footer>
        <el-button @click="chunkEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingChunk" @click="saveChunk">保存切片</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Refresh, Upload, Delete, Plus, Document, FolderOpened,
  UploadFilled, Close, MagicStick, WarningFilled, Setting,
  Link, DocumentCopy, InfoFilled,
  View, Lock, Hide, Tickets, User, OfficeBuilding, CircleCheckFilled,
  Search, Edit
} from '@element-plus/icons-vue'
import { knowledgeApi, chunkApi, type KnowledgeBase, type KbChunk } from '@/api/knowledge'
import { kbCategoryApi } from '@/api/kbCategory'
import { positionApi, departmentApi, kbAclApi, type Position, type Department } from '@/api/orgAcl'
import { apiKeyApi, type ApiKey } from '@/api/apiKey'

// ── 状态 ──
const loading     = ref(false)
const knowledgeList = ref<KnowledgeBase[]>([])
const total       = ref(0)
const kbStats     = ref({ total: 0, ready: 0, processing: 0, failed: 0, chunks: 0 })
const currentPage = ref(1)
const pageSize    = ref(20)
const filterCategory = ref('')
const filterStatus   = ref('')

const uploadDialogVisible = ref(false)
const detailVisible       = ref(false)
const uploading    = ref(false)
const uploadProgress = ref(0)
const uploadFile   = ref<File | null>(null)
const isDragging   = ref(false)
const fileInputRef = ref<HTMLInputElement>()
const uploadFormRef = ref<FormInstance>()
const currentDoc   = ref<KnowledgeBase | null>(null)

// 编辑文档信息
const editDocVisible = ref(false)
const savingDoc = ref(false)
const editDocForm = reactive({ name: '', category: '', description: '' })

// 切片管理
const chunkDialogVisible = ref(false)
const chunkDoc = ref<KnowledgeBase | null>(null)
const chunks = ref<KbChunk[]>([])
const chunkLoading = ref(false)
const chunkPage = ref(1)
const chunkPageSize = ref(10)
const chunkTotal = ref(0)
const chunkKeyword = ref('')
const chunkSearchMode = ref(false)
const chunkEditVisible = ref(false)
const savingChunk = ref(false)
const chunkEditForm = reactive({ id: 0, content: '' })

const uploadForm = ref({ category: '', description: '' })
const uploadRules: FormRules = {}   // category 改为可空，留空时由 LLM 自动分类

// 分类选项 · 动态从后端加载，失败时降级到内置兜底
const categoryOptions = ref<{ label: string; value: string; color?: string }[]>([
  { label: '技术', value: 'tech' },
  { label: '产品', value: 'product' },
  { label: '法务', value: 'legal' },
  { label: '财务', value: 'finance' },
  { label: '培训', value: 'training' },
  { label: '人事', value: 'hr' },
  { label: '客户', value: 'customer' },
  { label: '其他', value: 'other' },
])

// 卡片上不展示“原文件：xxx”这类回溯信息（详情弹窗里仍可看），避免长哈希挤满卡片
const cardDesc = (item: any) => {
  const d = (item.description || '').trim()
  if (!d || d.startsWith('原文件')) return '暂无描述'
  return d
}

const statCards = computed(() => {
  // 汇总统计来自 /knowledge/stats（全量），不能用当前页数据推算
  const total_cnt = kbStats.value.total || total.value || 0
  const ready_cnt = kbStats.value.ready
  const proc_cnt  = kbStats.value.processing
  const chunks    = kbStats.value.chunks
  return [
    { label: '知识库总数', value: total_cnt, icon: 'FolderOpened', color: '#38bdf8', bg: 'rgba(56,189,248,0.1)', border: 'rgba(56,189,248,0.2)' },
    { label: '已就绪',     value: ready_cnt, icon: 'CircleCheck',  color: '#34d399', bg: 'rgba(52,211,153,0.1)', border: 'rgba(52,211,153,0.2)' },
    { label: '处理中',     value: proc_cnt,  icon: 'Loading',      color: '#fbbf24', bg: 'rgba(251,191,36,0.1)', border: 'rgba(251,191,36,0.2)' },
    { label: '总切片数',   value: chunks,    icon: 'Document',     color: '#818cf8', bg: 'rgba(129,140,248,0.1)', border: 'rgba(129,140,248,0.2)' },
  ]
})

const loadList = async () => {
  loading.value = true
  try {
    const res = await knowledgeApi.list({
      current:  currentPage.value,
      size:     pageSize.value,
      category: filterCategory.value || undefined,
      status:   filterStatus.value || undefined,
    })
    knowledgeList.value = res.records || res || []
    total.value = res.total ?? knowledgeList.value.length
    try {
      kbStats.value = await knowledgeApi.stats({ category: filterCategory.value || undefined })
    } catch {
      // 统计失败不影响列表
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 加载分类字典（失败用兜底）
  try {
    const res: any = await kbCategoryApi.list()
    const arr = res?.data ?? res ?? []
    if (Array.isArray(arr) && arr.length > 0) {
      categoryOptions.value = arr.map((c: any) => ({
        label: c.name,
        value: c.code,
        color: c.color,
      }))
    }
  } catch { /* keep fallback */ }
  loadList()
})

const openDetail = (item: KnowledgeBase) => {
  currentDoc.value = item
  detailVisible.value = true
  // 任务 7 · 详情打开时加载 ACL
  loadAclForCurrentDoc()
}

// ─────────────────────────────────────────────
// 文档信息编辑
// ─────────────────────────────────────────────
function openEditDoc() {
  if (!currentDoc.value) return
  editDocForm.name = currentDoc.value.name || ''
  editDocForm.category = currentDoc.value.category || ''
  editDocForm.description = currentDoc.value.description || ''
  editDocVisible.value = true
}

async function saveDocEdit() {
  if (!currentDoc.value) return
  if (!editDocForm.name.trim()) {
    ElMessage.warning('文档名称不能为空')
    return
  }
  savingDoc.value = true
  try {
    await knowledgeApi.update(currentDoc.value.id, {
      name: editDocForm.name.trim(),
      category: editDocForm.category || undefined,
      description: editDocForm.description,
    })
    ElMessage.success('文档信息已保存')
    editDocVisible.value = false
    const id = currentDoc.value.id
    await loadList()
    const fresh = knowledgeList.value.find(i => i.id === id)
    if (fresh) currentDoc.value = fresh
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    savingDoc.value = false
  }
}

// ─────────────────────────────────────────────
// 切片查看 / 编辑 / 删除
// ─────────────────────────────────────────────
function openChunks() {
  if (!currentDoc.value) return
  chunkDoc.value = currentDoc.value
  chunkPage.value = 1
  chunkKeyword.value = ''
  chunkSearchMode.value = false
  chunkDialogVisible.value = true
  loadChunks()
}

async function loadChunks() {
  if (!chunkDoc.value) return
  chunkLoading.value = true
  chunkSearchMode.value = false
  try {
    const res: any = await chunkApi.list({
      kbId: chunkDoc.value.id,
      page: chunkPage.value,
      size: chunkPageSize.value,
    })
    chunks.value = res?.records ?? res ?? []
    chunkTotal.value = res?.total ?? chunks.value.length
  } catch (e: any) {
    ElMessage.error(e?.message || '加载切片失败')
  } finally {
    chunkLoading.value = false
  }
}

async function searchChunks() {
  if (!chunkDoc.value) return
  const kw = chunkKeyword.value.trim()
  if (!kw) {
    loadChunks()
    return
  }
  chunkLoading.value = true
  chunkSearchMode.value = true
  try {
    const res: any = await chunkApi.search(chunkDoc.value.id, kw)
    chunks.value = res ?? []
    chunkTotal.value = chunks.value.length
  } catch (e: any) {
    ElMessage.error(e?.message || '搜索切片失败')
  } finally {
    chunkLoading.value = false
  }
}

function chunkMetaText(c: KbChunk): string {
  const parts: string[] = []
  if (c.chunkIndex !== null && c.chunkIndex !== undefined) parts.push(`#${c.chunkIndex + 1}`)
  const meta = (c.metadata || '').trim()
  if (meta.startsWith('{')) {
    try {
      const m = JSON.parse(meta)
      if (m.chapter) parts.push(String(m.chapter))
      if (m.pageNumber) parts.push(`P${m.pageNumber}`)
      if (m.contentType && m.contentType !== 'general') parts.push(String(m.contentType))
    } catch { /* ignore */ }
  } else if (meta) {
    parts.push(meta.split(',').slice(0, 2).join(' · '))
  }
  return parts.join(' · ') || '切片'
}

function openChunkEdit(c: KbChunk) {
  chunkEditForm.id = c.id
  chunkEditForm.content = c.content || ''
  chunkEditVisible.value = true
}

async function saveChunk() {
  if (!chunkEditForm.content.trim()) {
    ElMessage.warning('切片内容不能为空')
    return
  }
  savingChunk.value = true
  try {
    await chunkApi.update(chunkEditForm.id, { content: chunkEditForm.content.trim() })
    ElMessage.success('切片已保存，关键词检索即时生效')
    chunkEditVisible.value = false
    if (chunkSearchMode.value) await searchChunks()
    else await loadChunks()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存切片失败')
  } finally {
    savingChunk.value = false
  }
}

async function deleteChunk(c: KbChunk) {
  try {
    await ElMessageBox.confirm('确认删除该切片？删除后不可恢复，关键词检索会立即失效。', '删除切片', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await chunkApi.remove(c.id)
    ElMessage.success('切片已删除')
    if (chunkDoc.value) {
      chunkDoc.value.chunkCount = Math.max(0, (chunkDoc.value.chunkCount ?? 1) - 1)
      if (currentDoc.value && currentDoc.value.id === chunkDoc.value.id) {
        currentDoc.value.chunkCount = chunkDoc.value.chunkCount
      }
    }
    if (chunkSearchMode.value) await searchChunks()
    else await loadChunks()
    await loadList()
  } catch (e: any) {
    ElMessage.error(e?.message || '删除切片失败')
  }
}

// ─────────────────────────────────────────────
// 任务 7 · 可见性 + ACL 配置（支持 职位级 + 部门级）
// ─────────────────────────────────────────────
interface AclEntry {
  positionId?: number | null
  departmentId?: number | null
  permission: string
}

const aclLoading = ref(false)
const aclEntries = ref<AclEntry[]>([])
const allPositions = ref<Position[]>([])
const allDepartments = ref<Department[]>([])
const newAclPositionId   = ref<number | null>(null)
const newAclDepartmentId = ref<number | null>(null)
const newAclPermission   = ref('read')
const addSubjectType     = ref<'position' | 'department'>('position')

const positionLabel = (id: number) => {
  const p = allPositions.value.find(x => x.id === id)
  return p ? `${p.name} (${p.code})` : `#${id}`
}
const departmentLabel = (id: number) => {
  const d = allDepartments.value.find(x => x.id === id)
  return d ? d.name : `#${id}`
}
const aclSubjectLabel = (e: AclEntry) =>
  e.departmentId ? departmentLabel(e.departmentId) : positionLabel(e.positionId as number)

const availableAclPositions = computed(() => {
  const granted = new Set(aclEntries.value.filter(e => e.positionId).map(e => e.positionId))
  return allPositions.value.filter(p => !granted.has(p.id))
})
const availableAclDepartments = computed(() => {
  const granted = new Set(aclEntries.value.filter(e => e.departmentId).map(e => e.departmentId))
  return allDepartments.value.filter(d => !granted.has(d.id))
})

const loadAclForCurrentDoc = async () => {
  if (!currentDoc.value) return
  // 先确保 职位 + 部门 字典都已加载
  if (allPositions.value.length === 0 || allDepartments.value.length === 0) {
    try {
      const [pRes, dRes]: any = await Promise.all([positionApi.list(), departmentApi.list()])
      allPositions.value   = pRes?.data ?? pRes ?? []
      allDepartments.value = dRes?.data ?? dRes ?? []
    } catch { /* 字典加载失败不阻塞 */ }
  }
  if (((currentDoc.value as any).visibility || 'public') !== 'scoped') {
    aclEntries.value = []
    return
  }
  aclLoading.value = true
  try {
    const res: any = await kbAclApi.list(currentDoc.value.id)
    const list = res?.data ?? res ?? []
    aclEntries.value = list.map((a: any) => ({
      positionId:   a.positionId   ?? null,
      departmentId: a.departmentId ?? null,
      permission:   a.permission,
    }))
  } catch (e: any) {
    ElMessage.error('加载 ACL 失败：' + (e?.message || ''))
    aclEntries.value = []
  } finally {
    aclLoading.value = false
  }
}

const changeVisibility = async (id: number, v: string) => {
  try {
    await knowledgeApi.updateVisibility(id, v)
    if (currentDoc.value) (currentDoc.value as any).visibility = v
    ElMessage.success(`可见性已切换为 ${v}`)
    if (v === 'scoped') await loadAclForCurrentDoc()
    loadList()
  } catch (e: any) {
    ElMessage.error('切换失败：' + (e?.message || ''))
  }
}

const persistAcl = async () => {
  if (!currentDoc.value) return
  try {
    // 转成后端期望格式 (AclEntry[])
    const payload = aclEntries.value.map(e => ({
      positionId:   e.positionId   ?? null,
      departmentId: e.departmentId ?? null,
      permission:   e.permission as 'read' | 'write' | 'admin',
    }))
    await kbAclApi.replace(currentDoc.value.id, payload)
  } catch (e: any) {
    ElMessage.error('保存授权失败：' + (e?.message || ''))
  }
}

const addAclEntry = async () => {
  if (addSubjectType.value === 'position') {
    if (!newAclPositionId.value) return
    aclEntries.value.push({
      positionId:   newAclPositionId.value,
      departmentId: null,
      permission:   newAclPermission.value,
    })
    newAclPositionId.value = null
  } else {
    if (!newAclDepartmentId.value) return
    aclEntries.value.push({
      positionId:   null,
      departmentId: newAclDepartmentId.value,
      permission:   newAclPermission.value,
    })
    newAclDepartmentId.value = null
  }
  newAclPermission.value = 'read'
  await persistAcl()
  ElMessage.success('已添加授权')
}

const removeAclEntryAt = async (idx: number) => {
  aclEntries.value.splice(idx, 1)
  await persistAcl()
  ElMessage.success('已撤销授权')
}

const onAclEntryChange = persistAcl

// 任务 7 · 可见性徽标 · 用 Element Plus 图标代替 emoji
const visibilityOptions = [
  { value: 'public',  title: '公开',    desc: '所有登录用户可读',     icon: 'View' },
  { value: 'scoped',  title: '按职位',  desc: '通过 ACL 控制访问',    icon: 'Lock' },
  { value: 'private', title: '私有',    desc: '仅创建者可见',         icon: 'Hide' },
]
const visibilityLabel = (v?: string) => {
  const x = v || 'public'
  return ({ public: '公开', scoped: '按职位', private: '私有' } as any)[x] || x
}
const visibilityTip = (v?: string) => {
  const x = v || 'public'
  return ({
    public: '所有登录用户可读',
    scoped: '按职位 ACL 控制访问',
    private: '仅创建者可见',
  } as any)[x] || ''
}
const visibilityIcon = (v?: string) => {
  const x = v || 'public'
  return ({ public: 'View', scoped: 'Lock', private: 'Hide' } as any)[x] || 'View'
}

// ─────────────────────────────────────────────
// 任务 11.6 · KB 专属 API Key 管理
// ─────────────────────────────────────────────
const apiKeyDialogVisible = ref(false)
const apiKeyTargetKb = ref<KnowledgeBase | null>(null)
const apiKeysLoading = ref(false)
const apiKeys = ref<ApiKey[]>([])

const openApiAccessDialog = async (kb: KnowledgeBase) => {
  apiKeyTargetKb.value = kb
  apiKeyDialogVisible.value = true
  await loadApiKeysForKb(kb.id)
}

const loadApiKeysForKb = async (kbId: number) => {
  apiKeysLoading.value = true
  try {
    const res: any = await apiKeyApi.byKb(kbId)
    apiKeys.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('加载 API Keys 失败：' + (e?.message || ''))
    apiKeys.value = []
  } finally {
    apiKeysLoading.value = false
  }
}

const statusLabel = (s: string) =>
  ({ active: '✓ 启用中', revoked: '✕ 已吊销', expired: '⏰ 已过期' } as any)[s] || s

const revokeKey = async (k: ApiKey) => {
  try {
    await ElMessageBox.confirm(`确认吊销 API Key「${k.name}」？该 key 立即失效，无法恢复。`, '警告', { type: 'warning' })
    await apiKeyApi.revoke(k.id)
    ElMessage.success('已吊销')
    await loadApiKeysForKb(apiKeyTargetKb.value!.id)
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('吊销失败：' + (e?.message || ''))
  }
}

// 生成 key 弹窗
const issueDialogVisible = ref(false)
const issuing = ref(false)
const issueForm = reactive<{
  name: string; monthlyQuota: number; expireAt: string | null; description: string
}>({ name: '', monthlyQuota: 10000, expireAt: null, description: '' })

const openIssueDialog = () => {
  issueForm.name = ''
  issueForm.monthlyQuota = 10000
  issueForm.expireAt = null
  issueForm.description = ''
  issueDialogVisible.value = true
}

const issuedResultVisible = ref(false)
const issuedRawKey = ref('')

const confirmIssue = async () => {
  if (!issueForm.name.trim()) {
    ElMessage.warning('请填名称')
    return
  }
  if (!apiKeyTargetKb.value) return
  issuing.value = true
  try {
    const res: any = await apiKeyApi.issue({
      name: issueForm.name.trim(),
      allowedKbIds: [apiKeyTargetKb.value.id],   // 11.6 · 单 KB 绑定
      monthlyQuota: issueForm.monthlyQuota,
      expireAt: issueForm.expireAt || undefined,
      description: issueForm.description || undefined,
    })
    const data = res?.data ?? res
    issuedRawKey.value = data.rawKey
    issueDialogVisible.value = false
    issuedResultVisible.value = true
    await loadApiKeysForKb(apiKeyTargetKb.value.id)
  } catch (e: any) {
    ElMessage.error('生成失败：' + (e?.message || ''))
  } finally {
    issuing.value = false
  }
}

const copyRawKey = async () => {
  try {
    await navigator.clipboard.writeText(issuedRawKey.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

const closeIssuedResult = async () => {
  try {
    await ElMessageBox.confirm('确认已经复制并妥善保存完整 key？关闭后无法再查看完整 key。', '再次确认', {
      type: 'warning',
      confirmButtonText: '已保存，关闭',
      cancelButtonText: '让我再看看',
    })
    issuedResultVisible.value = false
    issuedRawKey.value = ''
  } catch { /* cancel */ }
}

// 查看接入示例（旧 key · 不显完整 key）
const curlExampleVisible = ref(false)
const showCurlExample = (_k: ApiKey) => {
  curlExampleVisible.value = true
}

// 生成 curl 示例代码
const buildCurlExample = (key: string, kbId?: number) => {
  const host = window.location.origin
  return `curl -X POST ${host}/api/v3/chat \\
  -H "Authorization: Bearer ${key}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "question": "试用期工资多少？",
    "kbId": ${kbId ?? '<KB_ID>'}
  }'`
}

const buildSearchExample = (key: string, kbId?: number) => {
  const host = window.location.origin
  return `curl -X POST ${host}/api/v3/search \\
  -H "Authorization: Bearer ${key}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "query": "试用期",
    "kbId": ${kbId ?? '<KB_ID>'},
    "topK": 5
  }'`
}

// 点失败原因 → 直接弹完整文本（绕过 overlay 蒙层，无需 hover）
const showErrorDetail = (item: KnowledgeBase) => {
  ElMessageBox.alert(item.errorMsg || '未知错误', `❌ ${item.name} · 处理失败`, {
    type: 'error',
    confirmButtonText: '我知道了',
    customClass: 'error-detail-box',
    dangerouslyUseHTMLString: false,
  })
}

const deleteItem = async (id: number) => {
  await ElMessageBox.confirm('确认删除该知识库？此操作不可恢复。', '警告', { type: 'warning' })
  await knowledgeApi.delete(id)
  ElMessage.success('已删除')
  loadList()
}

const reprocess = async (id: number) => {
  await knowledgeApi.reprocess(id)
  ElMessage.success('已重新提交处理')
  loadList()
}

// ── 文件上传 ──
const triggerFileInput = () => fileInputRef.value?.click()
const handleFileChange = (e: Event) => {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (f) uploadFile.value = f
}
const handleDrop = (e: DragEvent) => {
  isDragging.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) uploadFile.value = f
}
const clearFile = () => {
  uploadFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
}
const closeUpload = () => {
  uploadDialogVisible.value = false
  clearFile()
  uploadForm.value = { category: '', description: '' }
  uploadProgress.value = 0
}

const submitUpload = async () => {
  const valid = await uploadFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!uploadFile.value) { ElMessage.warning('请选择文件'); return }

  uploading.value = true
  uploadProgress.value = 0
  try {
    await knowledgeApi.upload(
      uploadFile.value,
      uploadForm.value.category,
      uploadForm.value.description,
      (pct) => { uploadProgress.value = pct }
    )
    ElMessage.success('上传成功！文档正在向量化处理中...')
    closeUpload()
    loadList()
  } catch (e: any) {
    // 优先取后端 message / msg / data.message，最后兜底 e.message
    const reason =
      e?.response?.data?.message ||
      e?.response?.data?.msg ||
      e?.response?.data ||
      e?.message ||
      '未知错误'
    const text = typeof reason === 'string' ? reason : JSON.stringify(reason)
    ElMessageBox.alert(text, '上传失败', { type: 'error', confirmButtonText: '我知道了' })
  } finally { uploading.value = false }
}

// ── 工具函数 ──
const getStatusType = (s: string) => {
  const m: Record<string, string> = { ready: 'success', processing: 'warning', uploading: 'info', failed: 'danger' }
  return (m[s] || 'info') as any
}
const getStatusLabel = (s: string) => {
  const m: Record<string, string> = { ready: '就绪', processing: '处理中', uploading: '上传中', failed: '失败' }
  return m[s] || s
}
const getCategoryLabel = (v: string) => categoryOptions.value.find(c => c.value === v)?.label || v || '-'
const getCategoryColor = (v: string) => categoryOptions.value.find(c => c.value === v)?.color || '#64748B'

// JSON tags 解析
const parseTags = (raw: any): string[] => {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const arr = typeof raw === 'string' ? JSON.parse(raw) : raw
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

// 修改文档分类（锁定为用户手动）
const changeDocCategory = async (kbId: number, code: string) => {
  try {
    await kbCategoryApi.setDocumentCategory(kbId, code)
    ElMessage.success('分类已更新（已锁定，AI 不会再覆盖）')
    if (currentDoc.value) {
      currentDoc.value.category = code
      ;(currentDoc.value as any).categoryUserSet = 1
    }
    loadList()
  } catch (e: any) {
    ElMessage.error('修改失败：' + (e?.message || ''))
  }
}

// 重新分类（仅管理员）
const reclassifyDoc = async (kbId: number) => {
  try {
    await kbCategoryApi.reclassify(kbId)
    ElMessage.success('已重新提交分类，请稍后刷新查看')
    detailVisible.value = false
    setTimeout(loadList, 1500)
  } catch (e: any) {
    ElMessage.error('重分类失败：' + (e?.message || ''))
  }
}
const getFileExt = (name: string) => name.split('.').pop()?.toLowerCase() || ''
const getFileIcon = (ext: string) => {
  if (['pdf'].includes(ext))        return 'Document'
  if (['docx','doc'].includes(ext)) return 'Edit'
  if (['md','markdown'].includes(ext)) return 'Memo'
  return 'Document'
}
const getFileColor = (ext: string) => {
  if (['pdf'].includes(ext))        return '#f87171'
  if (['docx','doc'].includes(ext)) return '#38bdf8'
  if (['md','markdown'].includes(ext)) return '#818cf8'
  return '#94a3b8'
}
const formatFileSize = (bytes: number) => {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024*1024) return `${(bytes/1024).toFixed(1)} KB`
  return `${(bytes/1024/1024).toFixed(1)} MB`
}
const formatDate = (d: string) => {
  if (!d) return '-'
  return new Date(d).toLocaleDateString('zh-CN', { year:'numeric', month:'short', day:'numeric' })
}
</script>

<style scoped>
.knowledge-page { display: flex; flex-direction: column; gap: 16px; overflow-y: auto; }

/* 统计条 */
.stat-strip {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.stat-item {
  flex: 1;
  min-width: 140px;
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 14px 16px;
}
.stat-icon-wrap {
  width: 36px; height: 36px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-val { font-size: 22px; font-weight: 800; font-family: 'JetBrains Mono', monospace; line-height: 1; }
.stat-lbl { font-size: 11px; color: #64748b; margin-top: 3px; }

/* 操作栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 16px;
}
.toolbar-left { display: flex; align-items: center; gap: 10px; }

/* 卡片网格 */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.kb-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
  cursor: pointer;
  transition: var(--transition);
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}
.kb-card:hover {
  border-color: rgba(56,189,248,0.3);
  box-shadow: 0 4px 20px rgba(0,0,0,0.3);
  transform: translateY(-1px);
}
.kb-card:hover .kb-card-overlay { opacity: 1; }

.kb-card-header { display: flex; align-items: center; justify-content: space-between; }
.header-tags { display: flex; align-items: center; gap: 6px; }

/* 任务 7 · 可见性徽标 */
.vis-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  font-family: 'JetBrains Mono', monospace;
  cursor: help;
  letter-spacing: 0.02em;
}
.vis-badge.vis-public  { background: rgba(52, 211, 153, 0.12); color: #047857; border: 1px solid rgba(52, 211, 153, 0.3); }
.vis-badge.vis-scoped  { background: rgba(124, 58, 237, 0.12); color: #5b21b6; border: 1px solid rgba(124, 58, 237, 0.3); }
.vis-badge.vis-private { background: rgba(245, 158, 11, 0.12); color: #b45309; border: 1px solid rgba(245, 158, 11, 0.3); }

/* 任务 7 · 详情对话框 ACL 配置 */
.acl-block { background: rgba(124, 58, 237, 0.04); border: 1px solid rgba(124, 58, 237, 0.18) !important; }
.acl-perms { background: rgba(124, 58, 237, 0.06); border: 1px solid rgba(124, 58, 237, 0.22) !important; }
.acl-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 0;
}
.acl-row + .acl-row { border-top: 1px dashed rgba(124, 58, 237, 0.15); }
.acl-pos { flex: 1; font-size: 12.5px; color: var(--ink-1, #0f172a); font-weight: 600; }
.acl-del {
  width: 22px; height: 22px;
  border-radius: 4px;
  background: transparent; border: 1px solid transparent;
  color: var(--ink-3, #94a3b8); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.acl-del:hover { background: rgba(239, 68, 68, 0.1); border-color: #ef4444; color: #ef4444; }
.acl-add-row {
  display: flex; align-items: center; gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed rgba(124, 58, 237, 0.2);
}
.acl-tip { margin-top: 8px; font-size: 11px; color: var(--ink-3, #94a3b8); line-height: 1.55; }

/* 任务 7 补强 · 部门/职位 subject 类型徽标 */
.acl-subject-tag {
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 4px;
  flex-shrink: 0;
  letter-spacing: 0.02em;
}
.acl-subject-tag.pos {
  background: rgba(56, 189, 248, 0.12);
  color: #0284c7;
  border: 1px solid rgba(56, 189, 248, 0.3);
}
.acl-subject-tag.dept {
  background: rgba(124, 58, 237, 0.12);
  color: #5b21b6;
  border: 1px solid rgba(124, 58, 237, 0.3);
}

/* 添加授权区 · Tab + form */
.acl-add-block {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed rgba(124, 58, 237, 0.2);
}
.acl-tabs { margin-bottom: 8px; }
.kb-file-icon {
  width: 36px; height: 36px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
}

.kb-card-body { flex: 1; }
.kb-name {
  font-size: 14px; font-weight: 600; color: var(--ink-1);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  margin-bottom: 4px;
}
.kb-desc { font-size: 12px; color: #64748b; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

.kb-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.kb-meta { display: flex; align-items: center; gap: 10px; }
.meta-tag {
  font-size: 11px; color: #64748b;
  background: var(--bg-elevated);
  border-radius: 10px;
  padding: 2px 8px;
  border: 1px solid var(--border);
}
.meta-chunks { display: flex; align-items: center; gap: 4px; font-size: 11px; color: #475569; }
.kb-time { font-size: 11px; color: #334155; }
.lock-mark, .ai-mark { margin-left: 4px; font-size: 10px; }
.kb-tags-row {
  display: flex; flex-wrap: wrap; gap: 4px;
  margin-top: 6px;
}
.kb-tag-chip {
  font-size: 10.5px;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(56, 189, 248, 0.08);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.18);
  font-family: 'JetBrains Mono', monospace;
}
.kb-error-inline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
  padding: 8px 10px;
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.22);
  border-radius: 8px;
}
.kb-error-row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  cursor: pointer;
  transition: opacity 0.15s;
}
.kb-error-row:hover { opacity: 0.85; }
.kb-error-row .el-icon { color: #f87171; margin-top: 1px; flex-shrink: 0; }
.kb-error-text {
  flex: 1;
  font-size: 11.5px;
  color: #fca5a5;
  line-height: 1.4;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  word-break: break-all;
}
.kb-error-more {
  font-size: 10.5px;
  color: #f87171;
  font-weight: 600;
  flex-shrink: 0;
  white-space: nowrap;
}
.kb-error-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
  padding-top: 4px;
  border-top: 1px dashed rgba(248, 113, 113, 0.2);
}
.kb-inline-btn {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  border: 1px solid rgba(248, 113, 113, 0.3);
  background: rgba(248, 113, 113, 0.05);
  color: #fca5a5;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.kb-inline-btn:hover { background: rgba(248, 113, 113, 0.18); color: #fff; }
.kb-inline-btn.danger:hover { background: #ef4444; border-color: #ef4444; }

.error-block {
  background: rgba(248, 113, 113, 0.06);
  border: 1px solid rgba(248, 113, 113, 0.18);
  border-radius: 8px;
  padding: 10px 12px;
}
.error-msg {
  color: #f87171;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.55;
  word-break: break-all;
  white-space: pre-wrap;
}

.kb-card-overlay {
  position: absolute;
  inset: 0;
  background: rgba(13,17,23,0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  opacity: 0;
  transition: opacity 0.2s;
  border-radius: var(--radius);
  cursor: pointer;          /* 整个 overlay 区也可点（→ openDetail）*/
}
.overlay-btn {
  height: 38px;
  min-width: 38px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  display: inline-flex; align-items: center; justify-content: center;
  gap: 6px;
  cursor: pointer; color: var(--ink-2);
  font-size: 12.5px; font-weight: 600;
  transition: var(--transition);
}
.overlay-btn:hover { border-color: var(--primary); color: var(--primary); background: rgba(56, 189, 248, 0.06); }
.overlay-btn.primary {
  background: rgba(56, 189, 248, 0.12);
  border-color: rgba(56, 189, 248, 0.4);
  color: #38bdf8;
}
.overlay-btn.primary:hover {
  background: rgba(56, 189, 248, 0.2);
  border-color: #38bdf8;
}
.overlay-btn.danger {
  width: 38px;       /* 删除按钮保持小方形，只显图标 */
  padding: 0;
}
.overlay-btn.danger:hover { border-color: #f87171; color: #f87171; background: rgba(248,113,113,0.1); }
.overlay-btn-label { white-space: nowrap; }

/* 上传占位卡 */
.upload-placeholder {
  border-style: dashed;
  background: transparent;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #334155;
  font-size: 13px;
}
.upload-placeholder:hover { border-color: rgba(56,189,248,0.4); color: var(--primary); }

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px;
  text-align: center;
}
.empty-title { font-size: 18px; font-weight: 600; color: #475569; }
.empty-desc { font-size: 13px; color: #334155; max-width: 300px; line-height: 1.6; }

.pagination { display: flex; justify-content: center; padding: 8px 0; }

/* 详情弹窗：内容可滚动，保证底部「编辑信息 / 切片管理」按钮始终可见（Dialog 会 teleport 到 body） */
:global(.kb-detail-dialog .el-dialog__body) {
  max-height: calc(100vh - 300px);
  overflow-y: auto;
  padding-right: 16px;
}
:global(.kb-detail-dialog .el-dialog__footer) {
  border-top: 1px solid var(--line-soft);
  padding-top: 12px;
}

/* ── 切片管理弹窗 ── */
.chunk-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.chunk-total { margin-left: auto; font-size: 12px; color: var(--ink-3); }
.chunk-list {
  max-height: 56vh; overflow-y: auto; padding-right: 4px;
  display: flex; flex-direction: column; gap: 8px;
}
.chunk-item {
  border: 1px solid var(--line); border-radius: 10px;
  background: var(--bg-elevated); padding: 10px 12px;
  transition: border-color 150ms ease, box-shadow 150ms ease;
}
.chunk-item:hover { border-color: var(--line-strong); box-shadow: var(--shadow-card); }
.chunk-item header { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 6px; }
.chunk-index {
  font-size: 11px; font-weight: 600; color: var(--ink-3);
  background: var(--bg-subtle); border-radius: 5px; padding: 1px 7px;
  max-width: 70%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.chunk-actions { display: flex; gap: 2px; opacity: .55; transition: opacity 150ms ease; }
.chunk-item:hover .chunk-actions { opacity: 1; }
.chunk-text {
  margin: 0; font-size: 13px; line-height: 1.7; color: var(--ink-2);
  white-space: pre-wrap; word-break: break-word;
}
.chunk-pager { display: flex; justify-content: center; margin-top: 12px; }
.chunk-foot-tip { font-size: 11px; color: var(--ink-4); margin-right: auto; line-height: 1.5; }
.chunk-edit-tip { font-size: 11px; color: var(--ink-3); margin-top: 8px; line-height: 1.6; }

/* 上传弹窗 */
.drop-zone {
  width: 100%;
  min-height: 130px;
  border: 2px dashed var(--border);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  transition: var(--transition);
  position: relative;
  background: var(--bg-elevated);
}
.drop-zone:hover, .drop-zone.dragging { border-color: var(--primary); background: rgba(56,189,248,0.05); }
.drop-zone.has-file { border-style: solid; border-color: rgba(52,211,153,0.4); background: rgba(52,211,153,0.04); }
.drop-text { font-size: 13px; color: #64748b; }
.drop-link { color: var(--primary); cursor: pointer; }
.drop-hint { font-size: 11px; color: #475569; }
.file-selected-name { font-size: 13px; color: var(--ink-1); font-weight: 500; }
.file-selected-size { font-size: 11px; color: #64748b; }
.file-clear {
  position: absolute; top: 8px; right: 8px;
  background: var(--bg-elevated); border: 1px solid var(--border);
  border-radius: 50%; width: 22px; height: 22px;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; color: #64748b;
  transition: var(--transition);
}
.file-clear:hover { color: #f87171; border-color: #f87171; }

.upload-progress-wrap {
  width: 100%;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px;
}
.progress-info { display: flex; justify-content: space-between; font-size: 12px; color: #64748b; margin-bottom: 8px; }
.progress-pct { color: var(--primary); font-weight: 700; }

/* 详情弹窗 */
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.detail-row { display: flex; flex-direction: column; gap: 4px; padding: 10px 12px; background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius-sm); }
.detail-row.span2 { grid-column: span 2; }
.detail-key {
  font-size: 11px;
  color: var(--ink-3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.detail-key .el-icon { color: var(--ink-4); }

/* 分类锁定 / AI 推荐 提示 */
.cat-lock-tip, .cat-ai-tip {
  margin-left: 8px;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.cat-lock-tip { color: var(--ink-3); }
.cat-ai-tip   { color: var(--brand); }

/* 可见性卡片选择器 */
.vis-block .detail-val { display: block; }
.vis-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 4px;
}
.vis-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}
.vis-card:hover {
  border-color: var(--line-strong);
  background: var(--bg-hover);
}
.vis-card.active {
  border-color: var(--brand);
  background: var(--brand-soft);
  box-shadow: 0 0 0 3px var(--brand-glow);
}
.vis-card .vis-icon { color: var(--ink-3); flex-shrink: 0; margin-top: 1px; }
.vis-card.active .vis-icon { color: var(--brand); }
.vis-card-body { flex: 1; min-width: 0; }
.vis-card-title { font-size: 13px; font-weight: 700; color: var(--ink-1); line-height: 1.2; }
.vis-card-desc { font-size: 11.5px; color: var(--ink-3); margin-top: 2px; line-height: 1.4; }
.vis-check {
  position: absolute;
  top: 6px; right: 8px;
  color: var(--brand);
}
.detail-val { font-size: 13.5px; color: var(--ink-1); }

/* ═══════════════════════════════════════════
 * 任务 11.6 · KB 专属 API Key 弹窗
 * ═══════════════════════════════════════════ */
.api-hint {
  padding: 12px 14px;
  margin-bottom: 16px;
  background: rgba(56, 189, 248, 0.08);
  border: 1px solid rgba(56, 189, 248, 0.25);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--ink-2);
  line-height: 1.6;
}
.api-hint-warn { color: #f59e0b; font-weight: 600; }
.api-keys-list { display: flex; flex-direction: column; gap: 10px; max-height: 360px; overflow-y: auto; }
.api-key-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px 16px;
}
.api-key-card.status-revoked,
.api-key-card.status-expired { opacity: 0.6; }
.api-key-card header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.ak-name { font-size: 14px; font-weight: 700; color: var(--ink-1); }
.ak-status { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; }
.ak-status.s-active  { background: rgba(52,211,153,0.15); color: #047857; }
.ak-status.s-revoked { background: rgba(248,113,113,0.15); color: #b91c1c; }
.ak-status.s-expired { background: rgba(148,163,184,0.15); color: #64748b; }
.ak-prefix { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.ak-prefix code {
  font-family: 'JetBrains Mono', monospace;
  background: var(--bg-elevated); padding: 4px 10px;
  border-radius: 6px; font-size: 12.5px;
  color: var(--ink-1);
}
.ak-prefix-tip { font-size: 11px; color: var(--ink-4); }
.ak-meta { display: flex; gap: 16px; font-size: 12px; color: var(--ink-3); margin-bottom: 10px; }
.ak-meta b { color: var(--ink-1); }
.ak-actions { display: flex; gap: 8px; }
.api-keys-list .empty { padding: 40px 20px; text-align: center; color: var(--ink-3); }

.issued-warn {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px;
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.3);
  border-radius: 8px;
  color: #b91c1c;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 14px;
}
.issued-key {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px;
  background: #1f2937;
  border-radius: 8px;
  margin-bottom: 14px;
}
.issued-key code {
  flex: 1;
  font-family: 'JetBrains Mono', monospace;
  color: #34d399;
  font-size: 13px;
  word-break: break-all;
}
.issued-curl { margin-top: 4px; }
.curl-title { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 6px; }
.issued-curl pre {
  background: #0f172a;
  color: #cbd5e1;
  padding: 12px 14px;
  border-radius: 8px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
}
.issued-curl pre code { color: #fbbf24; }
</style>
