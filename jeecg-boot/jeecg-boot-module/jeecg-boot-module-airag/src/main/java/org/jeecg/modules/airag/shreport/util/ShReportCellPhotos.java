package org.jeecg.modules.airag.shreport.util;

import org.jeecg.modules.airag.onemap.service.ShFileObjectService;

import java.util.HashMap;
import java.util.Map;

/**
 * 把一张图照片索引接到 Word 表格单元格：同一编码在一次导出里只查一次。
 */
public final class ShReportCellPhotos {

    private ShReportCellPhotos() {
    }

    public static ReportWordBuilder.CellPhotoResolver resolver(ShFileObjectService files) {
        Map<String, ReportWordBuilder.CellPhoto> cache = new HashMap<>();
        return code -> {
            if (code == null || code.isBlank() || files == null) {
                return null;
            }
            String key = code.trim();
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            ShFileObjectService.PickedPhoto picked = files.pickPhoto(key);
            ReportWordBuilder.CellPhoto photo = picked == null ? null
                    : new ReportWordBuilder.CellPhoto(picked.bytes(), picked.pictureType(), picked.fileName());
            cache.put(key, photo);
            return photo;
        };
    }
}
