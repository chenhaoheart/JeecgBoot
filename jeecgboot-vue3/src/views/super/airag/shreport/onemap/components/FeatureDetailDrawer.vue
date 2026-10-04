<template>
  <a-drawer :open="open" width="860" :mask-closable="!aiLoading" :keyboard="!aiLoading" @close="onDrawerClose">
    <template #title>
      <div class="drawer-title">
        <span class="drawer-title-text">{{ title }}</span>
        <a-tag v-for="t in riskFlags" :key="t" color="red">{{ t }}</a-tag>
      </div>
    </template>
    <a-spin :spinning="loading">
      <div v-if="!detail && !loading && !presetText" class="empty">{{ code ? '未找到该编码对应的隐患点' : '暂无详情' }}</div>
      <template v-else-if="detail || presetText">
        <div v-if="!detail && code && !loading" class="hint">未找到该编码对应的隐患点，以下仅保留本次识别结果。</div>
        <template v-if="detail">
        <ShSectionTitle first>图层信息</ShSectionTitle>
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item v-for="item in fields" :key="item.key" :label="item.label">
            {{ display(detail?.[item.key]) }}
          </a-descriptions-item>
        </a-descriptions>
        </template>
        <template v-if="tab === 'hazard' || presetText">
          <ShSectionTitle>
            AI识别风险隐患
            <a-button v-if="!presetText" type="primary" size="small" :loading="aiLoading" @click="runAiRisk">开始识别</a-button>
            <a-button v-if="!presetText" type="link" size="small" @click="openLedger">AI隐患分析</a-button>
          </ShSectionTitle>
          <div class="ai-risk-hint">
            {{
              aiLoading
                ? '正在识别，完成前点击空白处或按 Esc 不会关闭抽屉。'
                : presetText
                  ? '以下为本次台账保存的识别结果。图层、照片和调查成果与隐患点预览相同。'
                  : '结合现场照片和外业调查备注判断风险。结果只在这里展示，不会改调查数据。'
            }}
          </div>
          <div v-if="aiMeta" class="ai-risk-meta">{{ aiMeta }}</div>
          <div v-if="aiText" class="ai-risk-result">
            <ShMarkdown :value="aiText" />
          </div>
        </template>
        <template v-if="detail">
          <!-- 现场照片/视频：与「一张图数据维护」一致，磁盘索引按编码自动关联 + photos/videos 列（只读） -->
          <ShMediaGallery :code="mediaCode" :photos="photos" :videos="videos" />
          <template v-if="surveyRows.length">
            <ShSectionTitle>调查成果</ShSectionTitle>
            <a-descriptions :column="2" size="small" bordered>
              <a-descriptions-item v-for="item in surveyRows" :key="item.key" :label="item.label">
                {{ display(item.value) }}
              </a-descriptions-item>
            </a-descriptions>
          </template>
          <div v-if="!hasCoord && tab === 'hazard'" class="hint">该记录无坐标，无法在地图定位</div>
        </template>
      </template>
    </a-spin>
  </a-drawer>
