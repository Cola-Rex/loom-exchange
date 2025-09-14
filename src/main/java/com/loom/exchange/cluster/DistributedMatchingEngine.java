package com.loom.exchange.cluster;

import com.loom.exchange.core.Order;
import com.loom.exchange.engine.EventSourcingMatchingEngine;
import com.loom.exchange.engine.MatchResult;
import com.loom.exchange.persistence.EventStore;
import com.loom.exchange.risk.RiskConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分布式撮合引擎
 * 实现主从复制和故障转移
 */
@Component
public class DistributedMatchingEngine implements ClusterEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedMatchingEngine.class);
    
    private final EventSourcingMatchingEngine localEngine;
    private final ClusterManager clusterManager;
    private final ExecutorService virtualThreadExecutor;
    
    // 状态管理
    private final AtomicBoolean isMaster = new AtomicBoolean(false);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicLong processedOrders = new AtomicLong(0);
    
    // 复制相关
    private final Map<String, ReplicationChannel> replicationChannels = new ConcurrentHashMap<>();
    private final BlockingQueue<ReplicationCommand> replicationQueue = new LinkedBlockingQueue<>();
    
    public DistributedMatchingEngine(EventStore eventStore, ClusterManager clusterManager) {
        this(eventStore, clusterManager, RiskConfig.defaultConfig());
    }
    
    public DistributedMatchingEngine(EventStore eventStore, ClusterManager clusterManager, 
                                   RiskConfig riskConfig) {
        this.localEngine = new EventSourcingMatchingEngine(eventStore, riskConfig);
        this.clusterManager = clusterManager;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // 注册集群事件监听器
        clusterManager.addEventListener(this);
        
        // 启动复制处理线程
        startReplicationProcessor();
    }
    
    /**
     * 提交订单（分布式版本）
     */
    public CompletableFuture<MatchResult> submitOrder(Order order) {
        if (!isProcessing.get()) {
            return CompletableFuture.completedFuture(
                MatchResult.error(order, "系统暂停处理"));
        }
        
        if (isMaster.get()) {
            // 主节点处理订单
            return processOrderAsMaster(order);
        } else {
            // 从节点转发到主节点
            return forwardToMaster(order);
        }
    }
    
    /**
     * 主节点处理订单
     */
    private CompletableFuture<MatchResult> processOrderAsMaster(Order order) {
        return localEngine.submitOrder(order)
            .thenCompose(result -> {
                if (result.success()) {
                    // 复制到从节点
                    return replicateToSlaves(order, result)
                        .thenApply(replicationResult -> {
                            if (replicationResult.isSuccess()) {
                                processedOrders.incrementAndGet();
                                return result;
                            } else {
                                log.warn("复制到从节点失败: {}", replicationResult.getError());
                                // 即使复制失败，主节点的处理结果仍然有效
                                return result;
                            }
                        });
                } else {
                    return CompletableFuture.completedFuture(result);
                }
            })
            .exceptionally(throwable -> {
                log.error("主节点处理订单失败: {}", order.orderId(), throwable);
                return MatchResult.error(order, "处理失败: " + throwable.getMessage());
            });
    }
    
    /**
     * 转发订单到主节点
     */
    private CompletableFuture<MatchResult> forwardToMaster(Order order) {
        var masterOpt = clusterManager.getMasterNode();
        if (masterOpt.isEmpty()) {
            return CompletableFuture.completedFuture(
                MatchResult.error(order, "没有可用的主节点"));
        }
        
        var master = masterOpt.get();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 通过网络转发订单到主节点
                return forwardOrderToNode(master, order);
                
            } catch (Exception e) {
                log.error("转发订单到主节点失败: {} -> {}", order.orderId(), master.nodeId(), e);
                return MatchResult.error(order, "转发失败: " + e.getMessage());
            }
        }, virtualThreadExecutor);
    }
    
    /**
     * 复制到从节点
     */
    private CompletableFuture<ReplicationResult> replicateToSlaves(Order order, MatchResult result) {
        var slaveNodes = clusterManager.getHealthyNodes().stream()
            .filter(node -> !node.isMaster())
            .filter(node -> !node.nodeId().equals(clusterManager.getCurrentNode().nodeId()))
            .toList();
        
        if (slaveNodes.isEmpty()) {
            return CompletableFuture.completedFuture(ReplicationResult.success());
        }
        
        var replicationCommand = new ReplicationCommand(
            ReplicationCommand.Type.ORDER_PROCESSED,
            order,
            result,
            System.currentTimeMillis()
        );
        
        // 并行复制到所有从节点
        var replicationFutures = slaveNodes.stream()
            .map(slave -> replicateToSlave(slave, replicationCommand))
            .toList();
        
        return CompletableFuture.allOf(replicationFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                var results = replicationFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();
                
                var successCount = (int) results.stream()
                    .filter(ReplicationResult::isSuccess)
                    .count();
                
                if (successCount >= slaveNodes.size() / 2 + 1) {
                    // 大多数节点复制成功
                    return ReplicationResult.success();
                } else {
                    return ReplicationResult.failure("复制到大多数节点失败");
                }
            })
            .orTimeout(5, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                log.warn("复制超时或失败", throwable);
                return ReplicationResult.failure("复制超时: " + throwable.getMessage());
            });
    }
    
    /**
     * 复制到单个从节点
     */
    private CompletableFuture<ReplicationResult> replicateToSlave(ClusterNode slave, 
                                                                ReplicationCommand command) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var channel = getOrCreateReplicationChannel(slave);
                return channel.sendCommand(command);
                
            } catch (Exception e) {
                log.warn("复制到从节点失败: {} -> {}", command.order().orderId(), slave.nodeId(), e);
                return ReplicationResult.failure("网络错误: " + e.getMessage());
            }
        }, virtualThreadExecutor);
    }
    
    /**
     * 启动复制处理器
     */
    private void startReplicationProcessor() {
        virtualThreadExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    var command = replicationQueue.take();
                    processReplicationCommand(command);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("处理复制命令失败", e);
                }
            }
        });
    }
    
    /**
     * 处理复制命令
     */
    private void processReplicationCommand(ReplicationCommand command) {
        try {
            switch (command.type()) {
                case ORDER_PROCESSED -> {
                    // 从节点应用订单处理结果
                    if (!isMaster.get()) {
                        applyOrderResult(command.order(), command.result());
                    }
                }
                case SNAPSHOT_SYNC -> {
                    // 同步快照
                    applySyncSnapshot(command);
                }
                case HEARTBEAT -> {
                    // 处理心跳
                    processHeartbeat(command);
                }
            }
        } catch (Exception e) {
            log.error("处理复制命令失败: {}", command.type(), e);
        }
    }
    
    /**
     * 应用订单结果到从节点
     */
    private void applyOrderResult(Order order, MatchResult result) {
        // 在从节点上重放订单处理结果
        // 这里简化实现，实际应该维护与主节点一致的状态
        log.debug("从节点应用订单结果: {} -> {}", order.orderId(), result.success());
    }
    
    /**
     * 应用同步快照
     */
    private void applySyncSnapshot(ReplicationCommand command) {
        log.debug("应用同步快照");
        // 实现快照同步逻辑
    }
    
    /**
     * 处理心跳
     */
    private void processHeartbeat(ReplicationCommand command) {
        log.debug("收到复制心跳");
    }
    
    // 集群事件处理
    
    @Override
    public void onMasterElected(ClusterNode masterNode) {
        var isCurrentNodeMaster = masterNode.nodeId().equals(clusterManager.getCurrentNode().nodeId());
        
        if (isCurrentNodeMaster && !isMaster.get()) {
            // 当前节点成为主节点
            log.info("当前节点成为主节点，开始处理订单");
            becomeMaster();
            
        } else if (!isCurrentNodeMaster && isMaster.get()) {
            // 当前节点不再是主节点
            log.info("当前节点不再是主节点，停止处理订单");
            becomeSlave();
        }
    }
    
    @Override
    public void onNodeDown(ClusterNode node) {
        log.warn("节点下线: {}", node.nodeId());
        
        // 移除复制通道
        replicationChannels.remove(node.nodeId());
        
        // 如果是主节点下线，等待重新选举
        if (node.isMaster()) {
            log.warn("主节点下线，暂停订单处理");
            isProcessing.set(false);
        }
    }
    
    /**
     * 成为主节点
     */
    private void becomeMaster() {
        isMaster.set(true);
        isProcessing.set(true);
        
        // 初始化与从节点的复制通道
        initializeReplicationChannels();
        
        log.info("成为主节点，开始接受订单");
    }
    
    /**
     * 成为从节点
     */
    private void becomeSlave() {
        isMaster.set(false);
        isProcessing.set(false);
        
        // 清理复制通道
        replicationChannels.clear();
        
        log.info("成为从节点，停止接受订单");
    }
    
    /**
     * 初始化复制通道
     */
    private void initializeReplicationChannels() {
        var slaveNodes = clusterManager.getHealthyNodes().stream()
            .filter(node -> !node.isMaster())
            .filter(node -> !node.nodeId().equals(clusterManager.getCurrentNode().nodeId()))
            .toList();
        
        slaveNodes.forEach(slave -> {
            try {
                var channel = new ReplicationChannel(slave);
                replicationChannels.put(slave.nodeId(), channel);
                log.debug("初始化复制通道: {}", slave.nodeId());
                
            } catch (Exception e) {
                log.error("初始化复制通道失败: {}", slave.nodeId(), e);
            }
        });
    }
    
    /**
     * 获取或创建复制通道
     */
    private ReplicationChannel getOrCreateReplicationChannel(ClusterNode slave) {
        return replicationChannels.computeIfAbsent(slave.nodeId(), 
            k -> new ReplicationChannel(slave));
    }
    
    /**
     * 转发订单到指定节点
     */
    private MatchResult forwardOrderToNode(ClusterNode targetNode, Order order) {
        // 实际实现应该通过HTTP/gRPC调用目标节点
        // 这里简化为直接调用本地引擎
        log.debug("转发订单: {} -> {}", order.orderId(), targetNode.nodeId());
        
        try {
            return localEngine.submitOrder(order).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("转发订单失败", e);
        }
    }
    
    // 公共API
    
    public boolean isMasterNode() {
        return isMaster.get();
    }
    
    public boolean isProcessing() {
        return isProcessing.get();
    }
    
    public long getProcessedOrderCount() {
        return processedOrders.get();
    }
    
    public DistributedStats getStats() {
        return new DistributedStats(
            isMaster.get(),
            isProcessing.get(),
            processedOrders.get(),
            replicationChannels.size(),
            replicationQueue.size()
        );
    }
    
    /**
     * 分布式统计信息
     */
    public record DistributedStats(
        boolean isMaster,
        boolean isProcessing,
        long processedOrders,
        int replicationChannels,
        int pendingReplications
    ) {}
    
    /**
     * 复制命令
     */
    private record ReplicationCommand(
        Type type,
        Order order,
        MatchResult result,
        long timestamp
    ) {
        enum Type {
            ORDER_PROCESSED,
            SNAPSHOT_SYNC,
            HEARTBEAT
        }
    }
    
    /**
     * 复制结果
     */
    private record ReplicationResult(
        boolean success,
        String error
    ) {
        static ReplicationResult success() {
            return new ReplicationResult(true, null);
        }
        
        static ReplicationResult failure(String error) {
            return new ReplicationResult(false, error);
        }
        
        boolean isSuccess() {
            return success;
        }
        
        String getError() {
            return error;
        }
    }
    
    /**
     * 复制通道
     */
    private static class ReplicationChannel {
        private final ClusterNode targetNode;
        
        public ReplicationChannel(ClusterNode targetNode) {
            this.targetNode = targetNode;
        }
        
        public ReplicationResult sendCommand(ReplicationCommand command) {
            // 实际实现应该通过网络发送复制命令
            // 这里简化为成功返回
            return ReplicationResult.success();
        }
    }
}
