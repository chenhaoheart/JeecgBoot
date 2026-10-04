import { defHttp } from '/@/utils/http/axios';

export interface NameValue {
  name: string;
  value: number;
}

export interface ReportRecent {
  batchNo: string;
  lybm: string;
  lymc: string;
  chapters: number;
  passed: number;
  pending: number;
  failed: number;
  updateTime: string;
}

export interface KnowledgeBase {
  name: string;
  status: string;
  docCount: number;
}

export interface AiBoardOverview {
  report: {
    reportCount: number;
    watershedCount: number;
    chapterCount: number;
    byStatus: NameValue[];
    recent: ReportRecent[];
  };
  knowledge: {
    baseCount: number;
    docCount: number;
    bases: KnowledgeBase[];
  };
  map: {
    watershedCount: number;
    sectionCount: number;
    preventionCount: number;
    hazardCount: number;
    crossPointCount?: number;
    longPointCount?: number;
    surveyObjectCount?: number;
    sectionByType?: Record<string, number>;
    preventionByType?: Record<string, number>;
    hazardByType?: Record<string, number>;
  };
  aiRisk: {
    total: number;
    success: number;
    fail: number;
  };
}

export const getAiBoardOverview = () => defHttp.get<AiBoardOverview>({ url: '/sh/report/dashboard/overview' });
