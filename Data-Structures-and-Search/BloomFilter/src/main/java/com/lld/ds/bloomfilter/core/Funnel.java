package com.lld.ds.bloomfilter.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Strategy that turns an item into bytes for hashing. It must be deterministic: equal items must
 * always produce equal bytes, even across JVM runs, so {@code hashCode()} is not good enough.
 */
@FunctionalInterface
public interface Funnel<T> {

    byte[] toBytes(T item);

    Funnel<String> STRING = s -> s.getBytes(StandardCharsets.UTF_8);

    Funnel<Long> LONG = v -> ByteBuffer.allocate(Long.BYTES).putLong(v).array();

    Funnel<Integer> INTEGER = v -> ByteBuffer.allocate(Integer.BYTES).putInt(v).array();
}
