<template>
  <div class="file-panel">
    <div class="fp-toolbar">
      <div class="fp-left">
        <a-select v-model:value="filters.category" :options="categoryOptions" style="width: 160px" size="small" allowClear placeholder="类别" @change="reload" />
        <a-select v-model:value="filters.filePosition" style="width: 110px" size="small" allowClear placeholder="位置" @change="reload">
          <a-select-option value="上">上游</a-select-option>
          <a-select-option value="下">下游</a-select-option>
        </a-select>
        <a-select v-model:value="filters.matched" style="width: 150px" size="small" allowClear placeholder="关联状态" @change="reload">
          <a-select-option :value="true">已关联隐患点</a-select-option>
          <a-select-option :value="false">未关联</a-select-option>
        </a-select>
        <a-select v-model:value="filters.wsnm" :options="wsnmOptions" style="width: 190px" size="small" allowClear placeholder="所属流域" showSearch optionFilterProp="label" @change="reload" />
        <a-input-search v-model:value="filters.search" placeholder="文件名 / 隐患点编码" style="width: 200px" size="small" allowClear @search="reload" />
      </div>
      <div class="fp-right">
        <a-button size="small" type="primary" :loading="scanning" @click="onScan">
          <ReloadOutlined /> 扫描索引
        </a-button>
      </div>
    </div>
    <div class="fp-tip" v-if="scanResult">
      最近扫描：共 {{ scanResult.total }} 个文件，新增 {{ scanResult.inserted }}，更新 {{ scanResult.updated }}；
      文件夹名能匹配库内隐患点的 {{ scanResult.matchedPids ?? 0 }} / {{ scanResult.allPids ?? 0 }} 个编码（覆盖 {{ scanResult.matchedFiles ?? 0 }} 个文件）。
      <span v-if="scanResult.errors?.length" class="fp-err">部分文件登记失败：{{ scanResult.errors[0] }}</span>
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
        <template v-if="column.key === 'thumb'">
          <a-image
            v-if="record.file_type === 'photo'"
            :src="filePreviewUrl(record.relative_path)"
            :width="64"
            :height="44"
            fit="cover"
            class="fp-thumb"
          />
          <span v-else class="fp-video-badge">视频</span>
        </template>
        <template v-else-if="column.key === 'action'">
          <a @click.stop="openPreview(record)">查看</a>
          <a-divider type="vertical" />
          <a @click.stop="openBind(record)">{{ record.pid ? '改绑' : '绑定' }}</a>
        </template>
      </template>
    </a-table>

    <!-- 大图/视频预览：全屏浮层（上一张/下一张 + 缩放，无窗口尺寸计算） -->
    <Teleport to="body">
      <div v-if="previewOpen" class="fp-overlay" @wheel.prevent="onWheel">
        <button class="fp-close" title="关闭 (Esc)" @click="closePreview">
          <CloseOutlined />
        </button>
        <button class="fp-nav nav-prev" :disabled="previewIndex <= 0" title="上一张 (←)" @click="navPreview(-1)">
          <LeftOutlined />
        </button>
        <video
          v-if="previewRecord?.file_type === 'video'"
          :src="filePreviewUrl(previewRecord.relative_path)"
          controls
          autoplay
          class="fp-media"
        ></video>
        <img
          v-else
          :src="filePreviewUrl(previewRecord?.relative_path)"
          class="fp-media fp-img"
          :style="imgStyle"
          draggable="false"
          @mousedown.prevent="onPanStart"
        />
        <button class="fp-nav nav-next" :disabled="previewIndex >= rows.length - 1" title="下一张 (→)" @click="navPreview(1)">
          <RightOutlined />
        </button>
        <div v-if="previewRecord" class="fp-toolbar">
          <span class="fp-count">{{ previewIndex + 1 }} / {{ rows.length }}</span>
          <span class="fp-name" :title="previewRecord.file_name">{{ previewRecord.file_name }}</span>
          <span v-if="previewRecord.file_position === '上'" class="pos-tag up">上游</span>
          <span v-else-if="previewRecord.file_position === '下'" class="pos-tag down">下游</span>
          <span v-if="previewRecord.category" class="fp-cat">{{ previewRecord.category }}</span>
          <span v-if="previewRecord.pid" class="fp-pid" :title="previewRecord.pid">{{ previewRecord.pid }}</span>
          <div v-if="previewRecord.file_type !== 'video'" class="fp-zoom">
            <button class="t-btn" title="缩小" @click="zoom(-1)"><ZoomOutOutlined /></button>
            <span class="zoom-val">{{ Math.round(scale * 100) }}%</span>
            <button class="t-btn" title="放大" @click="zoom(1)"><ZoomInOutlined /></button>
            <button class="t-btn" title="还原为原始大小（100%）" @click="resetView"><OneToOneOutlined /></button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 手动绑定 -->
    <a-modal
      :open="bindOpen"
      :title="`绑定隐患点（${bindRecord?.file_name}）`"
      width="480px"
      :confirm-loading="binding"
      ok-text="保存"
      cancel-text="取消"
      @ok="submitBind"
      @cancel="bindOpen = false"
    >
      <a-form layout="vertical">
        <a-form-item label="隐患点编码（文件夹名 = 县代码+河流代码+点号；留空解除绑定）">
          <a-input v-model:value="bindPid" placeholder="如 622926ADA3700121T000000A0001" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
  import { message } from 'ant-design-vue';
  import { CloseOutlined, LeftOutlined, OneToOneOutlined, ReloadOutlined, RightOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons-vue';
  import { bindFile, filePreviewUrl, getFileFacets, getFileList, scanFiles } from './ShMapDataAdmin.api';

  defineOptions({ name: 'ShFilePanel' });

  const rows = ref<Recordable[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const pageNo = ref(1);
  const pageSize = ref(20);
  const scanning = ref(false);
  const scanResult = ref<Recordable | null>(null);

  const facets = ref<{ categories: string[]; wsnms: string[] }>({ categories: [], wsnms: [] });
  const filters = reactive<Recordable>({ category: null, filePosition: null, matched: null, wsnm: null, search: '' });

  const categoryOptions = computed(() => facets.value.categories.map((c) => ({ label: c, value: c })));
  const wsnmOptions = computed(() => facets.value.wsnms.map((c) => ({ label: c, value: c })));

  const columns = [
    { title: '缩略', key: 'thumb', width: 84 },
    { title: '文件名', dataIndex: 'file_name', key: 'file_name', ellipsis: true },
    { title: '类别', dataIndex: 'category', key: 'category', width: 140, ellipsis: true },
    { title: '河流代码', dataIndex: 'river_code', key: 'river_code', width: 150, ellipsis: true },
    { title: '隐患点编码', dataIndex: 'pid', key: 'pid', width: 210, ellipsis: true },
    { title: '位置', key: 'position', width: 70 },
    { title: '流域', dataIndex: 'wsnm', key: 'wsnm', width: 180, ellipsis: true },
    { title: '操作', key: 'action', fixed: 'right', width: 110 },
  ];

  const pagination = computed(() => ({
    current: pageNo.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ['20', '50', '100'],
    showTotal: (t: number) => `共 ${t} 个文件`,
  }));

  async function load() {
    loading.value = true;
    try {
      const res = await getFileList({
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        category: filters.category || undefined,
        filePosition: filters.filePosition || undefined,
        matched: filters.matched ?? undefined,
        wsnm: filters.wsnm || undefined,
        search: filters.search || undefined,
      });
      rows.value = res?.data || [];
      total.value = res?.total || 0;
    } catch {
      rows.value = [];
      total.value = 0;
    } finally {
      loading.value = false;
    }
  }

  function reload() {
    pageNo.value = 1;
    load();
  }

  function onTableChange(p: any) {
    pageNo.value = p.current;
    pageSize.value = p.pageSize;
    load();
  }

  async function loadFacets() {
    try {
      const f = await getFileFacets();
      facets.value = { categories: f?.categories || [], wsnms: f?.wsnms || [] };
    } catch {
      // 未扫描前为空
    }
  }

  async function onScan() {
    scanning.value = true;
    try {
      scanResult.value = await scanFiles();
      message.success('扫描完成');
      await Promise.all([load(), loadFacets()]);
    } catch (e: any) {
      message.error(e?.message || '扫描失败');
    } finally {
      scanning.value = false;
    }
  }

  // ---------------------------------------------------------------- 预览（上一张/下一张）

  const previewOpen = ref(false);
  const previewRecord = ref<Recordable | null>(null);

  const previewIndex = computed(() => rows.value.findIndex((r) => r.id === previewRecord.value?.id));

  function openPreview(record: Recordable) {
    previewRecord.value = record;
    resetView();
    previewOpen.value = true;
  }

  function closePreview() {
    previewOpen.value = false;
    previewRecord.value = null;
    resetView();
  }

  function navPreview(step: number) {
    const i = previewIndex.value + step;
    if (i >= 0 && i < rows.value.length) {
      previewRecord.value = rows.value[i];
      resetView();
    }
  }

  // ---------------------------------------------------------------- 缩放 / 平移（全屏浮层）

  const scale = ref(1);
  const tx = ref(0);
  const ty = ref(0);
  let panning = false;
  let panX = 0;
  let panY = 0;

  const imgStyle = computed(() => ({
    transform: `translate(${tx.value}px, ${ty.value}px) scale(${scale.value})`,
    cursor: scale.value > 1 ? (panning ? 'grabbing' : 'grab') : 'zoom-in',
    transition: panning ? 'none' : 'transform 0.15s ease',
  }));

  function resetView() {
    scale.value = 1;
    tx.value = 0;
    ty.value = 0;
  }

  function zoom(step: number) {
    scale.value = Math.min(8, Math.max(0.5, Math.round((scale.value + step * 0.25) * 100) / 100));
  }

  function onWheel(e: WheelEvent) {
    zoom(e.deltaY > 0 ? -1 : 1);
  }

  function onPanStart(e: MouseEvent) {
    if (scale.value <= 1) {
      return;
    }
    panning = true;
    panX = e.clientX - tx.value;
    panY = e.clientY - ty.value;
    window.addEventListener('mousemove', onPanMove);
    window.addEventListener('mouseup', onPanEnd);
  }

  function onPanMove(e: MouseEvent) {
    if (!panning) {
      return;
    }
    tx.value = e.clientX - panX;
    ty.value = e.clientY - panY;
  }

  function onPanEnd() {
    panning = false;
    window.removeEventListener('mousemove', onPanMove);
    window.removeEventListener('mouseup', onPanEnd);
  }

  function onKeydown(e: KeyboardEvent) {
    if (!previewOpen.value) {
      return;
    }
    if (e.key === 'ArrowLeft') {
      navPreview(-1);
    } else if (e.key === 'ArrowRight') {
      navPreview(1);
    } else if (e.key === 'Escape') {
      closePreview();
    }
  }

  watch(previewOpen, (v) => {
    if (v) {
      window.addEventListener('keydown', onKeydown);
    } else {
      window.removeEventListener('keydown', onKeydown);
    }
  });

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown);
    onPanEnd();
  });

  // ---------------------------------------------------------------- 绑定

  const bindOpen = ref(false);
  const bindRecord = ref<Recordable | null>(null);
  const bindPid = ref('');
  const binding = ref(false);

  function openBind(record: Recordable) {
    bindRecord.value = record;
    bindPid.value = record.pid || '';
    bindOpen.value = true;
  }

  async function submitBind() {
    if (!bindRecord.value) {
      return;
    }
    binding.value = true;
    try {
      await bindFile(bindRecord.value.id, bindPid.value || null);
      message.success(bindPid.value ? '已绑定' : '已解除绑定');
      bindOpen.value = false;
      await load();
    } catch (e: any) {
      message.error(e?.message || '绑定失败');
    } finally {
      binding.value = false;
    }
  }

  onMounted(async () => {
    await Promise.all([load(), loadFacets()]);
  });