</template>

  <script lang="ts" setup>
  import { computed, onMounted, ref, watch } from 'vue';
  import { useRouter } from 'vue-router';
  import { analyzeHazardRisk, getHazardByCode, getHazardDetail, getPreventionDetail } from '../ShOneMap.api';
  import { useMessage } from '@/hooks/web/useMessage';
  import { getDatasets, getRows } from '../../ShMapDataAdmin.api';
  import ShMediaGallery from '../../ShMediaGallery.vue';
  import ShSectionTitle from '../../ShSectionTitle.vue';
  import ShMarkdown from '../../components/ShMarkdown.vue';

  defineOptions({ name: 'ShOneMapFeatureDetailDrawer' });

  const props = defineProps<{
    open: boolean;
    tab?: 'prevention' | 'hazard';
    item?: any;
    /** 按隐患点编码加载，与地图预览同一接口数据 */
    code?: string;
    /** 台账回看时预填的识别结果 */
    presetText?: string;
    presetMeta?: string;
  }>();
  const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>();

  const { createMessage } = useMessage();
  const router = useRouter();
  const loading = ref(false);
  const detail = ref<any>(null);
  const aiLoading = ref(false);
  const aiText = ref('');
  const aiMeta = ref('');
  let aiSeq = 0;
  /** 防治对象关联的调查成果（数据维护「防治对象名录」，按政区编码 fcd 匹配） */
  const surveyFromAdmin = ref<any>(null);

  const title = computed(() => {
    const d = detail.value || {};
    // 隐患要素标题显示隐患名称；防治对象显示防护对象名称
    if (props.tab === 'hazard') {
      return d.name || d.fnm || '要素详情';
    }
    return d.obj_name || d.fnm || d.name || '要素详情';
  });

  const riskFlags = computed<string[]>(() => {
    const v = detail.value?.risk_flags;
    return Array.isArray(v) ? v.map(String) : [];
  });

  const fields = computed(() => {
    const map = detail.value?.comment_map || {};
    const preferred =
      props.tab === 'prevention'
        ? ['obj_name', 'obj_pid', 'obj_type', 'pcount', 'county_name', 'town_name', 'rvname', 'rvcd', 'wsnm', 'wscd', 'comments']
        : ['name', 'pid', 'hz_type', 'rvname', 'rvcd', 'fnm', 'fcd', 'wsnm', 'wscd', 'lng', 'lat'];
    return preferred.filter((k) => detail.value && (detail.value[k] != null || map[k])).map((k) => ({ key: k, label: map[k] || k }));
  });

  const SURVEY_LABELS: Record<string, string> = {
    name: '名称',
    code: '编码',
    stype: '类型',
    lgtd: '经度',
    lttd: '纬度',
    channelw: '沟宽',
    channeld: '沟深',
    csshpt: '断面形态',
    obstruction_area_ratio: '阻水面积比',
    obstruction_storage_capacity: '阻水库容',
    backwater_objname: '壅水影响对象',
    burst_objname: '溃决影响对象',
    overflow_objname: '漫流改道影响对象',
    width: '沟宽',
    deep: '沟深',
    dmform: '断面形态',
    zsmj: '阻水面积比',
    // 防治对象名录（survey_object，按政区编码映射）
    fnm: '防护对象名称',
    fcd: '防护对象代码',
    ftp: '防护对象类型',
    county_name: '所属县区',
    town_name: '所属乡镇',
    wsnm: '小流域名称',
    wscd: '小流域编码',
    river_section_name: '河段名称',
    survey_year: '调查年份',
    pcount: '人口',
    comments: '备注',
  };

  /** 数据维护各数据集的列配置（col -> 中文标签），用于完整展示调查成果 */
  const datasetCols = ref<Record<string, { col: string; label: string }[]>>({});
  let datasetsLoaded = false;
  async function ensureDatasets() {
    if (datasetsLoaded) {
      return;
    }
    datasetsLoaded = true;
    try {
      const list = await getDatasets();
      const map: Record<string, { col: string; label: string }[]> = {};
      (list || []).forEach((d) => {
        map[d.key] = d.columns || [];
      });
      datasetCols.value = map;
    } catch {
      // 拿不到列配置时退回内置标签
    }
  }
  onMounted(ensureDatasets);

  /** 调查成果所属数据集：防治对象名录 / 跨沟道路桥涵 / 沟滩占地 */
  const surveyDatasetKey = computed(() => {
    if (props.tab === 'prevention') {
      return 'survey_object';
    }
    return detail.value?.survey_type === 'gully' ? 'survey_gully' : 'survey_road';
  });

  const surveyRows = computed(() => {
    const s = detail.value?.survey || surveyFromAdmin.value;
    if (!s) {
      return [];
    }
    // 优先按数据维护表格的列配置展示全部字段
    const cols = datasetCols.value[surveyDatasetKey.value];
    if (cols?.length) {
      return cols
        .filter((c) => s[c.col] != null && s[c.col] !== '')
        .map((c) => ({ key: c.col, label: c.label, value: s[c.col] }));
    }
    return Object.keys(SURVEY_LABELS)
      .filter((k) => s[k] != null && s[k] !== '')
      .map((k) => ({ key: k, label: SURVEY_LABELS[k], value: s[k] }));
  });

  const hasCoord = computed(() => {
    // 详情接口返回 lng/lat（geojson 里是 lgtd/lttd），两者都兜底
    const lng = Number(detail.value?.lng ?? detail.value?.lgtd);
    const lat = Number(detail.value?.lat ?? detail.value?.lttd);
    return Number.isFinite(lng) && Number.isFinite(lat);
  });

  function mediaList(v: any): string[] {
    return String(v || '')
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }

  const photos = computed(() => mediaList(detail.value?.photos));
  const videos = computed(() => mediaList(detail.value?.videos));

  /** 磁盘照片索引按编码关联：隐患点用 pid，防治对象用 obj_pid */
  const mediaCode = computed(() => String(detail.value?.pid || detail.value?.obj_pid || ''));

  watch(
    () => [props.open, props.item, props.tab, props.code, props.presetText, props.presetMeta],
    async () => {
      aiSeq += 1;
      aiText.value = props.presetText || '';
      aiMeta.value = props.presetMeta || '';
      aiLoading.value = false;
      if (!props.open || (!props.item && !props.code)) {
        if (!props.open) {
          detail.value = null;
        }
        return;
      }
      loading.value = true;
      detail.value = null;
      surveyFromAdmin.value = null;
      try {
        if (props.code) {
          detail.value = await getHazardByCode(props.code);
        } else {
          const id = String(props.item.id);
          detail.value = props.tab === 'prevention' ? await getPreventionDetail(id) : await getHazardDetail(id);
        }
      } catch {
        detail.value = props.code ? null : props.item;
      } finally {
        loading.value = false;
      }
      if (props.presetText) {
        aiText.value = props.presetText;
        aiMeta.value = props.presetMeta || '';
      }
      // 防治对象：详情接口未带调查成果时（旧后端），按政区编码从数据维护接口即时查询
      if (props.tab === 'prevention' && detail.value && !detail.value.survey) {
        await loadSurveyObject(String(detail.value.obj_pid || ''));
      }
    }
  );

  async function loadSurveyObject(objPid: string) {
    if (!objPid) {
      return;
    }
    try {
      const res = await getRows({ ds: 'survey_object', search: objPid, pageNo: 1, pageSize: 20 });
      const hit = (res?.data || []).find((r) => String(r.fcd) === String(objPid));
      surveyFromAdmin.value = hit || null;
    } catch {
      // 查询失败时忽略，仅展示基础信息
    }
  }

  function openLedger() {
    router.push({ path: '/super/airag/shreport/ShMapDataAdmin', query: { view: 'aiRisk' } });
  }

  function onDrawerClose() {
    if (aiLoading.value) {
      createMessage.warning('风险识别尚未完成，完成前不会关闭');
      return;
    }
    emit('update:open', false);
  }

  async function runAiRisk() {
    const id = String(detail.value?.id || props.item?.id || '');
    if (!id) {
      createMessage.warning('未找到隐患点');
      return;
    }
    const ticket = ++aiSeq;
    aiLoading.value = true;
    aiText.value = '';
    aiMeta.value = '';
    try {
      const res = await analyzeHazardRisk(id);
      if (ticket !== aiSeq) {
        return;
      }
      aiText.value = res?.text || '未返回识别结果';
      const parts: string[] = [];
      if (res?.degraded) {
        parts.push('照片未送入模型，仅根据文字判断');
      } else if (res?.photoCount) {
        parts.push(`参考了 ${res.photoCount} 张现场照片`);
      } else {
        parts.push('无现场照片');
      }
      parts.push(res?.usedComment ? '含外业备注' : '无外业备注');
      if (res?.modelName) {
        parts.push(res.modelName);
      }
      if (res?.logId) {
        parts.push('已记入台账');
      }
      aiMeta.value = parts.join(' · ');
    } catch (e: any) {
      if (ticket !== aiSeq) {
        return;
      }
      createMessage.warning(e?.message || '识别失败');
    } finally {
      if (ticket === aiSeq) {
        aiLoading.value = false;
      }
    }
  }

  function display(v: any) {
    if (v == null || v === '') {
      return '—';
    }
    if (Array.isArray(v)) {
      return v.join('、') || '—';
    }
    return String(v);
  }
</script>

<style lang="less" scoped>
  .empty,
  .hint,
  .ai-risk-hint,
  .ai-risk-meta {
    color: #94a3b8;
    font-size: 12px;
    margin-top: 12px;
  }

  .ai-risk-hint,
  .ai-risk-meta {
    margin-top: 0;
  }

  .ai-risk-meta {
    margin-top: 6px;
    color: #64748b;
  }

  .ai-risk-result {
    margin-top: 8px;
    padding: 4px 14px 12px;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 8px;

    :deep(.markdown-body) {
      background: transparent;
      font-size: 14px;
      color: #1e293b;
    }
  }

  .drawer-title {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    min-width: 0;
  }

  .drawer-title-text {
    min-width: 0;
  }
</style>
