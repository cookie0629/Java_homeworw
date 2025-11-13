package org.example.app;

import org.example.list.ConcurrentLinkedList;
import org.example.sort.BubbleSorterArray;
import org.example.sort.BubbleSorterLinked;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;


public class Main {
    enum Mode {LINKED, ARRAY}

    public static void main(String[] args) throws Exception {
        int workers = 2;
        long delayMs = 1000;
        Mode mode = Mode.LINKED;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workers":
                    workers = Integer.parseInt(args[++i]);
                    break;
                case "--delayMs":
                    delayMs = Long.parseLong(args[++i]);
                    break;
                case "--mode":
                    String m = args[++i].toLowerCase(Locale.ROOT);
                    mode = m.equals("array") ? Mode.ARRAY : Mode.LINKED;
                    break;
            }
        }

        System.out.printf("Mode=%s, workers=%d, delayMs=%d\n", mode, workers, delayMs);

        StepCounter steps = new StepCounter();
        StopFlag stop = new StopFlag();

        switch (mode) {
            case LINKED -> runLinked(workers, delayMs, steps, stop);
            case ARRAY -> runArray(workers, delayMs, steps, stop);
        }
    }

    private static void runLinked(int workers, long delayMs, StepCounter steps, StopFlag stop) throws Exception {
        ConcurrentLinkedList list = new ConcurrentLinkedList();
        List<Thread> sorters = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(new BubbleSorterLinked(list, delayMs, steps), "SorterLinked-" + i);
            t.setDaemon(true);
            t.start();
            sorters.add(t);
        }
        inputLoopLinked(list, steps, stop);
    }

    private static void runArray(int workers, long delayMs, StepCounter steps, StopFlag stop) throws Exception {
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        List<Thread> sorters = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(new BubbleSorterArray(list, delayMs, steps), "SorterArray-" + i);
            t.setDaemon(true);
            t.start();
            sorters.add(t);
        }
        inputLoopArray(list, steps, stop);
    }

    private static void inputLoopLinked(ConcurrentLinkedList list, StepCounter steps, StopFlag stop) throws Exception {
        System.out.println("Type lines. Empty line = print current list & step count. Ctrl+C to exit.");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) {
                ConsoleUtil.printLinked(list, steps);
            } else {
                for (String chunk : ConsoleUtil.split80(line)) {
                    list.addFirst(chunk);
                }
            }
        }
        stop.set(true);
    }

    private static void inputLoopArray(List<String> list, StepCounter steps, StopFlag stop) throws Exception {
        System.out.println("Type lines. Empty line = print current list & step count. Ctrl+C to exit.");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) {
                ConsoleUtil.printArray(list, steps);
            } else {
                for (String chunk : ConsoleUtil.split80(line)) {
                    synchronized (list) {
                        list.add(0, chunk);
                    }
                }
            }
        }
        stop.set(true);
    }
}