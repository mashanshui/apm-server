package com.shanshui.apmserver.anr.internal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/** 限量唤醒最近入库的 ANR，丢失时由版本轮转补偿。 */
@Component
public class AnrSymbolicationWakeup {
    /** 只记录队列饱和状态。 */
    private static final Logger LOG = LoggerFactory.getLogger(AnrSymbolicationWakeup.class);
    /** 不保存诊断正文的有限唤醒集合。 */
    private final ArrayBlockingQueue<Key> pending = new ArrayBlockingQueue<>(256);

    /** 接收路径只做非阻塞唤醒。 */
    public void offer(UUID appId, String eventId) {
        if (!pending.offer(new Key(appId, eventId))) LOG.debug("ANR 还原唤醒已满，等待后台轮转");
    }

    /** 后台取出一个最近入库身份。 */
    public Key poll() {
        return pending.poll();
    }

    /** 不携带原始现场的事件身份。 */
    public record Key(UUID appId, String eventId) { }
}
