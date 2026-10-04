<template>
  <div class="session-side">
    <div class="side-tabs">
      <a-tooltip title="新建会话">
        <a-button size="small" type="text" :disabled="loading" @click="createNewConversation" class="new-session-btn">
          <PlusOutlined />
        </a-button>
      </a-tooltip>
      <a-tooltip title="从服务端重新同步会话列表（库里删除的会话会立即消失）">
        <a-button size="small" type="text" :loading="syncing" @click="onRefresh" class="new-session-btn">
          <ReloadOutlined />
        </a-button>
      </a-tooltip>
      <div class="side-tab" :class="{ active: view === 'list' }" @click="view = 'list'">
        会话<span v-if="listCount" class="tab-count">{{ listCount }}</span>
      </div>
      <div class="side-tab" :class="{ active: view === 'archived' }" @click="view = 'archived'">
        已归档<span v-if="archivedCount" class="tab-count">{{ archivedCount }}</span>
      </div>
    </div>
    <div class="side-list">
      <template v-if="view === 'list'">
        <div
          v-for="s in listSessions"
          :key="s.conversationId"
          class="session-item"
          :class="{ active: s.conversationId === conversationId }"
          @click="switchSession(s)"
        >
          <div class="s-body">
            <div class="s-title" :title="s.title || '新会话'">
              <span v-if="s.generating" class="s-dot"></span>
              {{ s.title || '新会话' }}
            </div>
            <div class="s-meta">
              <span>{{ formatTime(s.updateTime) }}</span>
              <span v-if="s.lymc" class="s-ws" :title="s.lymc">· {{ s.lymc }}</span>
            </div>
          </div>
          <a-tooltip title="归档（数据保留，可随时恢复）">
            <FolderAddOutlined class="s-op s-arc" @click.stop="archiveSession(s)" />
          </a-tooltip>
        </div>
        <div v-if="!listSessions.length" class="side-empty">暂无会话</div>
      </template>
      <template v-else>
        <div
          v-for="s in archivedSessions"
          :key="s.conversationId"
          class="session-item archived"
          :class="{ active: s.conversationId === conversationId }"
          @click="switchSession(s)"
        >
          <div class="s-body">
            <div class="s-title" :title="s.title || '新会话'">
              <FolderOutlined class="s-arc-flag" />
              {{ s.title || '新会话' }}
            </div>
            <div class="s-meta">
              <span>{{ formatTime(s.updateTime) }}</span>
              <span v-if="s.lymc" class="s-ws" :title="s.lymc">· {{ s.lymc }}</span>
            </div>
          </div>
          <a-tooltip title="取消归档（恢复到会话列表）">
            <UndoOutlined class="s-op s-rest" @click.stop="unarchiveSession(s)" />
          </a-tooltip>
          <a-tooltip title="彻底删除">
            <DeleteOutlined class="s-op s-del" @click.stop="onDelete(s)" />
          </a-tooltip>
        </div>
        <div v-if="!archivedSessions.length" class="side-empty">暂无归档会话</div>
      </template>
    </div>
    <div v-if="view === 'list' && listSessions.length" class="side-foot">
      <span>共 {{ listSessions.length }} 个会话</span>
      <a-tooltip title="清除本机缓存并按服务端重新恢复会话列表">
        <span class="foot-op" @click="onClearCache"><ClearOutlined />清除本机缓存</span>
      </a-tooltip>
    </div>
    <div v-else-if="view === 'archived' && archivedSessions.length" class="side-foot">已归档 {{ archivedSessions.length }} 个会话，点击可回看</div>
  </div>
</template>

