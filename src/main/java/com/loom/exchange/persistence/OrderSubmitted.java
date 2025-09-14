package com.loom.exchange.persistence;

import com.loom.exchange.core.Order;
import java.time.Instant;
import java.util.UUID;

/**
 * 订单提交事件
 */
public record OrderSubmitted(
    String eventId,
    String streamId,
    Instant timestamp,
    long sequenceNumber,
    Order order
) implements DomainEvent {
    
    public static OrderSubmitted create(Order order, long sequenceNumber) {
        return new OrderSubmitted(
            UUID.randomUUID().toString(),
            "orders-" + order.symbol(),
            Instant.now(),
            sequenceNumber,
            order
        );
    }
    
    @Override
    public String eventType() {
        return "OrderSubmitted";
    }
    
    @Override
    public byte[] serialize() {
        // 简化的序列化实现
        // 生产环境应使用高效的序列化框架
        var data = String.format("%s|%s|%s|%d|%s|%s|%s|%s|%s|%s",
            eventId, streamId, timestamp, sequenceNumber,
            order.orderId(), order.symbol(), order.side(), 
            order.type(), order.price(), order.quantity());
        return data.getBytes();
    }
}
