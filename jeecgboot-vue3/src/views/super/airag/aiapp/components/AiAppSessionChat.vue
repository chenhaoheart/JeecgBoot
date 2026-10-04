<template>
  <div class="ai-session-chat">
    <div class="leftArea" :class="[expand ? 'expand' : 'shrink']">
      <div class="content">
        <div class="header">
          <img v-if="icon" class="header-image" :src="icon" />
          <div class="header-name">{{ name }}</div>
        </div>
        <div class="new-session" @click="handleNewSession">
          <div class="left-box">
            <div class="app-icon">
              <Icon icon="ant-design:edit-outlined" size="14" />
            </div>
            <div class="app-name">新对话</div>
          </div>
        </div>
        <div class="session-scroll">
          <div class="section">
            <div class="section-title">历史对话</div>
            <div v-if="tasks.length" class="task-list">
              <div
                v-for="task in tasks"
                :key="task.id"
                class="task-item"
                :class="activeKey === task.id ? 'active' : ''"
                @click="handleTaskClick(task)"
              >
                <div class="task-title" :title="task.title" v-if="!task.isEdit">{{ task.title }}</div>
                <a-input
                  class="title"
                  v-if="task.isEdit"
                  :defaultValue="task.title"
                  placeholder="请输入标题"
                  @change="handleInputChange"
                  @keyup.enter="inputBlur(task)"
                />
                <div class="icon-edit">
                  <a-space>
                    <span class="icon edit" @click.prevent.stop="handleEdit(task)" v-if="!task.isEdit">
                      <Icon icon="ant-design:edit-outlined" size="14" />
                    </span>
                    <span class="icon del">
                      <a-popconfirm
                        :overlayStyle="{ 'z-index': 9999 }"
                        title="确定删除此记录？"
                        placement="bottom"
                        ok-text="确定"
                        cancel-text="取消"
                        @confirm="handleDel(task)"
                      >
                        <Icon icon="ant-design:delete-outlined" size="14" />
                      </a-popconfirm>
                    </span>
                  </a-space>
                </div>
              </div>
            </div>
            <a-empty v-else description="暂无历史对话" />
          </div>
        </div>
      </div>
      <div class="toggle-btn" @click="handleToggle">
        <span class="icon" :class="expand ? 'expanded' : ''">
          <Icon icon="ant-design:right-outlined" size="14" />
        </span>
      </div>
    </div>
    <div class="rightArea">
      <chat
        v-if="uuid"
        :key="uuid"
        url="/airag/chat/send"
        :uuid="uuid"
        :historyData="historyData"
        type="view"
        :formState="appData"
        :prologue="prologue"
        :conversationSettings="conversationSettings"
        :chatTitle="name"
        :sessionType="sessionType"
        @reload-message-title="reloadMessageTitle"
      />
      <div v-if="showWelcome" class="emptyArea">
        <div class="welcome-text">{{ welcomeText }}</div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  /**
   * 单应用会话页：左侧历史 + 右侧对话。
   * 图像识别等独立菜单只传应用和 sessionType，会话按 sessionType 隔离。
   */
  import { computed, onMounted, ref } from 'vue';
  import { Icon } from '/@/components/Icon';
  import chat from '/@/views/super/airag/aiapp/chat/chat.vue';
  import { defHttp } from '@/utils/http/axios';
  import { useUserStore } from '@/store/modules/user';

  const props = defineProps<{
    /** 绑定的 AI 应用 id */
    appId: string;
    /** 会话存储类型，不同页面不要共用，否则历史会混在一起 */
    sessionType: string;
    name: string;
    icon?: string;
    prologue?: string;
  }>();

  const appData = computed(() => ({
    id: props.appId,
    name: props.name,
    icon: props.icon,
    prologue: props.prologue,
  }));

  const conversationSettings = ref<Record<string, any>>({});

  const userStore = useUserStore();
  const welcomeName = computed(() => userStore.getUserInfo?.realname || userStore.getUserInfo?.username || '');
  const welcomeText = computed(() => {
    const line = props.prologue || '准备好开始了吗？';
    return welcomeName.value ? `你好，${welcomeName.value}。${line}` : line;
  });

  const expand = ref(true);
  const handleToggle = () => {
    expand.value = !expand.value;
  };

  const uuid = ref('');
  const historyData = ref<any>([]);
  const tasks = ref<any>([]);
  const activeKey = ref('');
  const showWelcome = ref(false);

  let inputValue = '';
  const handleInputChange = (e) => {
    inputValue = e.target.value.trim();
  };

  const getUuid = (len = 10, radix = 16) => {
    const chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'.split('');
    const id: string[] = [];
    const base = radix || chars.length;
    for (let i = 0; i < len; i++) {
      id[i] = chars[0 | (Math.random() * base)];
    }
    return id.join('');
  };

  function handleNewSession() {
    showWelcome.value = true;
    historyData.value = [];
    activeKey.value = '';
    uuid.value = getUuid(32);
  }

  function reloadMessageTitle(title) {
    showWelcome.value = false;
    addSession(title, uuid.value);
  }

  function addSession(title, id) {
    activeKey.value = id;
    if (tasks.value?.length > 0 && tasks.value.some((item) => item.id === id)) {
      return;
    }
    tasks.value.unshift({ id, title });
  }

  function handleTaskClick(task) {
    if (task.id === activeKey.value) {
      return;
    }
    activeKey.value = task.id;
    showWelcome.value = false;
    uuid.value = task.id;
    defHttp
      .get(
        {
          url: '/airag/chat/messages',
          params: { conversationId: task.id, sessionType: props.sessionType },
        },
        { isTransformResponse: false }
      )
      .then((res) => {
        if (res.success) {
          if (res.result && res.result.messages) {
            historyData.value = res.result.messages;
          } else if (Array.isArray(res.result)) {
            historyData.value = res.result;
          } else {
            historyData.value = [];
          }
        } else {
          historyData.value = [];
        }
      });
  }

  async function getSessionList() {
    const res = await defHttp.get(
      {
        url: '/airag/chat/getConversationsByType',
        params: { sessionType: props.sessionType },
      },
      { isTransformResponse: false }
    );
    tasks.value = res && res.success ? res.result || [] : [];
    return tasks.value;
  }

  function handleEdit(task) {
    task.isEdit = true;
    inputValue = task.title;
  }

  function inputBlur(task) {
    task.isEdit = false;
    if (!inputValue || inputValue === task.title) {
      return;
    }
    task.title = inputValue;
    defHttp.put(
      {
        url: '/airag/chat/conversation/update/title',
        params: { id: task.id, title: inputValue, sessionType: props.sessionType },
      },
      { joinParamsToUrl: true }
    );
  }

  function handleDel(task) {
    defHttp
      .delete(
        {
          url: '/airag/chat/conversation/' + task.id + '/' + props.sessionType,
        },
        { isTransformResponse: false }
      )
      .then(() => {
        if (activeKey.value === task.id) {
          handleNewSession();
        }
        getSessionList();
      });
  }

  onMounted(() => {
    getSessionList();
    handleNewSession();
  });