<script lang="ts" setup>
  import { computed, ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { ClearOutlined, DeleteOutlined, FolderAddOutlined, FolderOutlined, PlusOutlined, ReloadOutlined, UndoOutlined } from '@ant-design/icons-vue';
  import { ISession, useReportSession } from '../useReportSession';

  defineOptions({ name: 'ShReportSessionSide' });

  const {
    sessions,
    conversationId,
    loading,
    formatTime,
    switchSession,
    createNewConversation,
    deleteSession,
    archiveSession,
    unarchiveSession,
    refreshSessions,
    clearLocalCache,
  } = useReportSession();

  /** 主列表 / 归档列表两个视图 */
  const view = ref<'list' | 'archived'>('list');

  const listSessions = computed(() => sessions.value.filter((s) => !s.archived));
  const archivedSessions = computed(() => sessions.value.filter((s) => s.archived));
  const listCount = computed(() => listSessions.value.length);
  const archivedCount = computed(() => archivedSessions.value.length);

  const syncing = ref(false);

  /** 强制以服务端为准重新对账：库里手工删掉的会话在这里会真正消失 */
  async function onRefresh() {
    if (syncing.value) {
      return;
    }
    syncing.value = true;
    try {
      await refreshSessions();
    } finally {
      syncing.value = false;
    }
  }

  /** 清除本机缓存（内置浏览器常驻时 localStorage 不会自动清理，是不同步的常见来源） */
  function onClearCache() {
    Modal.confirm({
      title: '清除本机会话缓存？',
      content: '将删除本浏览器保存的会话列表与删除标记，并按服务端数据重新恢复。服务端已保存的会话不会丢失。',
      okText: '清除并重新同步',
      cancelText: '取消',
      onOk: () => clearLocalCache(),
    });
  }

  /** 删除前强确认：生成的报告与对话记录不可恢复 */
  function onDelete(s: ISession) {
    Modal.confirm({
      title: '彻底删除会话？',
      content: `「${s.title || '新会话'}」的对话记录与生成的报告数据将全部清除，删除后无法恢复。确定要删除吗？`,
      okText: '删除并清除数据',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => deleteSession(s),
    });
  }
</script>

<style lang="less" scoped>
  .session-side {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    background: #fbfcfe;

    .side-tabs {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 6px 8px 0;
      border-bottom: 1px solid #eef1f6;

      .new-session-btn {
        flex: 0 0 auto;
        padding: 2px 6px;
        color: @primary-color;
        display: inline-flex;
        align-items: center;
        justify-content: center;

        :deep(.anticon) {
          font-size: 15px;
        }

        &:hover {
          color: @primary-color;
        }
      }

      .side-tab {
        flex: 1;
        text-align: center;
        font-size: 13px;
        color: #98a2b3;
        padding: 5px 0;
        border-radius: 6px 6px 0 0;
        cursor: pointer;
        border-bottom: 2px solid transparent;
        transition: color 0.2s, border-color 0.2s;

        &:hover {
          color: rgba(0, 0, 0, 0.85);
        }

        &.active {
          color: @primary-color;
          font-weight: 500;
          border-bottom-color: @primary-color;
        }

        .tab-count {
          margin-left: 4px;
          font-size: 10px;
          color: #b8c0cc;
        }
      }
    }

    .side-list {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: 8px;

      .session-item {
        position: relative;
        display: flex;
        align-items: center;
        padding: 8px 10px;
        border-radius: 9px;
        cursor: pointer;
        margin-bottom: 3px;
        border: 1px solid transparent;
        transition: background 0.2s, border-color 0.2s;

        &:hover {
          background: #f2f5f9;

          .s-op {
            display: block;
          }
        }

        &.active {
          background: fade(@primary-color, 7%);
          border-color: fade(@primary-color, 18%);

          .s-title {
            color: @primary-color;
            font-weight: 500;
          }

          .s-meta {
            color: fade(@primary-color, 60%);
          }
        }

        &.archived {
          opacity: 0.85;
        }

        .s-body {
          flex: 1;
          min-width: 0;
        }

        .s-title {
          font-size: 13px;
          color: rgba(0, 0, 0, 0.85);
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          display: flex;
          align-items: center;
          gap: 5px;

          .s-arc-flag {
            flex: 0 0 auto;
            font-size: 12px;
            color: #98a2b3;
          }

          .s-dot {
            flex: 0 0 6px;
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background: #faad14;
            animation: side-blink 1s infinite;
          }
        }

        .s-meta {
          font-size: 11px;
          color: #b8c0cc;
          margin-top: 2px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }

        .s-op {
          display: none;
          position: absolute;
          top: 50%;
          transform: translateY(-50%);
          font-size: 14px;
          padding: 4px;
          border-radius: 6px;
          color: #98a2b3;
          line-height: 0;
          /* 悬停时以浮层小按钮盖在标题尾部上，不常驻占位挤压标题 */
          background: rgba(255, 255, 255, 0.92);
          box-shadow: 0 0 0 1px #e8eef6;

          &:hover {
            background: #e8eef6;
            color: @primary-color;
          }
        }

        .s-arc,
        .s-rest {
          right: 4px;
        }

        .s-del {
          right: 28px;

          &:hover {
            background: #ffece8;
            color: #ff4d4f;
          }
        }
      }

      .side-empty {
        text-align: center;
        color: #b8c0cc;
        font-size: 12px;
        padding: 24px 0;
      }
    }

    .side-foot {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 8px 14px;
      border-top: 1px solid #eef1f6;
      color: #b8c0cc;
      font-size: 11px;

      .foot-op {
        display: inline-flex;
        align-items: center;
        gap: 3px;
        cursor: pointer;
        white-space: nowrap;

        &:hover {
          color: @primary-color;
        }
      }
    }
  }

  @keyframes side-blink {
    50% {
      opacity: 0.3;
    }
  }
</style>
