package org.example.app;

import org.example.list.ConcurrentLinkedList;

import java.util.ArrayList;
import java.util.List;

// 提供辅助方法，包括将字符串分割成80字符的块，以及打印链表和数组的方法
public class ConsoleUtil {
    public static List<String> split80(String s) {
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(i + 80, s.length());
            chunks.add(s.substring(i, end));
            i = end;
        }
        if (chunks.isEmpty())
            chunks.add("");
        return chunks;
    }

    public static void printLinked(ConcurrentLinkedList list, StepCounter steps) {
        int index = 0;
        for (String v : list) {
            System.out.printf("%3d: %s\n", index++, v);
        }
        System.out.printf("[size=%d, steps=%d]\n", list.size(), steps.get());
    }

    public static void printArray(List<String> list, StepCounter steps) {
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                System.out.printf("%3d: %s\n", i, list.get(i));
            }
        }
        System.out.printf("[size=%d, steps=%d]\n", list.size(), steps.get());
    }
}