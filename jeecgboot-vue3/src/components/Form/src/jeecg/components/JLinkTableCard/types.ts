/**
 * Online扩展配置类型（JLinkTableCard 本地副本）
 */
export type ExtConfigType = Partial<{
  // 是否启用联合查询（0否，1是）
  joinQuery: number,
  // 弹窗是否默认全屏（0否，1是）
  modelFullscreen: number,
  // 弹窗的最小宽度（px）
  modalMinWidth: string,
  // 是否固定操作列（0否，1是）
  tableFixedAction: number,
  // 操作列固定方式
  tableFixedActionType: 'left' | 'right',
  // 是否允许调整列表列宽
  canResizeColumn?: number,
  // Excel 图片导出方式
  imageExportMode?: 'embed' | 'path',
}>;
