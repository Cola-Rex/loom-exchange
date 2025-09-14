package com.loom.exchange.persistence;

import java.time.Instant;

/**
 * 领域事件基类
 * 使用sealed class确保类型安全
 */
public sealed interface DomainEvent 
    permits OrderSubmitted, OrderMatched, OrderCancelled, TradeExecuted, OrderBookSnapshot {
    
    String eventId();
    String streamId();
    Instant timestamp();
    long sequenceNumber();
    String eventType();
    
    /**
     * 事件序列化为字节数组
     */
    byte[] serialize();
    
    /**
     * 从字节数组反序列化事件
     */
    static DomainEvent deserialize(byte[] data) {
        // 实现事件反序列化逻辑
        // 这里简化处理，实际应使用高效的序列化框架如Kryo或Protobuf
        throw new UnsupportedOperationException("需要实现具体的反序列化逻辑");
    }
}