</script>

<style lang="less" scoped>
  @width: 280px;
  .ai-session-chat {
    height: 100%;
    width: 100%;
    position: absolute;
    background: white;
    display: flex;
    overflow: hidden;
    border: 1px solid #eeeeee;

    .leftArea {
      width: @width;
      transition: 0.3s left;
      position: relative;
      left: 0;
      height: 100%;
      flex-shrink: 0;

      .content {
        width: 100%;
        height: 100%;
        overflow: hidden;
        padding: 12px 10px;
        display: flex;
        flex-direction: column;
        border-right: 1px solid #eeeeee;
        box-sizing: border-box;
      }

      &.shrink {
        margin-left: -@width;

        .toggle-btn {
          .icon {
            transform: rotate(0deg);
          }
        }
      }

      .toggle-btn {
        cursor: pointer;
        width: 24px;
        height: 24px;
        position: absolute;
        top: 50%;
        right: -12px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 18px;
        color: rgb(51, 54, 57);
        border: 1px solid rgb(239, 239, 245);
        background-color: #fff;
        box-shadow: 0 2px 4px 0px #e7e9ef;
        transform: translateY(-50%);
        z-index: 1;

        .icon {
          transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
          display: flex;
          align-items: center;

          &.expanded {
            transform: rotate(180deg);
          }
        }
      }

      .header {
        display: flex;
        align-items: center;
        padding: 4px 6px 12px;

        .header-image {
          width: 30px;
          height: 30px;
          border-radius: 6px;
          margin-right: 8px;
        }

        .header-name {
          font-size: 15px;
          font-weight: 600;
          color: #333;
        }
      }

      .new-session {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 9px 12px;
        background: linear-gradient(90deg, #e3f0ff 0%, #eaf3ff 100%);
        border: 1px solid #d4e7ff;
        border-radius: 8px;
        cursor: pointer;
        margin-bottom: 12px;

        .left-box {
          display: flex;
          align-items: center;

          .app-icon {
            width: 22px;
            height: 22px;
            border-radius: 6px;
            background: #1677ff;
            color: #fff;
            display: flex;
            align-items: center;
            justify-content: center;
            margin-right: 8px;
          }

          .app-name {
            font-size: 13px;
            color: #333;
          }
        }
      }

      .session-scroll {
        flex: 1;
        overflow-y: auto;
        min-height: 0;

        .section {
          .section-title {
            font-size: 12px;
            color: #999;
            padding: 4px 6px 8px;
          }

          .task-list {
            display: flex;
            flex-direction: column;
            gap: 2px;
          }

          .task-item {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 8px 10px;
            border-radius: 6px;
            cursor: pointer;
            color: #333;
            font-size: 13px;

            &:hover {
              background: #f5f7fa;

              .icon-edit {
                opacity: 1;
              }
            }

            &.active {
              background: #e8f1ff;
              color: #1677ff;
            }

            .task-title {
              flex: 1;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }

            .icon-edit {
              opacity: 0;
              flex-shrink: 0;
              margin-left: 6px;

              .icon {
                color: #999;

                &:hover {
                  color: #1677ff;
                }
              }
            }
          }
        }
      }
    }

    .rightArea {
      flex: 1;
      position: relative;
      height: 100%;
      overflow: hidden;
      min-width: 0;

      .emptyArea {
        position: absolute;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        pointer-events: none;

        .welcome-text {
          font-size: 26px;
          font-weight: 600;
          color: #333;
        }
      }
    }
  }
</style>
