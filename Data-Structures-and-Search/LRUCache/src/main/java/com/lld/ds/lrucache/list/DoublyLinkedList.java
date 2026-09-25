package com.lld.ds.lrucache.list;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal doubly linked list with sentinel head and tail nodes. Every operation is O(1)
 * given the node, which is exactly what an LRU cache needs: the HashMap finds the node,
 * the list moves or removes it without searching.
 *
 * <p>Sentinels (dummy head/tail) remove all "is this the first/last node?" special cases.
 */
public class DoublyLinkedList<T> {

    /** A list node. Callers keep references to nodes (e.g. in a map) to unlink them in O(1). */
    public static final class Node<T> {
        private T item;
        private Node<T> prev;
        private Node<T> next;

        private Node(T item) {
            this.item = item;
        }

        public T item() {
            return item;
        }
    }

    private final Node<T> head = new Node<>(null);   // sentinel before the first element
    private final Node<T> tail = new Node<>(null);   // sentinel after the last element
    private int size;

    public DoublyLinkedList() {
        head.next = tail;
        tail.prev = head;
    }

    /** Inserts at the front and returns the new node. O(1). */
    public Node<T> addFirst(T item) {
        Node<T> node = new Node<>(item);
        linkAfter(head, node);
        size++;
        return node;
    }

    /** Unlinks a node that belongs to this list. O(1). */
    public void remove(Node<T> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
        size--;
    }

    /** Moves an existing node to the front. O(1). */
    public void moveToFront(Node<T> node) {
        if (head.next == node) {
            return;
        }
        node.prev.next = node.next;
        node.next.prev = node.prev;
        linkAfter(head, node);
    }

    /** Removes and returns the last node, or null if empty. O(1). */
    public Node<T> removeLast() {
        if (size == 0) {
            return null;
        }
        Node<T> last = tail.prev;
        remove(last);
        return last;
    }

    public T peekLast() {
        return size == 0 ? null : tail.prev.item;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    /** Items from front to back. O(n); for display and tests. */
    public List<T> toList() {
        List<T> items = new ArrayList<>(size);
        for (Node<T> n = head.next; n != tail; n = n.next) {
            items.add(n.item);
        }
        return items;
    }

    private void linkAfter(Node<T> anchor, Node<T> node) {
        node.prev = anchor;
        node.next = anchor.next;
        anchor.next.prev = node;
        anchor.next = node;
    }
}
