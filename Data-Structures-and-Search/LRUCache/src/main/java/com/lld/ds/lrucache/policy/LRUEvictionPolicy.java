package com.lld.ds.lrucache.policy;

import com.lld.ds.lrucache.list.DoublyLinkedList;
import com.lld.ds.lrucache.list.DoublyLinkedList.Node;

import java.util.HashMap;
import java.util.Map;

/** Least Recently Used: the same map + doubly linked list idea as LRUCache, but over keys only. */
public class LRUEvictionPolicy<K> implements EvictionPolicy<K> {

    private final Map<K, Node<K>> nodes = new HashMap<>();
    private final DoublyLinkedList<K> order = new DoublyLinkedList<>();

    @Override
    public void onInsert(K key) {
        nodes.put(key, order.addFirst(key));
    }

    @Override
    public void onAccess(K key) {
        Node<K> node = nodes.get(key);
        if (node != null) {
            order.moveToFront(node);
        }
    }

    @Override
    public void onRemove(K key) {
        Node<K> node = nodes.remove(key);
        if (node != null) {
            order.remove(node);
        }
    }

    @Override
    public K evict() {
        K victim = order.removeLast().item();
        nodes.remove(victim);
        return victim;
    }

    @Override
    public void clear() {
        nodes.clear();
        order.clear();
    }
}
