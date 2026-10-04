<template>
  <component :is="shell" v-bind="shellBind">
    <div class="log-page" :class="{ 'is-embedded': embedded }">
      <div class="log-head">
        <div>
          <div class="log-title">AI隐患分析</div>
          <div class="log-sub">记录每次在一张图隐患点上发起的 AI 风险识别，结果不回写调查数据。系统提示词在「AI提示词」里维护，名称为「山洪隐患点风险识别」，保存后下一次识别即生效。</div>
        </div>
        <div class="log-filters">
          <a-select v-model:value="status" allow-clear placeholder="结果" style="width: 120px" @change="reload">
            <a-select-option value="success">成功</a-select-option>
            <a-select-option value="fail">失败</a-select-option>
          </a-select>
          <a-input-search v-model:value="search" placeholder="隐患点 / 编码 / 操作人 / 流域" style="width: 280px" allow-clear @search="reload" />
        </div>
      </div>
      <a-table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="pagination"
        size="small"
        row-key="id"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'operator'">
            {{ record.realname || record.username || '—' }}
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="record.status === 'success' ? 'green' : 'red'">{{ record.status === 'success' ? '成功' : '失败' }}</a-tag>
            <a-tag v-if="isYes(record.degraded)" color="orange">仅文字</a-tag>
          </template>
          <template v-else-if="column.key === 'photos'">
            {{ record.photo_count || 0 }} / {{ record.photo_total || 0 }}
          </template>
          <template v-else-if="column.key === 'cost'">
            {{ record.cost_ms == null ? '—' : `${(record.cost_ms / 1000).toFixed(1)}s` }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a @click="openDetail(record.id)">查看</a>
          </template>
        </template>
      </a-table>
    </div>

    <FeatureDetailDrawer
      v-model:open="detailOpen"
      tab="hazard"
      :code="detail?.pid || undefined"
      :item="detail?.pid ? undefined : detail?.hazard_id ? { id: String(detail.hazard_id) } : undefined"
      :preset-text="presetText"
      :preset-meta="presetMeta"
    />
  </component>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { PageWrapper } from '/@/components/Page';
  import { useMessage } from '@/hooks/web/useMessage';
  import FeatureDetailDrawer from './onemap/components/FeatureDetailDrawer.vue';
  import { getAiRiskLog, IAiRiskLogRow, listAiRiskLogs } from './ShHazardAiRiskLog.api';

  defineOptions({ name: 'super-airag-shreport-ShHazardAiRiskLog' });

  const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false });
  const embedded = computed(() => props.embedded);
  const shell = computed(() => (props.embedded ? 'div' : PageWrapper));
  const shellBind = computed(() => (props.embedded ? {} : { contentFullHeight: true }));

  const columns = [
    { title: '时间', dataIndex: 'create_time', width: 170 },
    { title: '操作人', key: 'operator', width: 100 },
    { title: '隐患点', dataIndex: 'hazard_name', ellipsis: true },
    { title: '编码', dataIndex: 'pid', width: 140, ellipsis: true },
    { title: '类型', dataIndex: 'hz_type', width: 100, ellipsis: true },
    { title: '流域', dataIndex: 'wsnm', width: 140, ellipsis: true },
    { title: '结果', key: 'status', width: 150 },
    { title: '照片', key: 'photos', width: 80 },
    { title: '耗时', key: 'cost', width: 80 },
    { title: '操作', key: 'action', width: 70 },
  ];

  const loading = ref(false);
  const rows = ref<IAiRiskLogRow[]>([]);
  const search = ref('');
  const status = ref<string | undefined>(undefined);
  const pagination = reactive({ current: 1, pageSize: 10, total: 0, showSizeChanger: true });

  const { createMessage } = useMessage();
  const detailOpen = ref(false);
  const detail = ref<IAiRiskLogRow | null>(null);

  const presetText = computed(() => detail.value?.result_text || detail.value?.error_message || '');
  const presetMeta = computed(() => {
    const row = detail.value;
    if (!row) {
      return '';
    }
    return [row.create_time, row.realname || row.username, row.status === 'success' ? '识别成功' : '识别失败', row.model_name]
      .filter(Boolean)
      .join(' · ');
  });

  function isYes(v: unknown) {
    return v === 1 || v === true || v === '1';
  }

  async function load() {
    loading.value = true;
    try {
      const res = await listAiRiskLogs({
        pageNo: pagination.current,
        pageSize: pagination.pageSize,
        search: search.value || undefined,
        status: status.value || undefined,
      });
      rows.value = res?.data || [];
      pagination.total = res?.total || 0;
    } finally {
      loading.value = false;
    }
  }

  function reload() {
    pagination.current = 1;
    load();
  }

  function onTableChange(pager: { current?: number; pageSize?: number }) {
    pagination.current = pager.current || 1;
    pagination.pageSize = pager.pageSize || 10;
    load();
  }

  async function openDetail(id: number) {
    try {
      detail.value = await getAiRiskLog(id);
      if (!detail.value?.pid && !detail.value?.hazard_id) {
        createMessage.warning('这条记录没有隐患点编码');
      }
      detailOpen.value = true;
    } catch (e: any) {
      createMessage.warning(e?.message || '记录加载失败');
    }
  }

  onMounted(load);
</script>

<style lang="less" scoped>
  .log-page {
    background: #fff;
    padding: 16px;
    min-height: 100%;

    &.is-embedded {
      background: transparent;
      padding: 0;
      min-height: 0;
    }
  }

  .log-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 12px;
  }

  .log-title {
    font-size: 16px;
    font-weight: 600;
    color: #0f172a;
  }

  .log-sub {
    margin-top: 4px;
    font-size: 12px;
    color: #94a3b8;
  }

  .log-filters {
    display: flex;
    gap: 8px;
    flex-shrink: 0;
  }
</style>
