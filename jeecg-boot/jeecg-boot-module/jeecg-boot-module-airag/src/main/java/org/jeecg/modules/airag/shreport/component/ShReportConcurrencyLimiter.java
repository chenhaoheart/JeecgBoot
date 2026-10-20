package org.jeecg.modules.airag.shreport.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 山洪报告生成并发闸门。
 * <p>
 * 大模型处理一份章节通常需要数十秒甚至数分钟，并发过高会导致模型侧排队超时
 * （表现为「当前用户较多，排队中，请稍后再试！」）。这里统一控制两个维度：
 * <ol>
 *   <li>同时执行的报告任务数（整份 / 单章）：{@code shreport.concurrency.max-running-tasks}</li>
 *   <li>同时调用大模型的章节数：{@code shreport.concurrency.max-llm-calls}</li>
 * </ol>
 * 两个维度都按全局（单实例）计数，跨入口生效：AI 助手对话、章节重新生成、批量生成。
 */
@Slf4j
@Component
public class ShReportConcurrencyLimiter {

    private final Semaphore runPermits;
    private final Semaphore llmPermits;
    private final int maxRunningTasks;
    private final int maxLlmCalls;
    private final long llmWaitMillis;

    /**
     * 同一线程内嵌套调用（直连 LLM 失败后回退 LiteFlow）不重复占用大模型额度，避免自锁。
     */
    private final ThreadLocal<Integer> heldLlm = ThreadLocal.withInitial(() -> 0);

    public ShReportConcurrencyLimiter(
            @Value("${shreport.concurrency.max-running-tasks:2}") int maxRunningTasks,
            @Value("${shreport.concurrency.max-llm-calls:2}") int maxLlmCalls,
            @Value("${shreport.concurrency.llm-wait-seconds:600}") long llmWaitSeconds) {
        this.maxRunningTasks = Math.max(1, maxRunningTasks);
        this.maxLlmCalls = Math.max(1, maxLlmCalls);
        this.llmWaitMillis = Math.max(30, llmWaitSeconds) * 1000L;
        // 公平模式：先到的任务先拿到额度，避免后到的请求把排队中的请求饿死
        this.runPermits = new Semaphore(this.maxRunningTasks, true);
        this.llmPermits = new Semaphore(this.maxLlmCalls, true);
        log.info("山洪报告并发闸门已启用：max-running-tasks={}, max-llm-calls={}, llm-wait={}s",
                this.maxRunningTasks, this.maxLlmCalls, this.llmWaitMillis / 1000);
    }

    public int maxRunningTasks() {
        return maxRunningTasks;
    }

    public int maxLlmCalls() {
        return maxLlmCalls;
    }

    public int runningTasks() {
        return maxRunningTasks - runPermits.availablePermits();
    }

    /** 非阻塞占用一个报告任务名额：拿不到立即返回 false，由调用方给用户友好提示 */
    public boolean tryStartTask() {
        return tryStartTask(0);
    }

    /**
     * 占用一个报告任务名额。
     *
     * @param waitMillis 等待毫秒数，&lt;=0 表示不等待
     */
    public boolean tryStartTask(long waitMillis) {
        try {
            boolean ok = waitMillis > 0
                    ? runPermits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)
                    : runPermits.tryAcquire();
            if (ok) {
                log.info("报告生成任务开始占用名额，当前 {}/{}", runningTasks(), maxRunningTasks);
            } else {
                log.warn("报告生成任务未拿到名额（已满 {}/{}），本次请求被拒绝/排队超时", runningTasks(), maxRunningTasks);
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 归还报告任务名额 */
    public void finishTask() {
        runPermits.release();
        log.info("报告生成任务释放名额，当前 {}/{}", runningTasks(), maxRunningTasks);
    }

    /**
     * 在大模型并发额度内执行章节写作（直连 LLM 或 LiteFlow 流程）。
     *
     * @throws IllegalStateException 等待超时（模型持续繁忙）
     */
    public <T> T withLlmPermit(Supplier<T> supplier) {
        acquireLlmPermit();
        try {
            return supplier.get();
        } finally {
            releaseLlmPermit();
        }
    }

    private void acquireLlmPermit() {
        int held = heldLlm.get();
        if (held > 0) {
            // 嵌套调用：直连 LLM 失败后回退 LiteFlow，复用外层已拿到的额度
            heldLlm.set(held + 1);
            return;
        }
        boolean ok;
        try {
            ok = llmPermits.tryAcquire(llmWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        if (!ok) {
            throw new IllegalStateException("大模型繁忙，等待 " + (llmWaitMillis / 1000) + " 秒仍未获取到调用额度"
                    + "（并发上限 " + maxLlmCalls + "）");
        }
        heldLlm.set(1);
    }

    private void releaseLlmPermit() {
        int left = heldLlm.get() - 1;
        if (left <= 0) {
            heldLlm.remove();
            llmPermits.release();
        } else {
            heldLlm.set(left);
        }
    }

    /** 任务被拒绝时的友好提示语 */
    public String busyMessage() {
        return "当前已有 " + runningTasks() + " 个报告正在生成（并发上限 " + maxRunningTasks
                + "），大模型处理需要较长时间，请稍后再试。";
    }
}
