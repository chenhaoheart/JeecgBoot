import type { AppRouteRecordRaw } from '/@/router/types';
import { LAYOUT } from '/@/router/constant';

export const AI_ROUTE: AppRouteRecordRaw = {
  path: '',
  name: 'ai-parent',
  component: LAYOUT,
  meta: {
    title: 'ai',
  },
  children: [
    {
      path: '/ai',
      name: 'ai',
      component: () => import('/@/views/dashboard/ai/index.vue'),
      meta: {
        title: 'AI助手',
      },
    },
  ],
};

/** 菜单「隐患识别台账」已改到 AI 概览，明细页仍保留给概览里的入口 */
export const HAZARD_LOG_ROUTE: AppRouteRecordRaw = {
  path: '',
  name: 'hazard-ai-log-parent',
  component: LAYOUT,
  meta: {
    title: '隐患识别明细',
    hideMenu: true,
  },
  children: [
    {
      path: '/sh/hazard-ai-log',
      name: 'hazard-ai-log',
      component: () => import('/@/views/super/airag/shreport/ShHazardAiRiskLog.vue'),
      meta: {
        title: '隐患识别明细',
        hideMenu: true,
      },
    },
  ],
};

export const staticRoutesList = [AI_ROUTE, HAZARD_LOG_ROUTE];
