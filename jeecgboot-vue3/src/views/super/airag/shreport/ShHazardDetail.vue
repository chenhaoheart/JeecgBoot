<template>
  <a-modal :open="open" :title="`隐患要素详情${record?.name ? ' - ' + record.name : ''}`" width="860px" :footer="null" class="hz-modal" @cancel="close">
    <a-spin :spinning="loading">
      <a-descriptions :column="2" size="small" bordered class="hz-desc">
        <a-descriptions-item label="隐患要素编码">{{ record?.pid || '—' }}</a-descriptions-item>
        <a-descriptions-item label="隐患类型">{{ record?.hz_type || '—' }}</a-descriptions-item>
        <a-descriptions-item label="河流名称">{{ record?.rvname || '—' }}</a-descriptions-item>
        <a-descriptions-item label="防护对象">{{ record?.fnm || '—' }}</a-descriptions-item>
        <a-descriptions-item label="小流域编码">{{ record?.wscd || '—' }}</a-descriptions-item>
        <a-descriptions-item label="调查年份">{{ record?.survey_year ?? '未标注' }}</a-descriptions-item>
        <a-descriptions-item label="经度">{{ hasCoord ? record?.lgtd : '—' }}</a-descriptions-item>
        <a-descriptions-item label="纬度">{{ hasCoord ? record?.lttd : '—' }}</a-descriptions-item>
      </a-descriptions>

      <!-- 现场照片/视频：磁盘索引按编码自动关联 + 人工上传（存 photos/videos 列） -->
      <ShMediaGallery
        :code="record?.pid"
        editable
        v-model:photos="media.photos"
        v-model:videos="media.videos"
        empty-hint="暂无照片（磁盘照片请先在「文件管理」中扫描索引）"
        @changed="saveMedia"
      />

      <ShSectionTitle>
        地图定位
        <a-tag v-if="!hasCoord" color="warning">无坐标，无法定位</a-tag>
      </ShSectionTitle>
      <ShLngLatPicker v-if="open && hasCoord" readonly :lng="Number(record?.lgtd)" :lat="Number(record?.lttd)" />
      <div v-else-if="open" class="hz-map-empty">该记录无坐标，无法在地图定位</div>
    </a-spin>
  </a-modal>
</template>

<script lang="ts" setup>
  import { computed, reactive, ref, watch } from 'vue';
  import { message } from 'ant-design-vue';
  import { updateRow } from './ShMapDataAdmin.api';
  import ShLngLatPicker from './ShLngLatPicker.vue';
  import ShMediaGallery from './ShMediaGallery.vue';
  import ShSectionTitle from './ShSectionTitle.vue';

  defineOptions({ name: 'ShMapHazardDetail' });

  const props = defineProps<{
    open: boolean;
    record: Recordable | null;
  }>();
  const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>();

  const loading = ref(false);
  const media = reactive<{ photos: string[]; videos: string[] }>({ photos: [], videos: [] });

  const hasCoord = computed(() => {
    const lng = Number(props.record?.lgtd);
    const lat = Number(props.record?.lttd);
    return Number.isFinite(lng) && Number.isFinite(lat) && lng !== 0 && lat !== 0;
  });

  function splitList(v: any): string[] {
    return String(v || '')
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }

  watch(
    () => props.open,
    (v) => {
      if (v) {
        media.photos = splitList(props.record?.photos);
        media.videos = splitList(props.record?.videos);
      }
    }
  );

  function close() {
    emit('update:open', false);
  }

  async function saveMedia() {
    if (!props.record?.id) {
      return;
    }
    loading.value = true;
    try {
      await updateRow('hazard', props.record.id, {
        photos: media.photos.join(','),
        videos: media.videos.join(','),
      });
      message.success('已保存');
    } finally {
      loading.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .hz-desc {
    margin-bottom: 16px;
  }

  .hz-map-empty {
    color: #94a3b8;
    font-size: 12px;
  }
</style>

<style lang="less">
  /* 详情弹窗内容整体留白（字段表/相册/地图不再贴边） */
  .hz-modal.ant-modal .ant-modal-body {
    padding: 20px 24px 24px;
  }
</style>
