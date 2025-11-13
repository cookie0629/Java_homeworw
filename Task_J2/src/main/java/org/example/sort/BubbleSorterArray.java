package org.example.sort;

import org.example.app.StepCounter;

import java.util.Collections;
import java.util.List;

/*
优点：每个节点独立加锁，并发度高
缺点：实现复杂，容易死锁
锁顺序：严格按 prev→a→b 顺序加锁避免死锁
 */
public class BubbleSorterArray implements Runnable {
    private final List<String> list;
    private final long delayMs;
    private final StepCounter steps;

    public BubbleSorterArray(List<String> list, long delayMs, StepCounter steps) {
        this.list = list;
        this.delayMs = delayMs;
        this.steps = steps;
    }

    @Override
    public void run() {
        try {
            while (true) {
                int n;
                synchronized (list) {
                    n = list.size();
                }
                for (int i = 0; i + 1 < n; i++) {
                    sleep(delayMs);

                    steps.inc();
                    boolean swapped = false;

                    synchronized (list) {
                        if (i + 1 < list.size()) {
                            String a = list.get(i);
                            String b = list.get(i + 1);
                            if (a.compareTo(b) > 0) {
                                Collections.swap(list, i, i + 1);
                                swapped = true;
                            }
                        }
                    }
                    sleep(delayMs);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) throws InterruptedException {
        if (ms > 0)
            Thread.sleep(ms);
    }
}