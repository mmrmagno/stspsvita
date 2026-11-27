package com.stsvita.compat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class CompactingIterator<E> implements Iterator<E> {
    private final ArrayList<E> list;
    private int read;
    private boolean canRemove;
    private int[] removed = new int[16];
    private int removedCount;

    private CompactingIterator(ArrayList<E> list) {
        this.list = list;
    }

    public static <E> Iterator<E> of(ArrayList<E> list) {
        TrailBudget.frame(list);
        return new CompactingIterator<>(list);
    }

    @Override
    public boolean hasNext() {
        if (read < list.size()) {
            return true;
        }
        if (removedCount > 0) {
            compact();
        }
        return false;
    }

    @Override
    public E next() {
        if (read >= list.size()) {
            throw new NoSuchElementException();
        }
        canRemove = true;
        return list.get(read++);
    }

    @Override
    public void remove() {
        if (!canRemove) {
            throw new IllegalStateException();
        }
        canRemove = false;
        if (removedCount == removed.length) {
            int[] grown = new int[removed.length * 2];
            System.arraycopy(removed, 0, grown, 0, removedCount);
            removed = grown;
        }
        removed[removedCount++] = read - 1;
    }

    private void compact() {
        int size = list.size();
        int write = removed[0];
        int next = 0;
        for (int i = write; i < size; i++) {
            if (next < removedCount && removed[next] == i) {
                next++;
                continue;
            }
            list.set(write++, list.get(i));
        }
        while (size > write) {
            list.remove(--size);
        }
        removedCount = 0;
    }
}
