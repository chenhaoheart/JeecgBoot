<template>
  <div class="filter-bar">
    <div class="search-row">
      <a-input v-model:value="keyword" size="small" allow-clear :placeholder="filterMode === 'admin' ? '搜索县 / 乡镇' : '搜索流域'" />
      <a-button class="reset" size="small" @click="emit('reset')">重置</a-button>
    </div>
    <div class="pane">
      <div v-show="filterMode === 'admin'" class="tree-col">
        <a-tree
          :tree-data="treeData"
          :selected-keys="selectedKeys"
          :expanded-keys="expandedKeys"
          :auto-expand-parent="true"
          block-node
          @select="onTreeSelect"
          @expand="onExpand"
        />
      </div>
      <div v-show="filterMode === 'watershed'" class="tiles">
        <button
          v-for="w in filteredWs"
          :key="w.wscd"
          type="button"
          class="ws-tile"
          :class="{ on: w.wscd === wscd }"
          :title="w.wsnm"
          @click="onWatershed(w.wscd)"
        >
          <span class="nm">{{ w.wsnm }}</span>
          <span class="meta">{{ w.objectCount || 0 }} 对象</span>
        </button>
        <div v-if="!filteredWs.length" class="empty">无匹配流域</div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed, ref, watch } from 'vue';
  import type { IAdminOpt, IWatershedOpt } from '../ShOneMap.api';

  defineOptions({ name: 'ShOneMapFilterBar' });

  const props = defineProps<{
    watersheds: IWatershedOpt[];
    counties: IAdminOpt[];
    towns: IAdminOpt[];
    wscd?: string;
    countyAdcd?: string;
    townAdcd?: string;
  }>();

  const emit = defineEmits<{
    (e: 'update:wscd', v: string): void;
    (e: 'update:countyAdcd', v: string): void;
    (e: 'update:townAdcd', v: string): void;
    (e: 'reset'): void;
  }>();

  const keyword = ref('');
  const expandedKeys = ref<string[]>(['all']);
  // 政区/流域切换提升到「筛选」表头（与标题合并成一行），通过 v-model:filter-mode 双向绑定
  const filterMode = defineModel<'admin' | 'watershed'>('filterMode', { default: 'watershed' });

  const selectedKeys = computed(() => [props.townAdcd || props.countyAdcd || 'all']);

  const treeData = computed(() => {
    const kw = keyword.value.trim();
    const children = props.counties
      .map((c) => {
        const allTowns = props.towns.filter((t) => t.adcd.startsWith(c.adcd));
        const countyHit = !kw || c.name.includes(kw);
        const towns = countyHit
          ? allTowns.filter((t) => !kw || t.name.includes(kw) || c.name.includes(kw))
          : allTowns.filter((t) => t.name.includes(kw));
        if (kw && !countyHit && !towns.length) {
          return null;
        }
        return {
          title: c.name,
          key: c.adcd,
          children: towns.map((t) => ({ title: t.name, key: t.adcd })),
        };
      })
      .filter(Boolean);
    return [{ title: '全部', key: 'all', children }];
  });

  const filteredWs = computed(() => {
    const kw = keyword.value.trim();
    if (!kw) {
      return props.watersheds;
    }
    return props.watersheds.filter((w) => w.wsnm.includes(kw) || w.wscd.toLowerCase().includes(kw.toLowerCase()));
  });

  watch(
    () => props.wscd,
    (v) => {
      if (v) {
        filterMode.value = 'watershed';
      }
    }
  );

  watch(
    () => [props.countyAdcd, props.townAdcd],
    ([county, town]) => {
      if (county || town) {
        filterMode.value = 'admin';
        if (county && !expandedKeys.value.includes(county)) {
          expandedKeys.value = [...expandedKeys.value, 'all', county];
        }
      }
    }
  );

  /** 模式切换时清空当前维度的选中值（与原先 switchMode 行为一致） */
  watch(filterMode, (mode) => {
    keyword.value = '';
    if (mode === 'admin') {
      emit('update:wscd', '');
    } else {
      emit('update:countyAdcd', '');
      emit('update:townAdcd', '');
    }
  });

  function onExpand(keys: (string | number)[]) {
    expandedKeys.value = keys.map(String);
  }

  function onTreeSelect(keys: (string | number)[]) {
    emit('update:wscd', '');
    const key = String(keys[0] ?? 'all');
    if (key === 'all') {
      emit('update:countyAdcd', '');
      emit('update:townAdcd', '');
      return;
    }
    const town = props.towns.find((t) => t.adcd === key);
    if (town) {
      const parent = props.counties.find((c) => town.adcd.startsWith(c.adcd));
      emit('update:countyAdcd', parent?.adcd || '');
      emit('update:townAdcd', town.adcd);
      return;
    }
    emit('update:countyAdcd', key);
    emit('update:townAdcd', '');
  }

  function onWatershed(v: string) {
    emit('update:countyAdcd', '');
    emit('update:townAdcd', '');
    emit('update:wscd', v === props.wscd ? '' : v);
  }
</script>

<style lang="less" scoped>
  .filter-bar {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;

    .search-row {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 0 0 8px;
      flex: 0 0 auto;
    }

    .pane {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      border-top: 1px solid #eef1f6;
    }

    .tree-col {
      flex: 1;
      min-height: 0;
      overflow: auto;
      padding: 6px 4px 8px;

      :deep(.ant-tree) {
        font-size: 12px;
        background: transparent;
      }

      :deep(.ant-tree-treenode) {
        padding: 0 4px;
        width: 100%;
      }

      :deep(.ant-tree-node-content-wrapper) {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .tiles {
      flex: 1;
      min-height: 0;
      overflow: auto;
      padding: 8px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .ws-tile {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
      width: 100%;
      padding: 7px 8px;
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      background: #fff;
      cursor: pointer;
      text-align: left;

      &:hover {
        border-color: #bfdbfe;
      }

      &.on {
        border-color: #2563eb;
        background: #eff6ff;
      }

      .nm {
        max-width: 100%;
        font-size: 12px;
        font-weight: 600;
        color: #0f172a;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .meta {
        font-size: 11px;
        color: #94a3b8;
      }
    }

    .empty {
      padding: 16px 0;
      text-align: center;
      color: #94a3b8;
      font-size: 12px;
    }
  }
</style>