</script>

<style lang="less" scoped>
  .file-panel {
    .fp-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 10px;

      .fp-left,
      .fp-right {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
      }
    }

    .fp-tip {
      margin-bottom: 8px;
      font-size: 12px;
      color: #475467;

      .fp-err {
        color: #ff4d4f;
      }
    }

    .fp-thumb {
      border-radius: 4px;
      overflow: hidden;

      :deep(.ant-image-img) {
        width: 64px;
        height: 44px;
        object-fit: cover;
      }
    }

    .fp-video-badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      background: #fff1f0;
      color: #cf1322;
      font-size: 12px;
    }
  }

  /* 全屏预览（Teleport 到 body）：固定满屏，无窗口尺寸计算，切换不抖动 */
  .fp-overlay {
    position: fixed;
    inset: 0;
    z-index: 2000;
    background: rgba(13, 18, 22, 0.96);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;

    .fp-media {
      max-width: calc(100vw - 160px);
      max-height: calc(100vh - 110px);
      object-fit: contain;
      display: block;
    }

    .fp-img {
      user-select: none;
    }

    .fp-close,
    .fp-nav {
      position: absolute;
      z-index: 20;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: none;
      cursor: pointer;
      color: #fff;
      background: rgba(255, 255, 255, 0.16);
      border-radius: 50%;
      transition: background 0.2s;

      &:hover {
        background: rgba(255, 255, 255, 0.3);
      }

      &:disabled {
        opacity: 0.25;
        background: rgba(255, 255, 255, 0.1);
        cursor: default;
      }
    }

    .fp-close {
      top: 18px;
      right: 22px;
      width: 40px;
      height: 40px;
      font-size: 16px;
    }

    .fp-nav {
      top: 50%;
      transform: translateY(-50%);
      width: 46px;
      height: 46px;
      font-size: 18px;
    }

    .nav-prev {
      left: 26px;
    }

    .nav-next {
      right: 26px;
    }

    .fp-toolbar {
      position: absolute;
      bottom: 22px;
      left: 50%;
      transform: translateX(-50%);
      max-width: calc(100vw - 160px);
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 16px;
      background: rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      color: #e6e8eb;
      font-size: 13px;
      white-space: nowrap;

      .fp-count {
        color: #6cb3ff;
        font-weight: 600;
      }

      .fp-name {
        max-width: 220px;
        overflow: hidden;
        text-overflow: ellipsis;
        font-weight: 600;
      }

      .pos-tag {
        font-size: 12px;
        line-height: 18px;
        padding: 0 6px;
        border-radius: 4px;

        &.up {
          background: rgba(59, 130, 246, 0.25);
          color: #93c5fd;
        }

        &.down {
          background: rgba(34, 197, 94, 0.22);
          color: #86efac;
        }
      }

      .fp-cat,
      .fp-pid {
        color: #9aa4af;
        font-size: 12px;
      }

      .fp-pid {
        max-width: 230px;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .fp-zoom {
        display: flex;
        align-items: center;
        gap: 6px;
        margin-left: 4px;
        padding-left: 12px;
        border-left: 1px solid rgba(255, 255, 255, 0.18);

        .t-btn {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          width: 28px;
          height: 26px;
          border: none;
          border-radius: 6px;
          cursor: pointer;
          color: #e6e8eb;
          background: transparent;
          font-size: 14px;

          &:hover {
            background: rgba(255, 255, 255, 0.16);
          }
        }

        .zoom-val {
          min-width: 44px;
          text-align: center;
          font-size: 12px;
          color: #cdd3d9;
        }
      }
    }
  }
</style>
