package com.loom.exchange.core;

import java.util.List;

/**
 * 订单簿深度信息
 */
public record OrderBookDepth(
    String symbol,
    List<PriceLevel> bids,  // 买单档位（价格从高到低）
    List<PriceLevel> asks   // 卖单档位（价格从低到高）
) {
    
    /**
     * 获取最佳买价
     */
    public PriceLevel getBestBid() {
        return bids.isEmpty() ? null : bids.get(0);
    }
    
    /**
     * 获取最佳卖价
     */
    public PriceLevel getBestAsk() {
        return asks.isEmpty() ? null : asks.get(0);
    }
    
    /**
     * 检查订单簿是否为空
     */
    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }
    
    /**
     * 获取买单总数
     */
    public int getBidCount() {
        return bids.stream()
            .mapToInt(PriceLevel::getOrderCount)
            .sum();
    }
    
    /**
     * 获取卖单总数
     */
    public int getAskCount() {
        return asks.stream()
            .mapToInt(PriceLevel::getOrderCount)
            .sum();
    }
}
