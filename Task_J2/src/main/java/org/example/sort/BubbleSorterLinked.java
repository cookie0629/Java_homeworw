package org.example.sort;

import org.example.app.StepCounter;
import org.example.list.ConcurrentLinkedList;
import org.example.list.Node;

/*
优点：实现简单，使用synchronized关键字
缺点：并发度低，整个列表被锁定
 */
public class BubbleSorterLinked implements Runnable {
    private final ConcurrentLinkedList list;
    private final long delayMs;
    private final StepCounter steps;

    public BubbleSorterLinked(ConcurrentLinkedList list, long delayMs, StepCounter steps) {
        this.list = list;
        this.delayMs = delayMs;
        this.steps = steps;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Node prev = list.head();
                Node a = prev.next;
                while (a != null && a.next != null) {
                    Node b = a.next;
                    sleep(delayMs);
                    steps.inc();
                    boolean swapped = list.trySwapIfOutOfOrder(prev, a, b);
                    sleep(delayMs);
                    if (swapped) {
                        prev = prev.next;
                        a = prev.next;
                    } else {
                        prev = a;
                        a = a.next;
                    }
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