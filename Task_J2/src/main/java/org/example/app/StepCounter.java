package org.example.app;

import java.util.concurrent.atomic.AtomicLong;

// 用于计数排序步骤的线程安全计数器
public class StepCounter {
    private final AtomicLong steps = new AtomicLong();

    public long inc() {
        return steps.incrementAndGet();
    }

    public long get() {
        return steps.get();
    }
}