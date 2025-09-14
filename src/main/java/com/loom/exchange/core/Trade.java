package com.loom.exchange.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 交易记录 - 撮合成功后生成的交易
 */
public record Trade(
    String tradeId,
    String symbol,
    String buyOrderId,
    String sellOrderId,
    String buyUserId,
    String sellUserId,
    BigDecimal price,
    BigDecimal quantity,
    Instant timestamp
) {
    
    /**
     * 创建新的交易记录
     */
    public static Trade create(String tradeId, String symbol,
                              Order buyOrder, Order sellOrder,
                              BigDecimal tradePrice, BigDecimal tradeQuantity) {
        return new Trade(
            tradeId,
            symbol,
            buyOrder.orderId(),
            sellOrder.orderId(),
            buyOrder.userId(),
            sellOrder.userId(),
            tradePrice,
            tradeQuantity,
            Instant.now()
        );
    }
    
    /**
     * 计算交易金额
     */
    public BigDecimal getValue() {
        return price.multiply(quantity);
    }
}
