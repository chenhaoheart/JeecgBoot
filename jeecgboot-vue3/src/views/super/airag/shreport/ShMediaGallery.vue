<template>
  <div class="media-gallery">
    <ShSectionTitle>
      现场照片
      <a-upload v-if="editable" accept="image/*" multiple :show-upload-list="false" :custom-request="uploadRequest('photos')">
        <a-button size="small" type="link"><UploadOutlined /> 上传照片</a-button>
      </a-upload>
      <span v-else class="sec-note">按编码自动关联磁盘文件（只读）</span>
    </ShSectionTitle>
    <div v-if="!photos.length" class="empty">{{ photosEmptyHint }}</div>
    <template v-else>
      <div v-for="grp in photoGroups" :key="grp.label" class="media-group">
        <div v-if="grp.label" class="group-label">{{ grp.label }}（{{ grp.items.length }}）</div>
        <div class="media-grid">
          <div v-for="p in grp.items" :key="p.key" class="media-item">
            <a-image
              :src="p.url"
              :width="132"
              :height="92"
              fit="cover"
              class="media-thumb"
              :preview="false"
              @click="openViewer(p)"
            />
            <span class="media-name" :title="p.name">{{ p.name }}</span>
            <a v-if="p.manual" class="media-del" @click="removeMedia('photos', p.index)">删除</a>
            <span v-else class="media-src">{{ p.category || '磁盘照片' }}</span>
          </div>
        </div>
      </div>
    </template>

    <ShSectionTitle>
      现场视频
      <a-upload v-if="editable" accept="video/*" multiple :show-upload-list="false" :custom-request="uploadRequest('videos')">
        <a-button size="small" type="link"><UploadOutlined /> 上传视频</a-button>
      </a-upload>
      <span v-else-if="videos.length" class="sec-note">按编码自动关联磁盘文件（只读）</span>
    </ShSectionTitle>
    <div v-if="!videos.length" class="empty">暂无视频</div>
    <div v-else class="media-grid">
      <div v-for="v in videos" :key="v.key" class="media-item">
        <video :src="v.url" controls preload="metadata" class="media-video"></video>
        <span class="media-name" :title="v.name">{{ v.name }}</span>
        <a v-if="v.manual" class="media-del" @click="removeMedia('videos', v.index)">删除</a>
        <span v-else class="media-src">{{ v.category || '磁盘视频' }}</span>
      </div>
    </div>

    <!-- 照片查看器：全屏预览（无弹窗尺寸计算，不会抖动） -->
    <Teleport to="body">
      <div v-if="viewerOpen" class="viewer-overlay" @wheel.prevent="onWheel" @dblclick="onDblClick">
        <button class="viewer-close" title="关闭 (Esc)" @click="closeViewer">
          <CloseOutlined />
        </button>
        <button class="viewer-nav nav-prev" :disabled="viewerIndex <= 0" title="上一张 (←)" @click="nav(-1)">
          <LeftOutlined />
        </button>
        <img
          v-if="viewerItem"
          :src="viewerItem.url"
          class="viewer-img"
          :alt="viewerItem.name"
          :style="imgStyle"
          draggable="false"
          @mousedown.prevent="onPanStart"
        />
        <button class="viewer-nav nav-next" :disabled="viewerIndex >= photos.length - 1" title="下一张 (→)" @click="nav(1)">
          <RightOutlined />
        </button>
        <div v-if="viewerItem" class="viewer-toolbar">
          <span class="viewer-count">{{ viewerIndex + 1 }} / {{ photos.length }}</span>
          <span class="viewer-name" :title="viewerItem.name">{{ viewerItem.name }}</span>
          <span v-if="viewerItem.position === '上'" class="pos-tag up">上游</span>
          <span v-else-if="viewerItem.position === '下'" class="pos-tag down">下游</span>
          <span v-if="viewerItem.category" class="viewer-cat">{{ viewerItem.category }}</span>
          <div class="viewer-zoom">
            <button class="t-btn" title="缩小" @click="zoom(-1)"><ZoomOutOutlined /></button>
            <span class="zoom-val">{{ Math.round(viewerScale * 100) }}%</span>
            <button class="t-btn" title="放大" @click="zoom(1)"><ZoomInOutlined /></button>
            <button class="t-btn" title="还原为原始大小（100%）" @click="resetView"><OneToOneOutlined /></button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onBeforeUnmount, ref, watch } from 'vue';
  import { message } from 'ant-design-vue';
  import { CloseOutlined, LeftOutlined, OneToOneOutlined, RightOutlined, UploadOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons-vue';
  import { getFileAccessHttpUrl } from '/@/utils/common/compUtils';
  import { filePreviewUrl, getFilesByPid, uploadCommonFile } from './ShMapDataAdmin.api';
  import ShSectionTitle from './ShSectionTitle.vue';

  defineOptions({ name: 'ShMediaGallery' });

  const props = defineProps<{
    /** 磁盘索引关联编码（隐患点 pid / 调查成果编码，文件夹名） */
    code?: string | null;
    /** 可编辑：允许上传/删除人工媒体（存调用方的 photos/videos 列） */
    editable?: boolean;
    photos?: string[];
    videos?: string[];
    emptyHint?: string;
  }>();
  const emit = defineEmits<{
    (e: 'update:photos', v: string[]): void;
    (e: 'update:videos', v: string[]): void;
    (e: 'changed'): void;
  }>();

  const diskFiles = ref<Recordable[]>([]);

  interface MediaItem {
    key: string;
    url: string;
    /** 显示用文件名 */
    name: string;
    /** 人工上传才可删除；磁盘索引文件只读 */
    manual: boolean;
    index: number;
    category?: string;
    /** 磁盘文件按文件名解析的上下游：上/下 */
    position?: string;
  }

  function fileNameOf(pathOrName: string, fallback: string): string {
    const tail = String(pathOrName || '').split(/[\\/]/).pop() || '';
    return tail || fallback;
  }

  function manualItems(list: string[] | undefined, kind: string): MediaItem[] {
    return (list || []).map((p, i) => ({
      key: `m-${kind}-${i}`,
      url: getFileAccessHttpUrl(p),
      name: fileNameOf(p, '上传文件'),
      manual: true,
      index: i,
    }));
  }

  function diskItems(kind: string): MediaItem[] {
    return diskFiles.value
      .filter((f) => f.file_type === kind)
      .map((f) => ({
        key: `d-${kind}-${f.id}`,
        url: filePreviewUrl(f.relative_path),
        name: fileNameOf(f.file_name, f.relative_path || '磁盘文件'),
        manual: false,
        index: f.id,
        category: f.category,
        position: f.file_position,
      }));
  }

  const photos = computed<MediaItem[]>(() => [...manualItems(props.photos, 'photos'), ...diskItems('photo')]);
  const videos = computed<MediaItem[]>(() => [...manualItems(props.videos, 'videos'), ...diskItems('video')]);

  const photosEmptyHint = computed(() => props.emptyHint || '暂无照片');

  /** 照片按 上游/下游 分组（文件名含「上/下」解析），人工上传归「其他」段 */
  function groupByPosition(items: MediaItem[]): { label: string; items: MediaItem[] }[] {
    const up = items.filter((it) => it.position === '上');
    const down = items.filter((it) => it.position === '下');
    const other = items.filter((it) => it.position !== '上' && it.position !== '下');
    const out: { label: string; items: MediaItem[] }[] = [];
    if (up.length) {
      out.push({ label: '上游', items: up });
    }
    if (down.length) {
      out.push({ label: '下游', items: down });
    }
    if (other.length) {
      out.push({ label: up.length || down.length ? '其他' : '', items: other });
    }
    return out;
  }

  const photoGroups = computed(() => groupByPosition(photos.value));

  /** 拉取磁盘索引中关联到该编码的照片/视频 */
  async function loadDiskFiles() {
    diskFiles.value = [];
    if (!props.code) {
      return;
    }
    try {
      diskFiles.value = (await getFilesByPid(String(props.code))) || [];
    } catch {
      // 未扫描索引或接口异常时仅展示人工上传的媒体
    }
  }

  watch(
    () => props.code,
    () => loadDiskFiles(),
    { immediate: true }
  );

  function uploadRequest(kind: 'photos' | 'videos') {
    return async (opt: any) => {
      try {
        const res = await uploadCommonFile(opt.file as File);
        const path = res?.message || res?.result || (typeof res === 'string' ? res : '');
        if (!path) {
          throw new Error('上传返回为空');
        }
        const next = [...(kind === 'photos' ? props.photos || [] : props.videos || []), path];
        emit(kind === 'photos' ? 'update:photos' : 'update:videos', next);
        emit('changed');
        opt.onSuccess?.(res);
      } catch (e: any) {
        opt.onError?.(e);
        message.error(e?.message || '上传失败');
      }
    };
  }

  function removeMedia(kind: 'photos' | 'videos', index: number) {
    const next = [...(kind === 'photos' ? props.photos || [] : props.videos || [])];
    next.splice(index, 1);
    emit(kind === 'photos' ? 'update:photos' : 'update:videos', next);
    emit('changed');
  }

  // ---------------------------------------------------------------- 照片查看器（全屏浮层：上/下一张 + 缩放 + 平移）

  const viewerOpen = ref(false);
  const viewerKey = ref<string | null>(null);
  const viewerScale = ref(1);
  const viewerTx = ref(0);
  const viewerTy = ref(0);
  let panning = false;
  let panX = 0;
  let panY = 0;

  const viewerIndex = computed(() => photos.value.findIndex((p) => p.key === viewerKey.value));
  const viewerItem = computed(() => (viewerIndex.value >= 0 ? photos.value[viewerIndex.value] : null));

  const imgStyle = computed(() => ({
    transform: `translate(${viewerTx.value}px, ${viewerTy.value}px) scale(${viewerScale.value})`,
    cursor: viewerScale.value > 1 ? (panning ? 'grabbing' : 'grab') : 'zoom-in',
    transition: panning ? 'none' : 'transform 0.15s ease',
  }));

  function resetView() {
    viewerScale.value = 1;
    viewerTx.value = 0;
    viewerTy.value = 0;
  }

  function zoom(step: number) {
    viewerScale.value = Math.min(8, Math.max(0.5, Math.round((viewerScale.value + step * 0.25) * 100) / 100));
  }

  function onWheel(e: WheelEvent) {
    zoom(e.deltaY > 0 ? -1 : 1);
  }

  function onDblClick() {
    if (viewerScale.value > 1) {
      resetView();
    } else {
      viewerScale.value = 2.5;
    }
  }

  function onPanStart(e: MouseEvent) {
    if (viewerScale.value <= 1) {
      return;
    }
    panning = true;
    panX = e.clientX - viewerTx.value;
    panY = e.clientY - viewerTy.value;
    window.addEventListener('mousemove', onPanMove);
    window.addEventListener('mouseup', onPanEnd);
  }

  function onPanMove(e: MouseEvent) {
    if (!panning) {
      return;
    }
    viewerTx.value = e.clientX - panX;
    viewerTy.value = e.clientY - panY;
  }

  function onPanEnd() {
    panning = false;
    window.removeEventListener('mousemove', onPanMove);
    window.removeEventListener('mouseup', onPanEnd);
  }

  function openViewer(p: MediaItem) {
    viewerKey.value = p.key;
    resetView();
    viewerOpen.value = true;
  }

  function closeViewer() {
    viewerOpen.value = false;
    viewerKey.value = null;
    resetView();
  }

  function nav(step: number) {
    const i = viewerIndex.value + step;
    if (i >= 0 && i < photos.value.length) {
      viewerKey.value = photos.value[i].key;
      resetView();
    }
  }

  function onKeydown(e: KeyboardEvent) {
    if (!viewerOpen.value) {
      return;
    }
    if (e.key === 'ArrowLeft') {
      nav(-1);
    } else if (e.key === 'ArrowRight') {
      nav(1);
    } else if (e.key === 'Escape') {
      closeViewer();
    }
  }

  watch(viewerOpen, (v) => {
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
</script>

<style lang="less" scoped>
  .media-gallery {
    .empty {
      color: #94a3b8;
      font-size: 12px;
    }

    .media-group {
      margin-bottom: 10px;

      .group-label {
        font-size: 12px;
        font-weight: 600;
        color: #475467;
        margin-bottom: 6px;
      }
    }

    .media-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;

      .media-item {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 4px;

        .media-thumb {
          border-radius: 6px;
          overflow: hidden;

          :deep(.ant-image-img) {
            width: 132px;
            height: 92px;
            object-fit: cover;
          }
        }

        .media-video {
          width: 200px;
          height: 120px;
          border-radius: 6px;
          background: #000;
        }

        .media-del {
          font-size: 12px;
          color: #ff4d4f;
        }

        .media-src {
          font-size: 11px;
          color: #98a2b3;
          max-width: 132px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }

    .media-name {
      font-size: 11px;
      color: #344054;
      max-width: 132px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  /* 全屏查看器（Teleport 到 body）：固定满屏，无窗口尺寸计算，切换不抖动 */
  .viewer-overlay {
    position: fixed;
    inset: 0;
    z-index: 2000;
    background: rgba(13, 18, 22, 0.96);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;

    .viewer-img {
      max-width: calc(100vw - 160px);
      max-height: calc(100vh - 110px);
      object-fit: contain;
      display: block;
      user-select: none;
    }

    .viewer-close,
    .viewer-nav {
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

    .viewer-close {
      top: 18px;
      right: 22px;
      width: 40px;
      height: 40px;
      font-size: 16px;
    }

    .viewer-nav {
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

    .viewer-toolbar {
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

      .viewer-count {
        color: #6cb3ff;
        font-weight: 600;
      }

      .viewer-name {
        max-width: 260px;
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

      .viewer-cat {
        color: #9aa4af;
        font-size: 12px;
      }

      .viewer-zoom {
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
