package org.jeecg.modules.airag.shreport.exception;

/**
 * 同一批次+流域已有生成任务在跑时抛出。
 * 调用方据此区分「被互斥锁拒绝」与真正的生成失败：
 * 对话入口把消息透传给用户；重新生成接口只记日志，不能用失败占位覆盖已有章节内容。
 */
public class ShReportBatchBusyException extends RuntimeException {

    public ShReportBatchBusyException(String message) {
        super(message);
    }
}
