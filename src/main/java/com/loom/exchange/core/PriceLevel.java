package com.loom.exchange.core;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 价格档位 - 同一价格的订单队列
 * 实现时间优先原则：先到先成交
 */
public class PriceLevel {
    
    private final BigDecimal price;
    private final Queue<Order> orders;
    private BigDecimal totalQuantity;
    
    public PriceLevel(BigDecimal price) {
        this.price = price;
        this.orders = new LinkedList<>();
        this.totalQuantity = BigDecimal.ZERO;
    }
    
    /**
     * 添加订单到队列末尾
     */
    public void addOrder(Order order) {
        orders.offer(order);
        totalQuantity = totalQuantity.add(order.getRemainingQuantity());
    }
    
    /**
     * 移除指定订单
     */
    public boolean removeOrder(Order order) {
        boolean removed = orders.remove(order);
        if (removed) {
            totalQuantity = totalQuantity.subtract(order.getRemainingQuantity());
        }
        return removed;
    }
    
    /**
     * 获取队列头部订单（最早的订单）
     */
    public Order peekFirst() {
        return orders.peek();
    }
    
    /**
     * 移除并返回队列头部订单
     */
    public Order pollFirst() {
        var order = orders.poll();
        if (order != null) {
            totalQuantity = totalQuantity.subtract(order.getRemainingQuantity());
        }
        return order;
    }
    
    /**
     * 检查价格档位是否为空
     */
    public boolean isEmpty() {
        return orders.isEmpty();
    }
    
    /**
     * 获取该价格档位的订单数量
     */
    public int getOrderCount() {
        return orders.size();
    }
    
    /**
     * 获取该价格档位的总数量
     */
    public BigDecimal getTotalQuantity() {
        return totalQuantity;
    }
    
    /**
     * 获取价格
     */
    public BigDecimal getPrice() {
        return price;
    }
    
    /**
     * 重新计算总数量（用于数据一致性检查）
     */
    public void recalculateTotalQuantity() {
        totalQuantity = orders.stream()
            .map(Order::getRemainingQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    @Override
    public String toString() {
        return String.format("PriceLevel{price=%s, orders=%d, totalQty=%s}", 
                           price, orders.size(), totalQuantity);
    }
}
