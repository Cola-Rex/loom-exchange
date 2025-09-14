package com.loom.exchange.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * 订单取消事件
 */
public record OrderCancelled(
    String eventId,
    String streamId,
    Instant timestamp,
    long sequenceNumber,
    String orderId,
    String symbol,
    String reason
) implements DomainEvent {
    
    public static OrderCancelled create(String orderId, String symbol, 
                                      String reason, long sequenceNumber) {
        return new OrderCancelled(
            UUID.randomUUID().toString(),
            "orders-" + symbol,
            Instant.now(),
            sequenceNumber,
            orderId,
            symbol,
            reason
        );
    }
    
    @Override
    public String eventType() {
        return "OrderCancelled";
    }
    
    @Override
    public byte[] serialize() {
        var data = String.format("%s|%s|%s|%d|%s|%s|%s",
            eventId, streamId, timestamp, sequenceNumber,
            orderId, symbol, reason);
        return data.getBytes();
    }
}
