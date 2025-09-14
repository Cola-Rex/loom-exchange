package com.loom.exchange.engine;

import com.loom.exchange.core.Order;
import com.loom.exchange.core.Trade;
import java.util.List;

/**
 * 撮合结果
 */
public record MatchResult(
    Order originalOrder,
    Order updatedOrder,
    List<Trade> trades,
    boolean success,
    String errorMessage
) {
    
    /**
     * 创建成功的撮合结果
     */
    public static MatchResult success(Order originalOrder, Order updatedOrder, List<Trade> trades) {
        return new MatchResult(originalOrder, updatedOrder, trades, true, null);
    }
    
    /**
     * 创建失败的撮合结果
     */
    public static MatchResult error(Order order, String errorMessage) {
        return new MatchResult(order, null, List.of(), false, errorMessage);
    }
    
    /**
     * 检查是否有交易发生
     */
    public boolean hasTraded() {
        return !trades.isEmpty();
    }
    
    /**
     * 获取交易总数量
     */
    public int getTradeCount() {
        return trades.size();
    }
    
    /**
     * 检查订单是否完全成交
     */
    public boolean isFullyFilled() {
        return updatedOrder != null && updatedOrder.isFullyFilled();
    }
}
