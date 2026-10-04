<template>
  <aside class="risk-side">
    <div class="search">
      <a-input v-model:value="keyword" size="small" allow-clear placeholder="搜索名称 / 河流 / 流域" @press-enter="reload" />
      <a-select
        v-if="tab === 'hazard'"
        v-model:value="hzTypes"
        size="small"
        mode="multiple"
        allow-clear
        :max-tag-count="2"
        :options="(hazardTypes || []).map((t) => ({ label: t, value: t }))"
        placeholder="隐患类型：全部"
        class="type-select"
        @change="onTypeChange"
      />
    </div>
    <div class="list">
      <a-spin :spinning="loading">
        <div v-if="!rows.length && !loading" class="empty">暂无数据</div>
        <div
          v-for="(item, index) in rows"
          :key="item.id"
          class="card"
          title="点击定位并弹出详情"
          @click="emit('open', tab, item)"
        >
          <span class="seq">{{ (pageNo - 1) * pageSize + index + 1 }}</span>
          <div class="card-main">
            <div class="card-title">
              <span class="name">{{
                tab === 'hazard' ? item.name || item.fnm || '未命名' : item.fnm || item.name || '未命名'
              }}</span>
              <span class="tag" :style="{ background: typeColor(tab, item.ftp || item.type_label || item.types || '') }">{{
                item.ftp || item.type_label || item.types || ''
              }}</span>
            </div>
            <div class="card-meta">
              <span v-if="item.wsnm">{{ item.wsnm }}</span>
              <span v-if="item.town_name">{{ item.town_name }}</span>
              <span v-if="item.pcount">人口 {{ item.pcount }}</span>
            </div>
          </div>
        </div>
      </a-spin>
    </div>
    <div class="pager">
      <a-pagination size="small" :current="pageNo" :page-size="pageSize" :total="total" :show-size-changer="false" @change="onPage" />
    </div>
  </aside>
</template>

<script lang="ts" setup>
  import { ref, watch } from 'vue';
  import { getHazardList, getPreventionList } from '../ShOneMap.api';
  import { typeColor } from '../mapStyles';

  defineOptions({ name: 'ShOneMapRiskSidePanel' });

  const props = defineProps<{
    wscd?: string;
    adcd?: string;
    /** 隐患类型清单（来自图层统计），供隐患要素 tab 的类型筛选 */
    hazardTypes?: string[];
  }>();
  const emit = defineEmits<{
    (e: 'open', tab: 'prevention' | 'hazard', item: any): void;
  }>();

  // tab 状态提升到面板表头（与「对象列表」标题合并成一行），通过 v-model:tab 双向绑定
  const tab = defineModel<'prevention' | 'hazard'>('tab', { default: 'prevention' });
  const keyword = ref('');
  const hzTypes = ref<string[]>([]);
  const pageNo = ref(1);
  const pageSize = 10;
  const total = ref(0);
  const rows = ref<any[]>([]);
  const loading = ref(false);

  watch([tab, () => props.wscd, () => props.adcd, keyword], () => {
    pageNo.value = 1;
    reload();
  });

  function onTypeChange() {
    pageNo.value = 1;
    reload();
  }

  async function reload() {
    loading.value = true;
    try {
      const params: any = {
        pageNo: pageNo.value,
        pageSize,
        search: keyword.value || undefined,
        wscd: props.wscd || undefined,
        adcd: props.adcd || undefined,
      };
      if (tab.value === 'hazard' && hzTypes.value.length) {
        params.types = hzTypes.value.join(',');
      }
      const res = tab.value === 'prevention' ? await getPreventionList(params) : await getHazardList(params);
      rows.value = res?.data || [];
      total.value = res?.total || 0;
    } catch {
      rows.value = [];
      total.value = 0;
    } finally {
      loading.value = false;
    }
  }

  function onPage(p: number) {
    pageNo.value = p;
    reload();
  }

  reload();

  defineExpose({ reload });
</script>

<style lang="less" scoped>
  .risk-side {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    background: transparent;

    .search {
      padding: 8px 10px;

      .type-select {
        width: 100%;
        margin-top: 6px;
      }
    }

    .list {
      flex: 1;
      overflow: auto;
      padding: 0 10px 8px;
    }

    .empty {
      text-align: center;
      color: #94a3b8;
      padding: 24px 0;
      font-size: 12px;
    }

    .card {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      border: 1px solid #eef1f6;
      border-radius: 8px;
      padding: 8px 10px;
      margin-bottom: 8px;
      cursor: pointer;
      transition: all 0.2s;

      &:hover {
        border-color: #bfdbfe;
        background: #f8fbff;
      }

      .seq {
        flex: none;
        min-width: 20px;
        height: 18px;
        margin-top: 1px;
        padding: 0 4px;
        border-radius: 4px;
        background: #f1f5f9;
        color: #475569;
        font-size: 12px;
        font-weight: 600;
        line-height: 18px;
        text-align: center;
      }

      .card-main {
        flex: 1;
        min-width: 0;
      }

      .card-title {
        display: flex;
        align-items: center;
        gap: 6px;

        .name {
          flex: 1;
          font-size: 13px;
          font-weight: 600;
          color: #0f172a;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .tag {
          flex: none;
          font-size: 11px;
          color: #fff;
          border-radius: 10px;
          padding: 0 6px;
          line-height: 18px;
        }
      }

      .card-meta {
        margin-top: 4px;
        font-size: 12px;
        color: #64748b;
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
      }
    }

    .pager {
      padding: 8px 10px 10px;
      border-top: 1px solid #eef1f6;
    }
  }
</style>
