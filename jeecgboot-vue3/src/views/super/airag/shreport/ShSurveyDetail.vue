<template>
  <a-modal :open="open" :title="`详情${dsLabel ? ' - ' + dsLabel : ''}${record?.name ? '（' + record.name + '）' : ''}`" width="1080px" :footer="null" class="sv-modal" @cancel="close">
    <a-descriptions :column="2" size="small" bordered class="sv-desc">
      <a-descriptions-item v-for="c in fieldCols" :key="c.col" :label="c.label">
        {{ display(record?.[c.col]) }}
      </a-descriptions-item>
      <a-descriptions-item label="调查年份">{{ record?.survey_year ?? '未标注' }}</a-descriptions-item>
      <a-descriptions-item v-if="record?.comments" label="备注" :span="2">
        {{ display(record?.comments) }}
      </a-descriptions-item>
    </a-descriptions>

    <ShSectionTitle v-if="showMap">地图定位</ShSectionTitle>
    <ShLngLatPicker
      v-if="open && showMap"
      readonly
      :lng="Number(record?.lgtd)"
      :lat="Number(record?.lttd)"
      :extra-geojson="assocGeom"
      extra-label="防治对象面"
    />
    <div v-if="assocMissing && !hasCoord" class="assoc-missing">
      该记录无经纬度，且未在「防治对象（面）」图层中找到与防护对象代码（{{ record?.fcd || record?.code }}）匹配的空间面
    </div>
    <div v-if="assocMissing" class="assoc-missing">未在「防治对象（面）」图层中找到与该防护对象代码（{{ record?.fcd }}）匹配的空间面</div>

    <!-- 现场照片/视频：磁盘索引按「编码/防护对象代码」自动关联（只读） -->
    <ShMediaGallery :code="record?.code || record?.fcd" />
  </a-modal>
</template>

<script lang="ts" setup>
  import { computed, ref, watch } from 'vue';
  import ShLngLatPicker from './ShLngLatPicker.vue';
  import ShMediaGallery from './ShMediaGallery.vue';
  import ShSectionTitle from './ShSectionTitle.vue';
  import { getGeomByPid, type IColConf } from './ShMapDataAdmin.api';

  defineOptions({ name: 'ShSurveyDetail' });

  const props = defineProps<{
    open: boolean;
    record: Recordable | null;
    /** 数据集列配置，用于详情字段与中文标签 */
    columns: IColConf[];
    dsLabel?: string;
  }>();
  const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>();

  /** 名录类公共字段放前面，其余列按配置顺序；备注单独整行展示 */
  const fieldCols = computed(() => {
    const cols = (props.columns || []).filter((c) => c.col !== 'comments');
    const priority = ['name', 'code', 'stype', 'fnm', 'ftp', 'pcount', 'rvnm', 'rvcd', 'county_name', 'town_name', 'wsnm', 'wscd'];
    const seen = new Set<string>();
    const ordered: IColConf[] = [];
    for (const key of priority) {
      const hit = cols.find((c) => c.col === key);
      if (hit) {
        ordered.push(hit);
        seen.add(hit.col);
      }
    }
    for (const c of cols) {
      if (!seen.has(c.col)) {
        ordered.push(c);
      }
    }
    return ordered;
  });

  const hasCoord = computed(() => {
    const lng = Number(props.record?.lgtd);
    const lat = Number(props.record?.lttd);
    return Number.isFinite(lng) && Number.isFinite(lat) && lng !== 0 && lat !== 0;
  });

  /** 无经纬度但可按编码关联图层时，也显示地图 */
  const showMap = computed(() => hasCoord.value || !!props.record?.fcd || !!props.record?.code);

  /** 防治对象名录：按 15 位政区编码（防护对象代码 fcd）关联防治对象面几何；无 fcd 时退回编码列 */
  const assocGeom = ref<any>(null);
  const assocMissing = ref(false);

  async function loadAssocGeom() {
    assocGeom.value = null;
    assocMissing.value = false;
    const pid = props.record?.fcd || props.record?.code;
    if (!pid) {
      return;
    }
    try {
      const res = await getGeomByPid('prevention', String(pid));
      assocGeom.value = res?.geometry || null;
      assocMissing.value = !res?.geometry;
    } catch {
      // 接口异常时不展示关联图层
    }
  }

  watch(
    () => [props.open, props.record?.fcd, props.record?.code],
    ([v]) => {
      if (v) {
        loadAssocGeom();
      } else {
        assocGeom.value = null;
        assocMissing.value = false;
      }
    }
  );

  function display(v: any) {
    if (v == null || v === '') {
      return '—';
    }
    return String(v);
  }

  function close() {
    emit('update:open', false);
  }
</script>

<style lang="less" scoped>
  .sv-desc {
    margin-bottom: 16px;
  }

  .assoc-missing {
    margin-top: 8px;
    font-size: 12px;
    color: #d48806;
  }
</style>

<style lang="less">
  /* 详情弹窗内容整体留白（字段表/相册/地图不再贴边） */
  .sv-modal.ant-modal .ant-modal-body {
    padding: 20px 24px 24px;
  }
</style>
