package org.jeecg.modules.airag.shreport;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山洪报告按「批次+流域」的生成互斥锁。
 * <p>
 * 章节落库是先删后写，同一批次同一流域若同时跑两个生成任务（例如两个浏览器标签页
 * 对同一会话各点一次生成、或对话生成进行中又点单章重新生成），章节内容会交错覆盖。
 * 该锁保证同一 batch_no + lybm 同时只有一个生成任务在执行。
 * <p>
 * Redis setIfAbsent 原子加锁（持锁人写入随机 token，解锁时校验避免误删他人锁），
 * 持有上限 {@link #LOCK_TTL_SECONDS}（防 JVM 崩溃后死锁），Redis 不可用时降级为本机内存锁。
 * <p>
 * 注意：降级本机锁只在单实例部署下互斥有效。多实例部署时必须保证 Redis 可用
 * （或改用数据库锁），否则不同实例可能对同一批次同时生成、章节互相覆盖。
 */
@Slf4j
@Component
public class ShReportBatchLock {

    private static final String KEY_PREFIX = "shreport:batchlock:";
    /** 持锁上限：整份生成（含排队重试）耗时远小于该值，仅作 JVM 崩溃兜底 */
    private static final long LOCK_TTL_SECONDS = 4 * 3600L;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** Redis 不可用时的本机兜底锁：key -> token */
    private final ConcurrentHashMap<String, String> local = new ConcurrentHashMap<>();

    private String key(String batchNo, String lybm) {
        return KEY_PREFIX + batchNo + "|" + lybm;
    }

    /** 尝试加锁：成功返回 token（用于解锁），失败返回 null（该批次+流域已有任务在跑） */
    public String tryLock(String batchNo, String lybm) {
        String key = key(batchNo, lybm);
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(LOCK_TTL_SECONDS));
            if (Boolean.TRUE.equals(ok)) {
                return token;
            }
            return null;
        } catch (Exception e) {
            log.warn("批次锁 Redis 不可用，降级本机锁 {}: {}", key, e.getMessage());
            String prev = local.putIfAbsent(key, token);
            return prev == null ? token : null;
        }
    }

    /** 解锁：仅当 token 匹配（自己持有）时删除 */
    public void unlock(String batchNo, String lybm, String token) {
        if (token == null) {
            return;
        }
        String key = key(batchNo, lybm);
        try {
            Object cur = redisTemplate.opsForValue().get(key);
            if (token.equals(String.valueOf(cur))) {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("批次锁 Redis 释放失败 {}: {}", key, e.getMessage());
        }
        local.remove(key, token);
    }

    /** 是否已有任务持锁（用于接口的同步预检，给用户即时反馈） */
    public boolean isLocked(String batchNo, String lybm) {
        String key = key(batchNo, lybm);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            return local.containsKey(key);
        }
    }

    /** 锁的 Redis key 与本机 Map 视图（运维排查用） */
    public Map<String, String> localView() {
        return local;
    }

    /**
     * 启动清理：生成线程随 JVM 存活，服务重启后不可能再有本机任务持锁，
     * 但 Redis 锁不受重启影响（TTL 长达数小时），不清掉会导致重启后该批次永远无法再次生成。
     */
    public int clearAll() {
        int n = 0;
        try {
            java.util.Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                Long removed = redisTemplate.delete(keys);
                n = removed == null ? 0 : removed.intValue();
            }
        } catch (Exception e) {
            log.warn("批次锁 Redis 清理失败: {}", e.getMessage());
        }
        n += local.size();
        local.clear();
        return n;
    }
}
