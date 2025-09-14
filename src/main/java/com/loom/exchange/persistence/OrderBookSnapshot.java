package com.loom.exchange.persistence;

import com.loom.exchange.core.OrderBookDepth;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 订单簿快照事件
 */
public record OrderBookSnapshot(
    String eventId,
    String streamId,
    Instant timestamp,
    long sequenceNumber,
    String symbol,
    OrderBookDepth depth,
    Map<String, Object> metadata
) implements DomainEvent {
    
    public static OrderBookSnapshot create(String symbol, OrderBookDepth depth, 
                                         long sequenceNumber) {
        return new OrderBookSnapshot(
            UUID.randomUUID().toString(),
            "snapshot-" + symbol,
            Instant.now(),
            sequenceNumber,
            symbol,
            depth,
            Map.of(
                "orderCount", depth.getBidCount() + depth.getAskCount(),
                "snapshotVersion", "1.0"
            )
        );
    }
    
    @Override
    public String eventType() {
        return "OrderBookSnapshot";
    }
    
    @Override
    public byte[] serialize() {
        // 快照数据较大，应使用更高效的序列化
        var data = String.format("%s|%s|%s|%d|%s|%s",
            eventId, streamId, timestamp, sequenceNumber,
            symbol, "SNAPSHOT_DATA");
        return data.getBytes();
    }
}
