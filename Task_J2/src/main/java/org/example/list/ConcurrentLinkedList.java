package org.example.list;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

// 一个线程安全的链表，支持在头部添加元素，并提供了冒泡排序时交换节点的方法
public class ConcurrentLinkedList implements Iterable<String> {
    private final Node head = new Node(null);
    private final ReentrantLock headLock = new ReentrantLock();
    private final AtomicInteger size = new AtomicInteger();

    public void addFirst(String s) {
        Node n = new Node(s);
        headLock.lock();
        try {
            n.next = head.next;
            head.next = n;
            size.incrementAndGet();
        } finally {
            headLock.unlock();
        }
    }

    public int size() {
        return size.get();
    }

    public boolean trySwapIfOutOfOrder(Node prev, Node a, Node b) {
        lockOrdered(prev, a, b);
        try {
            if (a == null || b == null)
                return false;
            if (prev.next != a || a.next != b)
                return false;
            if (a.value.compareTo(b.value) > 0) {
                a.next = b.next;
                b.next = a;
                prev.next = b;
                return true;
            }
            return false;
        } finally {
            b.lock.unlock();
            a.lock.unlock();
            prev.lock.unlock();
        }
    }

    public static void lockOrdered(Node prev, Node a, Node b) {
        prev.lock.lock();
        a.lock.lock();
        if (b != null)
            b.lock.lock();
    }

    public Node head() {
        return head;
    }

    @Override
    public Iterator<String> iterator() {
        return new Iterator<>() {
            Node cur = head.next;

            @Override
            public boolean hasNext() {
                return cur != null;
            }

            @Override
            public String next() {
                if (cur == null)
                    throw new NoSuchElementException();
                String v = cur.value;
                cur = cur.next;
                return v;
            }
        };
    }
}