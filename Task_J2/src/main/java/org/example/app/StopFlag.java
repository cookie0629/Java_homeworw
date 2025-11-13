package org.example.app;

import java.util.concurrent.atomic.AtomicBoolean;

// 用于通知线程停止的标志
public class StopFlag {
    private final AtomicBoolean stop = new AtomicBoolean(false);

    public void set(boolean v) {
        stop.set(v);
    }

    public boolean get() {
        return stop.get();
    }
}