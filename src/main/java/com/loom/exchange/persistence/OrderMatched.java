package com.loom.exchange.persistence;

import com.loom.exchange.core.Trade;
import java.time.Instant;
import java.util.UUID;

/**
 * 订单撮合事件
 */
public record OrderMatched(
    String eventId,
    String streamId,
    Instant timestamp,
    long sequenceNumber,
    Trade trade,
    String takerOrderId,
    String makerOrderId
) implements DomainEvent {
    
    public static OrderMatched create(Trade trade, String takerOrderId, 
                                    String makerOrderId, long sequenceNumber) {
        return new OrderMatched(
            UUID.randomUUID().toString(),
            "trades-" + trade.symbol(),
            Instant.now(),
            sequenceNumber,
            trade,
            takerOrderId,
            makerOrderId
        );
    }
    
    @Override
    public String eventType() {
        return "OrderMatched";
    }
    
    @Override
    public byte[] serialize() {
        var data = String.format("%s|%s|%s|%d|%s|%s|%s|%s|%s|%s|%s",
            eventId, streamId, timestamp, sequenceNumber,
            trade.tradeId(), trade.symbol(), trade.price(), trade.quantity(),
            takerOrderId, makerOrderId, trade.timestamp());
        return data.getBytes();
    }
}
