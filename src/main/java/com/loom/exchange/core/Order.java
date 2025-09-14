package com.loom.exchange.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单实体 - 设计为不可变对象以提高并发性能
 * 使用JDK 21的record特性简化代码
 */
public record Order(
    String orderId,
    String symbol,
    OrderSide side,
    OrderType type,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal filledQuantity,
    OrderStatus status,
    Instant timestamp,
    String userId
) {
    
    public enum OrderSide {
        BUY, SELL
    }
    
    public enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT
    }
    
    public enum OrderStatus {
        PENDING,
        PARTIALLY_FILLED,
        FILLED,
        CANCELLED,
        REJECTED
    }
    
    /**
     * 创建新订单
     */
    public static Order create(String orderId, String symbol, OrderSide side, 
                              OrderType type, BigDecimal price, BigDecimal quantity, 
                              String userId) {
        return new Order(
            orderId,
            symbol,
            side,
            type,
            price,
            quantity,
            BigDecimal.ZERO,
            OrderStatus.PENDING,
            Instant.now(),
            userId
        );
    }
    
    /**
     * 获取剩余数量
     */
    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(filledQuantity);
    }
    
    /**
     * 检查订单是否完全成交
     */
    public boolean isFullyFilled() {
        return filledQuantity.compareTo(quantity) >= 0;
    }
    
    /**
     * 创建部分成交的订单副本
     */
    public Order withPartialFill(BigDecimal fillQuantity) {
        var newFilledQuantity = filledQuantity.add(fillQuantity);
        var newStatus = newFilledQuantity.compareTo(quantity) >= 0 
            ? OrderStatus.FILLED 
            : OrderStatus.PARTIALLY_FILLED;
            
        return new Order(
            orderId, symbol, side, type, price, quantity,
            newFilledQuantity, newStatus, timestamp, userId
        );
    }
    
    /**
     * 创建已取消的订单副本
     */
    public Order withCancellation() {
        return new Order(
            orderId, symbol, side, type, price, quantity,
            filledQuantity, OrderStatus.CANCELLED, timestamp, userId
        );
    }
    
    /**
     * 计算订单优先级 - 价格优先，时间优先
     */
    public long getPriorityScore() {
        // 使用纳秒时间戳作为优先级基础
        return timestamp.toEpochMilli() * 1_000_000 + timestamp.getNano() % 1_000_000;
    }
}
