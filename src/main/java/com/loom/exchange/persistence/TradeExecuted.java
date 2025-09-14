package com.loom.exchange.persistence;

import com.loom.exchange.core.Trade;
import java.time.Instant;
import java.util.UUID;

/**
 * 交易执行事件
 */
public record TradeExecuted(
    String eventId,
    String streamId,
    Instant timestamp,
    long sequenceNumber,
    Trade trade
) implements DomainEvent {
    
    public static TradeExecuted create(Trade trade, long sequenceNumber) {
        return new TradeExecuted(
            UUID.randomUUID().toString(),
            "trades-" + trade.symbol(),
            Instant.now(),
            sequenceNumber,
            trade
        );
    }
    
    @Override
    public String eventType() {
        return "TradeExecuted";
    }
    
    @Override
    public byte[] serialize() {
        var data = String.format("%s|%s|%s|%d|%s|%s|%s|%s|%s|%s|%s|%s|%s",
            eventId, streamId, timestamp, sequenceNumber,
            trade.tradeId(), trade.symbol(), trade.buyOrderId(), 
            trade.sellOrderId(), trade.buyUserId(), trade.sellUserId(),
            trade.price(), trade.quantity(), trade.timestamp());
        return data.getBytes();
    }
}
