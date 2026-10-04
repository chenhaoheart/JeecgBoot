<template>
  <div class="mapdata-page">
    <!-- 左侧数据集列表：数据图层 / 调查成果 / 文件系统 三组 -->
    <aside class="ds-side">
      <div class="side-group">
        <div class="side-group-title">知识图谱</div>
        <div class="ds-item" :class="{ active: view === 'graph' }" @click="switchToGraph">
          <span class="ds-name">山洪知识图谱</span>
        </div>
      </div>
      <div v-for="g in sideGroups" :key="g.title" class="side-group">
        <div class="side-group-title">{{ g.title }}</div>
        <div
          v-for="d in g.items"
          :key="d.key"
          class="ds-item"
          :class="{ active: view === 'table' && d.key === currentDs }"
          @click="switchDataset(d.key)"
        >
          <span class="ds-name">{{ d.label }}</span>
          <span v-if="geomKind(d.key)" class="ds-tag">{{ geomKind(d.key) }}</span>
        </div>
        <template v-if="g.files">
          <div class="ds-item" :class="{ active: view === 'files' }" @click="switchToFiles">
            <span class="ds-name">文件管理（照片/视频）</span>
            <span class="ds-tag">文件</span>
          </div>
        </template>
      </div>
    </aside>

    <div v-show="view === 'graph'" class="kg-host">
      <ShKnowledgeGraph :active="view === 'graph'" :revision="kgRevision" @edit="onGraphEdit" @maintain="onGraphMaintain" />
    </div>

    <a-card v-if="view === 'table'" :bordered="false" class="mapdata-card">
      <div class="toolbar">
        <div class="tb-left">
          <span class="ds-current">{{ activeDataset?.label }}</span>
        </div>
        <div class="tb-right">
          <template v-if="isSurveyDs">
            <a-select
              v-model:value="filterCounty"
              :options="countyOptions"
              style="width: 160px"
              size="small"
              placeholder="政区：全部"
              allowClear
              showSearch
              optionFilterProp="label"
              @change="onCountyChange"
            />
            <a-select
              v-model:value="filterTown"
              :options="townOptions"
              style="width: 160px"
              size="small"
              placeholder="乡镇：全部"
              allowClear
              showSearch
              optionFilterProp="label"
              @change="reload"
            />
            <a-select
              v-model:value="filterWs"
              :options="wsOptions"
              style="width: 190px"
              size="small"
              placeholder="流域：全部"
              allowClear
              showSearch
              optionFilterProp="label"
              @change="reload"
            />
          </template>
          <a-select
            v-model:value="currentYear"
            :options="yearOptions"
            style="width: 170px"
            size="small"
            placeholder="调查年份：全部"
            allowClear
            @change="reload"
          />
          <a-input-search
            v-model:value="search"
            placeholder="搜索任意字段"
            style="width: 200px"
            size="small"
            allowClear
            @search="reload"
          />
          <a-button size="small" @click="reload">刷新</a-button>
          <a-button size="small" type="primary" @click="openImport">
            <UploadOutlined /> 导入 Excel
          </a-button>
          <a-button size="small" danger :disabled="!selectedRowKeys.length" @click="onBatchDelete">
            <DeleteOutlined /> 删除选中 ({{ selectedRowKeys.length }})
          </a-button>
        </div>
      </div>
      <div class="tb-tip">
        共 {{ total }} 条记录
        <span v-if="activeDataset?.hasGeom" class="tip-geom">（Excel 导入不含空间几何，导入数据不出现在地图图层，仅进入表格与统计；可在「编辑」中补绘/编辑空间位置）</span>
      </div>
      <a-table
        :columns="tableColumns"
        :data-source="rows"
        :loading="loading"
        :pagination="pagination"
        :row-selection="{ selectedRowKeys, onChange: onSelectionChange }"
        :scroll="tableScroll"
        size="small"
        row-key="id"
        @resizeColumn="handleResizeColumn"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a v-if="hasDetail" @click.stop="openDetail(record)">详情</a>
            <a-divider v-if="hasDetail" type="vertical" />
            <a @click.stop="openEdit(record)">编辑</a>
            <a-divider type="vertical" />
            <a class="danger-link" @click.stop="onDeleteRow(record)">删除</a>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 文件管理（照片/视频索引） -->
    <a-card v-if="view === 'files'" :bordered="false" class="mapdata-card">
      <ShFilePanel />
    </a-card>

    <!-- 编辑弹窗 -->
    <a-modal
      v-model:open="editOpen"
      :title="`编辑${activeDataset?.label || ''}（ID: ${editRecord?.id}）`"
      :width="editModalWidth"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      class="em-modal"
      @ok="submitEdit"
    >
      <a-form layout="vertical" class="edit-form">
        <div class="form-grid" :class="{ wide: editFormCols.length > 10 }">
          <a-form-item v-for="c in editFormCols" :key="c.col" :label="c.label">
            <a-input-number
              v-if="c.numeric"
              v-model:value="editForm[c.col]"
              style="width: 100%"
              :placeholder="c.col"
            />
            <a-textarea v-else-if="isLongText(c.col)" v-model:value="editForm[c.col]" :rows="2" :placeholder="c.col" />
            <a-input v-else v-model:value="editForm[c.col]" :placeholder="c.col" />
          </a-form-item>
          <a-form-item label="调查年份">
            <a-input-number v-model:value="editForm.survey_year" style="width: 100%" :min="1900" :max="2999" placeholder="未标注" />
          </a-form-item>
        </div>
        <!-- 空间要素数据集：几何编辑内嵌在编辑弹窗，随「保存」一起提交 -->
        <template v-if="activeDataset?.hasGeom">
          <ShSectionTitle>
            空间位置
            <span class="sec-note">（编辑后随保存一起提交{{ currentDs === 'hazard' ? '，并同步经纬度' : '' }}）</span>
          </ShSectionTitle>
          <ShMapGeomEditor
            v-if="editOpen && editRecord"
            :ds-key="currentDs"
            :record="editRecord"
            :height="320"
            @change="onGeomChange"
          />
        </template>
        <template v-else-if="hasLngLat">
          <ShSectionTitle>
            平面位置
            <span class="sec-note">（{{ assocGeom ? '蓝框为按编码关联的防治对象面；' : '' }}点击地图选点 / 拖动标记微调）</span>
          </ShSectionTitle>
          <ShLngLatPicker v-if="editOpen" v-model:lng="editForm.lgtd" v-model:lat="editForm.lttd" :extra-geojson="assocGeom" />
        </template>
        <a-form-item v-if="editMediaCode" label="现场资料（按编码自动关联磁盘照片/视频，只读）">
          <ShMediaGallery v-if="editOpen" :code="editMediaCode" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 隐患点详情弹窗（照片/视频 + 地图定位） -->
    <ShHazardDetail v-model:open="hazardDetailOpen" :record="hazardRecord" />

    <!-- 调查成果详情弹窗（跨沟道路/沟滩占地：字段 + 地图 + 磁盘照片） -->
    <ShSurveyDetail
      v-model:open="surveyDetailOpen"
      :record="surveyRecord"
      :columns="activeDataset?.columns || []"
      :ds-label="activeDataset?.label"
    />

    <!-- 导入弹窗 -->
    <a-modal
      v-model:open="importOpen"
      :title="`导入 ${activeDataset?.label || ''}（Excel .xlsx）`"
      width="560px"
      :confirm-loading="importing"
      ok-text="开始导入"
      cancel-text="取消"
      @ok="submitImport"
    >
      <a-form layout="vertical">
        <a-form-item label="调查年份（必填，导入数据将标记为该年份）" required>
          <a-input-number v-model:value="importYear" style="width: 100%" :min="1900" :max="2999" placeholder="例如 2024" />
        </a-form-item>
        <a-form-item label="导入模式">
          <a-radio-group v-model:value="importMode">
            <a-radio value="append">追加（保留该年份已有数据）</a-radio>
            <a-radio value="replace">覆盖（先清空该年份全部数据再导入）</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="数据文件">
          <a-upload
            :max-count="1"
            accept=".xlsx"
            :before-upload="onPickFile"
            :file-list="importFileList"
            @remove="removeFile"
          >
            <a-button><UploadOutlined /> 选择 Excel 文件</a-button>
          </a-upload>
        </a-form-item>
        <a-alert type="info" show-icon class="import-tip">
          <template #message>表头支持列名或中文名，识别规则如下：</template>
          <template #description>
            <div class="import-cols">
              <span v-for="c in activeDataset?.columns || []" :key="c.col">· {{ c.label }}（{{ c.col }}）</span>
            </div>
          </template>
        </a-alert>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { DeleteOutlined, UploadOutlined } from '@ant-design/icons-vue';
  import { message } from 'ant-design-vue';
  import {
    deleteRows,
    getDatasets,
    getFacets,
    getGeomByPid,
    getRow,
    getRows,
    getYears,
    IDataset,
    IFacets,
    importExcel,
    updateGeom,
    updateRow,
  } from './ShMapDataAdmin.api';
  import ShMapGeomEditor from './ShMapGeomEditor.vue';
  import ShHazardDetail from './ShHazardDetail.vue';
  import ShLngLatPicker from './ShLngLatPicker.vue';
  import ShFilePanel from './ShFilePanel.vue';
  import ShMediaGallery from './ShMediaGallery.vue';
  import ShSurveyDetail from './ShSurveyDetail.vue';
  import ShSectionTitle from './ShSectionTitle.vue';
  import ShKnowledgeGraph from './ShKnowledgeGraph.vue';

  defineOptions({ name: 'ShMapDataAdmin' });

  const datasets = ref<IDataset[]>([]);
  const currentDs = ref('');
  const activeDataset = computed(() => datasets.value.find((d) => d.key === currentDs.value));

  /** 右侧视图：知识图谱 / 数据表格 / 文件管理 */
  const view = ref<'graph' | 'table' | 'files'>('graph');
  /** 图谱里改完一条数据后递增，通知图谱重新取数 */
  const kgRevision = ref(0);

  /** 左侧列表分组：数据图层（空间图层）/ 调查成果（名录表）/ 文件系统 */
  const LAYER_KEYS = ['watershed', 'section', 'prevention', 'hazard'];
  const SURVEY_KEYS = ['survey_object', 'survey_road', 'survey_gully'];
  const sideGroups = computed(() => {
    const byKeys = (keys: string[]) => datasets.value.filter((d) => keys.includes(d.key));
    return [
      { title: '数据图层', items: byKeys(LAYER_KEYS), files: false },
      { title: '调查成果', items: byKeys(SURVEY_KEYS), files: false },
      { title: '文件系统', items: [] as IDataset[], files: true },
    ];
  });

  /** 调查成果数据集才有政区/流域列，工具栏据此显示对应筛选项 */
  const isSurveyDs = computed(() => SURVEY_KEYS.includes(currentDs.value));

  /** 左侧列表的几何类型角标 */
  const GEOM_KIND: Record<string, string> = { watershed: '面', section: '线', prevention: '面', hazard: '点' };
  const geomKind = (key: string) => GEOM_KIND[key] || '';

  /** 照片/视频列只在隐患点详情弹窗中管理，不出现在表格与普通编辑表单 */
  const MEDIA_COLS = ['photos', 'videos'];

  const currentYear = ref<number | undefined>(undefined);
  const yearOptions = ref<{ label: string; value: number }[]>([]);
  const search = ref('');

  /** 调查成果的政区（县→乡镇级联）与流域过滤 */
  const filterCounty = ref<string | undefined>(undefined);
  const filterTown = ref<string | undefined>(undefined);
  const filterWs = ref<string | undefined>(undefined);
  const facets = ref<IFacets>({ counties: [], towns: [], watersheds: [] });

  const countyOptions = computed(() =>
    facets.value.counties.map((c) => ({ label: `${c.name}（${c.adcd}）`, value: String(c.adcd) }))
  );
  const townOptions = computed(() =>
    facets.value.towns
      // 乡镇代码以县代码开头，选中县后只保留其下属乡镇
      .filter((t) => !filterCounty.value || String(t.adcd).startsWith(filterCounty.value))
      .map((t) => ({ label: `${t.name}（${t.adcd}）`, value: String(t.adcd) }))
  );
  const wsOptions = computed(() =>
    facets.value.watersheds.map((w) => ({ label: w.nm ? `${w.nm}（${w.cd}）` : String(w.cd), value: String(w.cd) }))
  );

  async function loadFacets() {
    try {
      const f = await getFacets(currentDs.value);
      facets.value = {
        counties: f?.counties || [],
        towns: f?.towns || [],
        watersheds: f?.watersheds || [],
      };
    } catch {
      facets.value = { counties: [], towns: [], watersheds: [] };
    }
  }

  function onCountyChange() {
    // 县变化后原乡镇不再属于该县时清空
    if (filterTown.value && !String(filterTown.value).startsWith(filterCounty.value || '')) {
      filterTown.value = undefined;
    }
    reload();
  }

  const rows = ref<Recordable[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const pageNo = ref(1);
  const pageSize = ref(20);
  const selectedRowKeys = ref<number[]>([]);

  const LONG_TEXT_COLS = ['comments', 'backwater_objname', 'burst_objname', 'overflow_objname'];
  const isLongText = (col: string) => LONG_TEXT_COLS.includes(col);

  const editFormCols = computed(() =>
    (activeDataset.value?.columns || [])
      // 空间要素数据集的经纬度由内嵌几何编辑器维护（保存几何时后端同步回写），不在表单重复编辑
      .filter((c) => !MEDIA_COLS.includes(c.col))
      .filter((c) => !(activeDataset.value?.hasGeom && (c.col === 'lgtd' || c.col === 'lttd')))
  );

  /** 数据集列里含经纬度（lgtd/lttd）时，编辑弹窗提供地图选点 */
  const hasLngLat = computed(() => {
    const cols = activeDataset.value?.columns.map((c) => c.col) || [];
    return cols.includes('lgtd') && cols.includes('lttd');
  });

  /** 字段多时加宽弹窗；含几何编辑器的弹窗再宽一档 */
  const editModalWidth = computed(() => {
    if (activeDataset.value?.hasGeom) {
      return '960px';
    }
    return editFormCols.value.length > 10 ? '880px' : '640px';
  });

  /** 调查成果（防治对象名录）按 15 位政区编码 fcd 关联的防治对象面几何 */
  const assocGeom = ref<any>(null);

  async function loadAssocGeom() {
    assocGeom.value = null;
    if (currentDs.value !== 'survey_object' || !editRecord.value?.fcd) {
      return;
    }
    try {
      const res = await getGeomByPid('prevention', String(editRecord.value.fcd));
      assocGeom.value = res?.geometry || null;
    } catch {
      // 接口异常时不展示关联图层
    }
  }

  /** 各数据集单独记住拖动后的列宽，key = 数据集:列 */
  const columnWidths = ref<Record<string, number>>({});

  function widthKey(key: string) {
    return `${currentDs.value}:${key}`;
  }

  function defaultColWidth(label: string) {
    return Math.max(label.length * 14 + 24, 90);
  }

  const tableColumns = computed(() => {
    const cols: Recordable[] = editFormCols.value.map((c) => ({
      title: c.label,
      dataIndex: c.col,
      key: c.col,
      width: columnWidths.value[widthKey(c.col)] ?? defaultColWidth(c.label),
      minWidth: 72,
      resizable: true,
      ellipsis: true,
    }));
    cols.push({
      title: '调查年份',
      dataIndex: 'survey_year',
      key: 'survey_year',
      width: columnWidths.value[widthKey('survey_year')] ?? 90,
      minWidth: 72,
      resizable: true,
    });
    cols.push({
      title: '操作',
      key: 'action',
      fixed: 'right',
      width: columnWidths.value[widthKey('action')] ?? 150,
      minWidth: 120,
      resizable: true,
    });
    return cols;
  });

  function handleResizeColumn(w: number, col: { key?: string; dataIndex?: string }) {
    const key = String(col.key || col.dataIndex || '');
    if (!key) {
      return;
    }
    columnWidths.value[widthKey(key)] = Math.max(72, Math.round(w));
  }

  const tableScroll = computed(() => {
    const sum = tableColumns.value.reduce((n, c) => n + Number(c.width || 0), 0);
    return { x: sum + 48 };
  });

  const pagination = computed(() => ({
    current: pageNo.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ['20', '50', '100', '200'],
    showTotal: (t: number) => `共 ${t} 条`,
  }));

  async function loadDatasets() {
    datasets.value = (await getDatasets()) || [];
    if (datasets.value.length && !currentDs.value) {
      currentDs.value = datasets.value[0].key;
    }
  }

  async function loadYears() {
    try {
      const list = (await getYears(currentDs.value)) || [];
      yearOptions.value = list.map((y) => ({
        label: y.year === 0 ? `未标注（${y.count}）` : `${y.year} 年（${y.count}）`,
        value: y.year,
      }));
    } catch {
      yearOptions.value = [];
    }
  }

  async function loadRows() {
    loading.value = true;
    try {
      const res = await getRows({
        ds: currentDs.value,
        year: currentYear.value,
        search: search.value || undefined,
        countyAdcd: filterCounty.value || undefined,
        townAdcd: filterTown.value || undefined,
        wscd: filterWs.value || undefined,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
      });
      rows.value = res?.data || [];
      total.value = res?.total || 0;
      selectedRowKeys.value = [];
    } finally {
      loading.value = false;
    }
  }

  function reload() {
    pageNo.value = 1;
    loadYears();
    loadFacets();
    loadRows();
  }

  function switchDataset(key: string) {
    const same = view.value === 'table' && key === currentDs.value;
    view.value = 'table';
    if (same) {
      return;
    }
    currentDs.value = key;
    onDatasetChange();
  }

  function switchToGraph() {
    view.value = 'graph';
  }

  function switchToFiles() {
    view.value = 'files';
  }

  async function onGraphEdit(payload: { ds: string; rowId: number }) {
    if (!datasets.value.length) {
      await loadDatasets();
    }
    currentDs.value = payload.ds;
    try {
      const row = await getRow(payload.ds, payload.rowId);
      openEdit(row);
    } catch (e: any) {
      message.error(e?.message || '读取记录失败');
    }
  }

  function onGraphMaintain(payload: { ds: string; wscd?: string; search?: string }) {
    view.value = 'table';
    currentDs.value = payload.ds;
    currentYear.value = undefined;
    search.value = payload.search || '';
    filterCounty.value = undefined;
    filterTown.value = undefined;
    filterWs.value = SURVEY_KEYS.includes(payload.ds) ? payload.wscd : undefined;
    pageNo.value = 1;
    loadYears();
    loadFacets();
    loadRows();
  }

  function onDatasetChange() {
    currentYear.value = undefined;
    search.value = '';
    filterCounty.value = undefined;
    filterTown.value = undefined;
    filterWs.value = undefined;
    reload();
  }

  function onTableChange(p: any) {
    pageNo.value = p.current;
    pageSize.value = p.pageSize;
    loadRows();
  }

  function onSelectionChange(keys: number[]) {
    selectedRowKeys.value = keys;
  }

  // ---------------------------------------------------------------- 行编辑

  const editOpen = ref(false);
  const editRecord = ref<Recordable | null>(null);
  const editForm = reactive<Recordable>({});
  const saving = ref(false);

  /** 内嵌几何编辑器的改动：undefined 表示未编辑过几何，null 表示编辑了但不完整 */
  let geomDraft: any = undefined;
  let geomTouched = false;

  function onGeomChange(geometry: any | null) {
    geomTouched = true;
    geomDraft = geometry;
  }

  function openEdit(record: Recordable) {
    editRecord.value = record;
    Object.keys(editForm).forEach((k) => delete editForm[k]);
    for (const c of editFormCols.value) {
      editForm[c.col] = record[c.col] ?? null;
    }
    editForm.survey_year = record.survey_year ?? null;
    geomDraft = undefined;
    geomTouched = false;
    editOpen.value = true;
    loadAssocGeom();
  }

  async function submitEdit() {
    if (!editRecord.value) {
      return;
    }
    if (geomTouched && geomDraft === null) {
      message.warning('几何数据不完整，请按地图上方的提示补全后再保存');
      return;
    }
    saving.value = true;
    try {
      await updateRow(currentDs.value, editRecord.value.id, { ...editForm });
      if (geomTouched && geomDraft) {
        await updateGeom(currentDs.value, editRecord.value.id, geomDraft);
      }
      message.success('已保存');
      editOpen.value = false;
      kgRevision.value += 1;
      await Promise.all([loadRows(), loadYears()]);
    } finally {
      saving.value = false;
    }
  }

  function onDeleteRow(record: Recordable) {
    Modal.confirm({
      title: '删除这条数据？',
      content: '删除后无法恢复，仅影响当前选中的这一行。',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        await deleteRows(currentDs.value, [record.id]);
        message.success('已删除');
        reload();
      },
    });
  }

  function onBatchDelete() {
    const n = selectedRowKeys.value.length;
    Modal.confirm({
      title: `删除选中的 ${n} 条数据？`,
      content: '删除后无法恢复，请确认所选数据不再需要。',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        await deleteRows(currentDs.value, [...selectedRowKeys.value]);
        message.success('已删除 ' + n + ' 条');
        reload();
      },
    });
  }

  // ---------------------------------------------------------------- 隐患点/调查成果详情（照片/视频 + 定位）

  /** 有详情弹窗的数据集：隐患点 + 调查成果（跨沟道路、沟滩占地） */
  const hasDetail = computed(() => ['hazard', 'survey_object', 'survey_road', 'survey_gully'].includes(currentDs.value));

  /** 编辑弹窗内嵌的磁盘媒体关联编码：隐患点用 pid，跨沟道路/沟滩占地用 code */
  const editMediaCode = computed(() => {
    if (currentDs.value === 'hazard') {
      return editRecord.value?.pid ?? null;
    }
    if (currentDs.value === 'survey_road' || currentDs.value === 'survey_gully') {
      return editRecord.value?.code ?? null;
    }
    return null;
  });

  const hazardDetailOpen = ref(false);
  const hazardRecord = ref<Recordable | null>(null);

  const surveyDetailOpen = ref(false);
  const surveyRecord = ref<Recordable | null>(null);

  function openDetail(record: Recordable) {
    if (currentDs.value === 'hazard') {
      hazardRecord.value = record;
      hazardDetailOpen.value = true;
    } else {
      surveyRecord.value = record;
      surveyDetailOpen.value = true;
    }
  }

  // ---------------------------------------------------------------- Excel 导入

  const importOpen = ref(false);
  const importYear = ref<number | null>(null);
  const importMode = ref<'append' | 'replace'>('append');
  const importFile = ref<File | null>(null);
  const importFileList = ref<any[]>([]);
  const importing = ref(false);

  function openImport() {
    importYear.value = null;
    importMode.value = 'append';
    importFile.value = null;
    importFileList.value = [];
    importOpen.value = true;
  }

  function onPickFile(file: File) {
    importFile.value = file;
    importFileList.value = [{ uid: Date.now(), name: file.name, status: 'done' }];
    return false;
  }

  function removeFile() {
    importFile.value = null;
    importFileList.value = [];
  }

  async function submitImport() {
    if (!importYear.value) {
      message.warning('请填写调查年份');
      return;
    }
    if (!importFile.value) {
      message.warning('请选择 Excel 文件');
      return;
    }
    if (importMode.value === 'replace') {
      const label = importYear.value === 0 ? '未标注' : importYear.value + ' 年';
      const confirmed = await new Promise<boolean>((resolve) => {
        Modal.confirm({
          title: `覆盖导入将先清空 ${label} 的全部存量数据`,
          content: '该年份已有数据会全部删除后重新导入，确定继续吗？',
          okText: '清空并导入',
          okType: 'danger',
          cancelText: '取消',
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      });
      if (!confirmed) {
        return;
      }
    }
    importing.value = true;
    try {
      const res: any = await importExcel({
        ds: currentDs.value,
        year: importYear.value,
        mode: importMode.value,
        file: importFile.value,
      });
      message.success(`导入完成：成功 ${res?.imported ?? 0} 条，跳过空行 ${res?.skipped ?? 0} 条`);
      importOpen.value = false;
      reload();
    } catch (e: any) {
      message.error(e?.message || '导入失败');
    } finally {
      importing.value = false;
    }
  }

  onMounted(loadDatasets);
</script>

<style lang="less" scoped>
  .mapdata-page {
    display: flex;
    align-items: stretch;
    gap: 10px;
    padding: 12px;

    .kg-host {
      flex: 1;
      min-width: 0;
      display: flex;
    }

    .mapdata-card {
      flex: 1;
      min-width: 0;
      border-radius: 10px;
      box-shadow: 0 1px 4px rgba(0, 21, 41, 0.06);
    }
  }

  .ds-side {
    flex: none;
    width: 200px;
    padding: 10px 8px;
    background: #fff;
    border-radius: 10px;
    box-shadow: 0 1px 4px rgba(0, 21, 41, 0.06);
    align-self: flex-start;
    position: sticky;
    top: 12px;

    .side-group {
      padding-bottom: 6px;
      margin-bottom: 8px;
      border-bottom: 1px solid #f0f0f0;

      &:last-child {
        border-bottom: none;
        margin-bottom: 0;
        padding-bottom: 0;
      }
    }

    .side-group-title {
      padding: 2px 10px 6px;
      font-size: 12px;
      font-weight: 600;
      color: #98a2b3;
      letter-spacing: 1px;
    }

    .ds-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 6px;
      padding: 8px 10px;
      margin: 2px 0;
      border-radius: 6px;
      cursor: pointer;
      font-size: 13px;
      color: #344054;
      transition: all 0.2s;

      &:hover {
        background: #f5f7fa;
      }

      &.active {
        background: #e6f4ff;
        color: #1677ff;
        font-weight: 600;
      }

      .ds-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .ds-tag {
        flex: none;
        font-size: 11px;
        line-height: 16px;
        padding: 0 6px;
        border-radius: 4px;
        background: #f2f4f7;
        color: #667085;
      }
    }

    .ds-item.active .ds-tag {
      background: #bae0ff;
      color: #1677ff;
    }
  }

  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 10px;

    .tb-left {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .ds-current {
      font-weight: 600;
      font-size: 14px;
      color: #101828;
    }

    .tb-right {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
  }

  .tb-tip {
    margin-bottom: 8px;
    font-size: 12px;
    color: #98a2b3;

    .tip-geom {
      color: #d48806;
    }
  }

  .danger-link {
    color: #ff4d4f;
  }

  /* 编辑弹窗：栅格行列间距（弹窗内边距由 .em-modal 统一恢复） */
  .edit-form {
    padding: 0;
  }

  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px 24px;

    &.wide {
      grid-template-columns: 1fr 1fr 1fr;
    }

    :deep(.ant-form-item) {
      margin-bottom: 10px;
    }

    /* 栅格后的整行区块（地图选点 / 现场资料）与栅格拉开距离 */
    & + :deep(.ant-form-item),
    & + .ant-form-item {
      margin-top: 12px;
    }
  }

  .import-tip {
    .import-cols {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      max-height: 140px;
      overflow-y: auto;
      font-size: 12px;
    }
  }
</style>

<style lang="less">
  /* 项目全局把 .ant-modal-body padding 压成 0，这里按弹窗显式恢复留白 */
  .em-modal.ant-modal .ant-modal-body {
    padding: 20px 24px 24px;
  }
</style>
