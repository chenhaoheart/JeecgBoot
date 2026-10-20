<template>
  <div class="mat-panel">
    <div v-if="!boundWatershed?.lybm" class="mat-empty">请先绑定流域后再上传资料。</div>
    <template v-else>
      <div class="mat-toolbar">
        <a-upload :accept="materialAccept" :show-upload-list="false" :multiple="true" :custom-request="onUpload">
          <a-button type="primary" size="small" :loading="uploading">上传资料</a-button>
        </a-upload>
        <a-button size="small" :loading="loading" @click="reload">刷新</a-button>
        <span class="mat-hint">Excel / PDF / Word / 图片，单文件 ≤ 50MB。解析完成后才会写入章节。</span>
      </div>
      <div v-if="!rows.length && !loading" class="mat-empty">本流域还没有上传资料。</div>
      <div v-for="row in rows" :key="row.id" class="mat-item">
        <div class="mat-head">
          <span class="mat-name" :title="row.fileName">{{ row.fileName }}</span>
          <span class="mat-size">{{ sizeText(row.fileSize) }}</span>
          <span class="mat-st" :class="row.parseStatus">{{ statusText(row.parseStatus) }}</span>
        </div>
        <div v-if="row.failReason" class="mat-fail">{{ row.failReason }}</div>
        <div v-if="row.contentSummary" class="mat-sum" :title="row.contentSummary">{{ row.contentSummary }}</div>
        <div class="mat-tags">
          <a-select v-model:value="row.category" size="small" style="width: 140px" @change="saveRow(row)">
            <a-select-option value="基础数据">基础数据</a-select-option>
            <a-select-option value="历史山洪调查">历史山洪调查</a-select-option>
            <a-select-option value="现场照片图纸">现场照片图纸</a-select-option>
            <a-select-option value="其他">其他</a-select-option>
          </a-select>
          <a-checkbox-group :value="chaptersOf(row)" @change="(v) => onChapters(row, v)">
            <a-checkbox v-for="n in 7" :key="n" :value="String(n)">{{ n }}</a-checkbox>
          </a-checkbox-group>
          <a-checkbox :checked="!!row.dataOverride" @change="(e) => onOverride(row, e.target.checked)">以此为准</a-checkbox>
        </div>
        <div class="mat-actions">
          <a-button size="small" type="link" @click="preview(row)">预览</a-button>
          <a-button size="small" type="link" :loading="reparsing === row.id" @click="reparse(row)">重新解析</a-button>
          <a-button size="small" type="link" danger @click="remove(row)">删除</a-button>
        </div>
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
  import { onMounted, onUnmounted, ref, watch } from 'vue';
  import { message } from 'ant-design-vue';
  import {
    deleteMaterial,
    IMaterial,
    listMaterials,
    materialAccept,
    previewMaterialBlob,
    reparseMaterial,
    updateMaterial,
    uploadMaterial,
  } from '../ShReportAssistant.api';
  import { useReportSession } from '../useReportSession';

  defineOptions({ name: 'ShMaterialPanel' });

  const { conversationId, boundWatershed, batchNo } = useReportSession();
  const rows = ref<IMaterial[]>([]);
  const loading = ref(false);
  const uploading = ref(false);
  const reparsing = ref<number | null>(null);
  let timer: ReturnType<typeof setInterval> | null = null;

  async function reload() {
    if (!boundWatershed.value?.lybm) {
      rows.value = [];
      return;
    }
    loading.value = true;
    try {
      rows.value = (await listMaterials(conversationId.value, boundWatershed.value.lybm, batchNo.value)) || [];
    } catch {
      message.error('资料列表加载失败');
    } finally {
      loading.value = false;
    }
  }

  async function onUpload(req: any) {
    if (!boundWatershed.value?.lybm) {
      message.warning('请先绑定流域');
      req.onError?.(new Error('未绑定流域'));
      return;
    }
    uploading.value = true;
    try {
      await uploadMaterial(req.file, conversationId.value);
      req.onSuccess?.({});
      message.success('已上传，正在解析');
      await reload();
    } catch (e: any) {
      req.onError?.(e);
      message.error(e?.message || '上传失败');
    } finally {
      uploading.value = false;
    }
  }

  function chaptersOf(row: IMaterial) {
    return String(row.chapterNos || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
  }

  async function saveRow(row: IMaterial) {
    await updateMaterial({
      id: row.id,
      conversationId: conversationId.value,
      category: row.category,
      chapterNos: row.chapterNos,
      dataOverride: !!row.dataOverride,
    });
  }

  async function onChapters(row: IMaterial, vals: any) {
    row.chapterNos = (vals as string[]).sort().join(',');
    await saveRow(row);
  }

  async function onOverride(row: IMaterial, on: boolean) {
    row.dataOverride = on ? 1 : 0;
    await saveRow(row);
  }

  async function reparse(row: IMaterial) {
    reparsing.value = row.id;
    try {
      await reparseMaterial(row.id, conversationId.value);
      message.success('已提交重新解析');
      await reload();
    } catch {
      message.error('重新解析失败');
    } finally {
      reparsing.value = null;
    }
  }

  async function remove(row: IMaterial) {
    await deleteMaterial(row.id, conversationId.value);
    await reload();
  }

  async function preview(row: IMaterial) {
    try {
      const blob = (await previewMaterialBlob(row.id, conversationId.value)) as Blob;
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch {
      message.error('预览失败');
    }
  }

  function statusText(s?: string) {
    if (s === 'ready') return '已解析';
    if (s === 'parsing') return '解析中';
    if (s === 'failed') return '失败';
    return '等待解析';
  }

  function sizeText(n?: number) {
    if (!n) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  watch(
    () => [boundWatershed.value?.lybm, conversationId.value],
    () => reload()
  );

  onMounted(() => {
    reload();
    timer = setInterval(() => {
      if (rows.value.some((r) => r.parseStatus === 'pending' || r.parseStatus === 'parsing')) {
        reload();
      }
    }, 2500);
  });
  onUnmounted(() => {
    if (timer) clearInterval(timer);
  });
</script>

<style lang="less" scoped>
  .mat-panel {
    padding: 8px 4px 16px;
  }
  .mat-toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
    flex-wrap: wrap;
  }
  .mat-hint {
    color: #94a3b8;
    font-size: 12px;
  }
  .mat-empty {
    color: #94a3b8;
    font-size: 13px;
    padding: 24px 8px;
    text-align: center;
  }
  .mat-item {
    border: 1px solid #e2e8f0;
    border-radius: 8px;
    padding: 8px 10px;
    margin-bottom: 8px;
  }
  .mat-head {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .mat-name {
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
  }
  .mat-size {
    color: #94a3b8;
    font-size: 12px;
  }
  .mat-st {
    font-size: 12px;
    &.ready {
      color: #16a34a;
    }
    &.parsing,
    &.pending {
      color: #d97706;
    }
    &.failed {
      color: #dc2626;
    }
  }
  .mat-fail {
    color: #dc2626;
    font-size: 12px;
    margin-top: 4px;
  }
  .mat-sum {
    color: #64748b;
    font-size: 12px;
    margin-top: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .mat-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    margin-top: 8px;
  }
  .mat-actions {
    margin-top: 4px;
  }
</style>
