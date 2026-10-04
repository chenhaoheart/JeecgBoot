<template>
  <div class="kg-rel">
    <div class="rel-head">
      <div>
        <div class="rel-title">关系初始化</div>
        <p class="rel-tip">
          从现有的小流域、政区、河流、防治对象、断面、隐患和调查成果里整理关系。停用后图谱不再画这条线，重新初始化也不会重新打开。手工添加的关系会保留。删掉的派生关系会在下次初始化时按业务数据重新出现。
        </p>
      </div>
      <div class="rel-actions">
        <a-button size="small" type="primary" :loading="initializing" @click="onInit">
          {{ status?.initialized ? '重新初始化' : '从业务数据初始化' }}
        </a-button>
        <a-button size="small" @click="openAdd">手工添加</a-button>
        <a-button size="small" @click="reload">刷新</a-button>
      </div>
    </div>

    <div v-if="loadError" class="rel-warn">{{ loadError }}</div>
    <div v-else class="rel-status">
      <template v-if="status?.initialized">
        已于 {{ status.initTime || '—' }} 初始化，关系库 {{ status.total }} 条（派生 {{ status.derived }}，手工 {{ status.manual }}，停用 {{ status.disabled }}）。
      </template>
      <template v-else>还没有初始化。点「从业务数据初始化」后，下面会列出可以维护的关系。</template>
      <span v-if="status?.message">{{ status.message }}</span>
    </div>

    <div v-if="rules.length" class="rule-board">
      <button
        v-for="r in rules"
        :key="r.token"
        type="button"
        class="rule-chip"
        :class="{ on: activeToken === r.token }"
        :title="r.blurb"
        @click="toggleRule(r)"
      >
        <span class="rule-line">{{ r.srcCatName }} {{ r.label }} {{ r.tgtCatName }}</span>
        <span class="rule-num">{{ r.count }}</span>
      </button>
    </div>

    <div class="rel-filters">
      <a-select v-model:value="origin" style="width: 120px" size="small" allowClear placeholder="来源" @change="reloadList">
        <a-select-option value="init">初始化</a-select-option>
        <a-select-option value="manual">手工</a-select-option>
      </a-select>
      <a-select v-model:value="enabled" style="width: 120px" size="small" allowClear placeholder="状态" @change="reloadList">
        <a-select-option :value="1">启用</a-select-option>
        <a-select-option :value="0">停用</a-select-option>
      </a-select>
      <a-input-search
        v-model:value="keyword"
        placeholder="起点、终点、编码、流域"
        style="width: 240px"
        size="small"
        allowClear
        @search="reloadList"
      />
    </div>

    <a-table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      size="small"
      row-key="id"
      :scroll="{ x: 980 }"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'rel'">
          <span class="rel-verb">{{ record.relLabel }}</span>
        </template>
        <template v-else-if="column.key === 'src'">
          <div class="obj-name">{{ record.srcName || record.srcId }}</div>
          <div class="obj-sub">{{ record.srcCatName }} · {{ record.srcId }}</div>
        </template>
        <template v-else-if="column.key === 'tgt'">
          <div class="obj-name">{{ record.tgtName || record.tgtId }}</div>
          <div class="obj-sub">{{ record.tgtCatName }} · {{ record.tgtId }}</div>
        </template>
        <template v-else-if="column.key === 'origin'">
          {{ record.origin === 'manual' ? '手工' : '初始化' }}
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a @click.stop="onToggle(record)">{{ record.enabled ? '停用' : '启用' }}</a>
          <a-divider type="vertical" />
          <a class="danger-link" @click.stop="onDelete(record)">删除</a>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="addOpen"
      title="手工添加关系"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="saving"
      :width="560"
      class="em-modal"
      @ok="submitAdd"
    >
      <a-form layout="vertical" class="add-form">
        <a-radio-group v-model:value="form.mode" size="small" class="mode-switch">
          <a-radio-button value="exist">选用已有关系</a-radio-button>
          <a-radio-button value="create">新增一种关系</a-radio-button>
        </a-radio-group>
        <a-form-item v-if="form.mode === 'exist'" label="关系" required>
          <a-select
            v-model:value="form.token"
            :options="ruleOptions"
            showSearch
            optionFilterProp="label"
            placeholder="选择一种关系"
          />
        </a-form-item>
        <template v-else>
          <a-form-item label="关系名称" required>
            <a-input v-model:value="form.relLabel" :maxlength="16" placeholder="例如：相邻、补给、位于上游" />
          </a-form-item>
          <div class="add-grid">
            <a-form-item label="起点类型" required>
              <a-select v-model:value="form.srcCat" :options="catOptions" showSearch optionFilterProp="label" placeholder="起点是哪一类" />
            </a-form-item>
            <a-form-item label="终点类型" required>
              <a-select v-model:value="form.tgtCat" :options="catOptions" showSearch optionFilterProp="label" placeholder="终点是哪一类" />
            </a-form-item>
          </div>
          <p class="add-blurb">保存后，这种关系会出现在「选用已有关系」的下拉框里，下次可以直接选用。</p>
        </template>
        <div class="add-grid">
          <a-form-item :label="`起点名称（${activeRule?.srcCatName || '对象'}）`" required>
            <a-input v-model:value="form.srcName" placeholder="例如：红山河" />
          </a-form-item>
          <a-form-item :label="`起点标识（${activeRule?.srcHint || '不填则按名称生成'}）`">
            <a-input v-model:value="form.srcId" :placeholder="activeRule?.srcHint || '不填则按名称生成'" />
          </a-form-item>
          <a-form-item :label="`终点名称（${activeRule?.tgtCatName || '对象'}）`" required>
            <a-input v-model:value="form.tgtName" placeholder="例如：某村" />
          </a-form-item>
          <a-form-item :label="`终点标识（${activeRule?.tgtHint || '不填则按名称生成'}）`">
            <a-input v-model:value="form.tgtId" :placeholder="activeRule?.tgtHint || '不填则按名称生成'" />
          </a-form-item>
          <a-form-item label="小流域编码">
            <a-input v-model:value="form.wscd" placeholder="填写后，展开该流域时能看到这条关系" />
          </a-form-item>
          <a-form-item label="调查年份">
            <a-input-number v-model:value="form.surveyYear" style="width: 100%" :min="1900" :max="2999" placeholder="不填则各年份都能看到" />
          </a-form-item>
        </div>
        <p v-if="activeRule?.blurb" class="add-blurb">{{ activeRule.blurb }}</p>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { message } from 'ant-design-vue';
  import {
    deleteKgRelation,
    getKgRelList,
    getKgRelStatus,
    initKgRelations,
    saveKgRelation,
    setKgRelationEnabled,
    type IKgRelRow,
    type IKgRelRule,
    type IKgRelStatus,
  } from './ShMapDataAdmin.api';

  defineOptions({ name: 'ShKgRelationAdmin' });

  const emit = defineEmits<{ (e: 'changed'): void }>();

  const CAT_OPTIONS = [
    { value: 'watershed', label: '小流域', hint: '小流域编码' },
    { value: 'river', label: '河流', hint: '河流名称' },
    { value: 'county', label: '县', hint: '县代码' },
    { value: 'town', label: '乡镇', hint: '乡镇代码' },
    { value: 'prevention', label: '防治对象', hint: '防护对象代码' },
    { value: 'section', label: '断面', hint: '断面记录 ID' },
    { value: 'hazard', label: '隐患要素', hint: '隐患记录 ID' },
    { value: 'road', label: '跨沟道路', hint: '跨沟道路记录 ID' },
    { value: 'gully', label: '沟滩占地', hint: '沟滩占地记录 ID' },
  ];
  const catOptions = CAT_OPTIONS.map((c) => ({ value: c.value, label: c.label }));
  const catName = (key?: string) => CAT_OPTIONS.find((c) => c.value === key)?.label || '对象';
  const catHint = (key?: string) => CAT_OPTIONS.find((c) => c.value === key)?.hint || '不填则按名称生成';

  const status = ref<IKgRelStatus | null>(null);
  const rows = ref<IKgRelRow[]>([]);
  const total = ref(0);
  const pageNo = ref(1);
  const pageSize = ref(20);
  const loading = ref(false);
  const initializing = ref(false);
  const loadError = ref('');
  const origin = ref<string | undefined>(undefined);
  const enabled = ref<number | undefined>(undefined);
  const keyword = ref('');
  const activeToken = ref('');

  const addOpen = ref(false);
  const saving = ref(false);
  const form = reactive({
    mode: 'exist' as 'exist' | 'create',
    token: undefined as string | undefined,
    relLabel: '',
    srcCat: undefined as string | undefined,
    tgtCat: undefined as string | undefined,
    srcName: '',
    srcId: '',
    tgtName: '',
    tgtId: '',
    wscd: '',
    surveyYear: undefined as number | undefined,
  });

  const rules = computed(() => status.value?.rules || []);
  const activeRule = computed(() => {
    if (form.mode === 'create') {
      return {
        srcCatName: catName(form.srcCat),
        tgtCatName: catName(form.tgtCat),
        srcHint: catHint(form.srcCat),
        tgtHint: catHint(form.tgtCat),
        blurb: '',
      };
    }
    return rules.value.find((r) => r.token === form.token);
  });
  const ruleOptions = computed(() =>
    rules.value.map((r) => ({
      label: `${r.srcCatName} ${r.label} ${r.tgtCatName}`,
      value: r.token,
    }))
  );

  const columns = [
    { title: '关系', key: 'rel', dataIndex: 'relLabel', width: 100 },
    { title: '起点', key: 'src', dataIndex: 'srcName', width: 240 },
    { title: '终点', key: 'tgt', dataIndex: 'tgtName', width: 240 },
    { title: '小流域', dataIndex: 'wscd', width: 140, ellipsis: true },
    { title: '来源', key: 'origin', dataIndex: 'origin', width: 90 },
    { title: '状态', key: 'enabled', dataIndex: 'enabled', width: 90 },
    { title: '年份', dataIndex: 'surveyYear', width: 80 },
    { title: '操作', key: 'action', width: 120, fixed: 'right' as const },
  ];

  const pagination = computed(() => ({
    current: pageNo.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    showTotal: (t: number) => `共 ${t} 条`,
  }));

  function selectedRule(): IKgRelRule | undefined {
    return rules.value.find((r) => r.token === activeToken.value);
  }

  async function loadStatus() {
    loadError.value = '';
    try {
      status.value = await getKgRelStatus();
    } catch (e: any) {
      const msg = e?.message || '关系库还没加载';
      loadError.value = msg.includes('路径不存在') ? '关系初始化接口还没加载。请重新启动后端，再点刷新。' : msg;
    }
  }

  async function loadList() {
    loading.value = true;
    try {
      const rule = selectedRule();
      const res = await getKgRelList({
        relKey: rule?.key,
        srcCat: rule?.srcCat,
        tgtCat: rule?.tgtCat,
        origin: origin.value,
        enabled: enabled.value,
        q: keyword.value?.trim() || undefined,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
      });
      rows.value = res?.data || [];
      total.value = res?.total || 0;
    } catch (e: any) {
      if (!loadError.value) {
        message.error(e?.message || '关系列表加载失败');
      }
    } finally {
      loading.value = false;
    }
  }

  async function reload() {
    await loadStatus();
    await loadList();
  }

  function reloadList() {
    pageNo.value = 1;
    loadList();
  }

  function toggleRule(rule: IKgRelRule) {
    activeToken.value = activeToken.value === rule.token ? '' : rule.token;
    reloadList();
  }

  function onTableChange(p: { current?: number; pageSize?: number }) {
    pageNo.value = p.current || 1;
    pageSize.value = p.pageSize || 20;
    loadList();
  }

  function onInit() {
    const again = !!status.value?.initialized;
    Modal.confirm({
      title: again ? '重新初始化关系？' : '从业务数据初始化关系？',
      content: again
        ? '将按当前业务数据补齐派生关系。已停用的保持停用，手工添加的保留。之前删掉的派生关系会重新出现。'
        : '将按小流域、政区、河流、防治对象、断面、隐患和调查成果的现有字段生成关系。生成后可在本页停用或手工补充。',
      okText: again ? '重新初始化' : '开始初始化',
      cancelText: '取消',
      onOk: async () => {
        initializing.value = true;
        try {
          const res = await initKgRelations();
          status.value = res;
          if (res?.ok === false && res.errors?.length) {
            Modal.warning({
              title: '有部分关系没能生成',
              content: res.errors.join('；'),
            });
          } else {
            message.success(res?.message || '初始化完成');
          }
          pageNo.value = 1;
          await loadList();
          emit('changed');
        } catch (e: any) {
          message.error(e?.message || '初始化失败');
        } finally {
          initializing.value = false;
        }
      },
    });
  }

  function openAdd() {
    form.mode = 'exist';
    form.token = activeToken.value || rules.value[0]?.token;
    form.relLabel = '';
    form.srcCat = undefined;
    form.tgtCat = undefined;
    form.srcName = '';
    form.srcId = '';
    form.tgtName = '';
    form.tgtId = '';
    form.wscd = '';
    form.surveyYear = undefined;
    addOpen.value = true;
  }

  async function submitAdd() {
    const creating = form.mode === 'create';
    const rule = creating ? undefined : rules.value.find((r) => r.token === form.token);
    if (!creating && !rule) {
      message.warning('请选择一种关系');
      return Promise.reject(new Error('invalid'));
    }
    if (creating && !form.relLabel.trim()) {
      message.warning('请填写关系名称');
      return Promise.reject(new Error('invalid'));
    }
    if (creating && (!form.srcCat || !form.tgtCat)) {
      message.warning('请选择起点类型和终点类型');
      return Promise.reject(new Error('invalid'));
    }
    if (!form.srcName.trim() || !form.tgtName.trim()) {
      message.warning('请填写起点和终点名称');
      return Promise.reject(new Error('invalid'));
    }
    saving.value = true;
    try {
      await saveKgRelation({
        custom: creating,
        relKey: rule?.key,
        relLabel: creating ? form.relLabel.trim() : rule?.label,
        srcCat: creating ? form.srcCat : rule?.srcCat,
        tgtCat: creating ? form.tgtCat : rule?.tgtCat,
        srcName: form.srcName.trim(),
        srcId: form.srcId.trim(),
        tgtName: form.tgtName.trim(),
        tgtId: form.tgtId.trim(),
        wscd: form.wscd.trim(),
        surveyYear: form.surveyYear,
      });
      message.success('已添加');
      addOpen.value = false;
      await reload();
      emit('changed');
    } catch (e: any) {
      message.error(e?.message || '保存失败');
      return Promise.reject(e);
    } finally {
      saving.value = false;
    }
  }

  async function onToggle(record: IKgRelRow) {
    try {
      await setKgRelationEnabled(record.id, !record.enabled);
      message.success(record.enabled ? '已停用，图谱里不再画这条线' : '已启用');
      await reload();
      emit('changed');
    } catch (e: any) {
      message.error(e?.message || '保存失败');
    }
  }

  function onDelete(record: IKgRelRow) {
    const derived = record.origin !== 'manual';
    Modal.confirm({
      title: '删除这条关系？',
      content: derived
        ? '从关系库去掉。图谱仍会按业务字段画出，直到再次初始化；若要让图谱不再画，请改用停用。'
        : '手工关系删除后，图谱里不再出现。',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        await deleteKgRelation(record.id);
        message.success('已删除');
        await reload();
        emit('changed');
      },
    });
  }

  onMounted(reload);
