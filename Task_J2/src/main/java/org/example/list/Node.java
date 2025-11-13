package org.example.list;

import java.util.concurrent.locks.ReentrantLock;

//链表的节点，每个节点有一个锁，用于并发控制
public final class Node {
    public final ReentrantLock lock = new ReentrantLock();
    public String value;
    public volatile Node next;

    public Node(String v) {
        this.value = v;
    }
}