</script>

<style lang="less" scoped>
  .kg-rel {
    .rel-head {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      align-items: flex-start;
    }

    .rel-title {
      font-size: 14px;
      font-weight: 600;
      color: #101828;
    }

    .rel-tip,
    .rel-status,
    .add-blurb {
      margin: 6px 0 0;
      font-size: 12px;
      line-height: 1.6;
      color: #667085;
    }

    .rel-warn {
      margin-top: 8px;
      font-size: 12px;
      color: #d48806;
    }

    .rel-actions {
      display: flex;
      flex: none;
      gap: 8px;
    }

    .mode-switch {
      margin-bottom: 12px;
    }

    .rule-board {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 12px 0;
    }

    .rule-chip {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      min-width: 168px;
      padding: 6px 10px;
      border: 1px solid #eaecf0;
      border-radius: 8px;
      background: #fff;
      cursor: pointer;

      &.on {
        border-color: #1677ff;
        background: #e6f4ff;
      }
    }

    .rule-line {
      font-size: 12px;
      color: #344054;
    }

    .rule-num {
      font-size: 14px;
      font-weight: 600;
      color: #101828;
    }

    .rel-filters {
      display: flex;
      gap: 8px;
      margin-bottom: 10px;
    }

    .rel-verb {
      color: #1677ff;
    }

    .obj-name {
      color: #101828;
    }

    .obj-sub {
      font-size: 12px;
      color: #98a2b3;
      word-break: break-all;
    }

    .danger-link {
      color: #ff4d4f;
    }

    .add-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 16px;
    }

    .add-form :deep(.ant-form-item) {
      margin-bottom: 12px;
    }
  }
</style>
