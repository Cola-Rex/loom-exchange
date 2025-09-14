package com.loom.exchange.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 集群管理器
 * 负责节点发现、健康检查、故障转移等
 */
@Component
public class ClusterManager {
    
    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);
    
    private final Map<String, ClusterNode> clusterNodes = new ConcurrentHashMap<>();
    private final AtomicReference<ClusterNode> masterNode = new AtomicReference<>();
    private final ExecutorService virtualThreadExecutor;
    private final ScheduledExecutorService scheduler;
    
    // 配置参数
    private final Duration heartbeatInterval = Duration.ofSeconds(5);
    private final Duration healthCheckInterval = Duration.ofSeconds(10);
    private final Duration nodeTimeout = Duration.ofSeconds(30);
    
    // 事件监听器
    private final List<ClusterEventListener> eventListeners = new CopyOnWriteArrayList<>();
    
    // 当前节点信息
    private final ClusterNode currentNode;
    private volatile boolean isRunning = false;
    
    public ClusterManager() {
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        // 初始化当前节点
        this.currentNode = ClusterNode.create(
            generateNodeId(),
            getLocalHostname(),
            8080
        );
        
        // 注册当前节点
        clusterNodes.put(currentNode.nodeId(), currentNode);
    }
    
    /**
     * 启动集群管理
     */
    public void start() {
        if (isRunning) {
            return;
        }
        
        log.info("启动集群管理器, 节点ID: {}", currentNode.nodeId());
        isRunning = true;
        
        // 启动心跳发送
        scheduler.scheduleAtFixedRate(
            this::sendHeartbeat,
            0,
            heartbeatInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        
        // 启动健康检查
        scheduler.scheduleAtFixedRate(
            this::performHealthCheck,
            healthCheckInterval.toMillis(),
            healthCheckInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        
        // 启动主节点选举
        electMasterNode();
    }
    
    /**
     * 停止集群管理
     */
    public void stop() {
        if (!isRunning) {
            return;
        }
        
        log.info("停止集群管理器");
        isRunning = false;
        
        scheduler.shutdown();
        virtualThreadExecutor.shutdown();
        
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!virtualThreadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 加入集群
     */
    public void joinCluster(String seedNodeAddress) {
        log.info("加入集群, 种子节点: {}", seedNodeAddress);
        
        virtualThreadExecutor.submit(() -> {
            try {
                // 连接种子节点获取集群信息
                var clusterInfo = connectToSeedNode(seedNodeAddress);
                
                // 更新集群节点列表
                clusterInfo.forEach((nodeId, node) -> {
                    clusterNodes.put(nodeId, node);
                });
                
                // 通知其他节点当前节点加入
                broadcastNodeJoin();
                
                log.info("成功加入集群, 集群大小: {}", clusterNodes.size());
                
            } catch (Exception e) {
                log.error("加入集群失败: {}", seedNodeAddress, e);
            }
        });
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        if (!isRunning) return;
        
        virtualThreadExecutor.submit(() -> {
            try {
                // 更新当前节点指标
                var metrics = collectCurrentMetrics();
                var updatedNode = currentNode.withMetrics(metrics).withHeartbeat();
                clusterNodes.put(currentNode.nodeId(), updatedNode);
                
                // 向其他节点发送心跳
                var otherNodes = getOtherNodes();
                var futures = otherNodes.stream()
                    .map(node -> CompletableFuture.runAsync(() -> {
                        sendHeartbeatToNode(node, updatedNode);
                    }, virtualThreadExecutor))
                    .toList();
                
                // 等待心跳发送完成（超时处理）
                var allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                allOf.orTimeout(5, TimeUnit.SECONDS).join();
                
            } catch (Exception e) {
                log.warn("发送心跳失败", e);
            }
        });
    }
    
    /**
     * 执行健康检查
     */
    private void performHealthCheck() {
        if (!isRunning) return;
        
        virtualThreadExecutor.submit(() -> {
            var now = Instant.now();
            var timeoutNodes = new ArrayList<ClusterNode>();
            
            // 检查所有节点的心跳超时
            clusterNodes.values().forEach(node -> {
                if (!node.nodeId().equals(currentNode.nodeId())) {
                    var timeSinceHeartbeat = Duration.between(node.lastHeartbeat(), now);
                    
                    if (timeSinceHeartbeat.compareTo(nodeTimeout) > 0) {
                        timeoutNodes.add(node);
                    }
                }
            });
            
            // 处理超时节点
            timeoutNodes.forEach(this::handleNodeTimeout);
            
            // 检查主节点状态
            checkMasterNodeHealth();
        });
    }
    
    /**
     * 处理节点超时
     */
    private void handleNodeTimeout(ClusterNode timeoutNode) {
        log.warn("节点超时: {}", timeoutNode);
        
        // 更新节点状态为离线
        var offlineNode = timeoutNode.withStatus(ClusterNode.NodeStatus.OFFLINE);
        clusterNodes.put(timeoutNode.nodeId(), offlineNode);
        
        // 通知事件监听器
        notifyNodeDown(timeoutNode);
        
        // 如果是主节点超时，触发重新选举
        if (timeoutNode.isMaster()) {
            log.warn("主节点超时，触发重新选举");
            electMasterNode();
        }
    }
    
    /**
     * 检查主节点健康状态
     */
    private void checkMasterNodeHealth() {
        var master = masterNode.get();
        if (master == null) {
            log.warn("没有主节点，触发选举");
            electMasterNode();
            return;
        }
        
        var currentMaster = clusterNodes.get(master.nodeId());
        if (currentMaster == null || !currentMaster.isHealthy()) {
            log.warn("主节点不健康，触发重新选举: {}", currentMaster);
            electMasterNode();
        }
    }
    
    /**
     * 主节点选举
     */
    private void electMasterNode() {
        virtualThreadExecutor.submit(() -> {
            try {
                log.info("开始主节点选举");
                
                // 获取所有健康的节点
                var healthyNodes = clusterNodes.values().stream()
                    .filter(ClusterNode::isHealthy)
                    .filter(ClusterNode::isOnline)
                    .collect(Collectors.toList());
                
                if (healthyNodes.isEmpty()) {
                    log.warn("没有健康节点可选举为主节点");
                    return;
                }
                
                // 选择健康得分最高的节点作为主节点
                var newMaster = healthyNodes.stream()
                    .max(Comparator.comparing(ClusterNode::getHealthScore))
                    .orElse(null);
                
                if (newMaster == null) {
                    log.warn("选举失败，没有合适的主节点");
                    return;
                }
                
                // 更新主节点
                var masterWithRole = newMaster.withRole(ClusterNode.NodeRole.MASTER);
                clusterNodes.put(newMaster.nodeId(), masterWithRole);
                masterNode.set(masterWithRole);
                
                // 更新其他节点为从节点
                healthyNodes.stream()
                    .filter(node -> !node.nodeId().equals(newMaster.nodeId()))
                    .forEach(node -> {
                        var slave = node.withRole(ClusterNode.NodeRole.SLAVE);
                        clusterNodes.put(node.nodeId(), slave);
                    });
                
                log.info("选举完成，新主节点: {}", masterWithRole);
                
                // 通知事件监听器
                notifyMasterElected(masterWithRole);
                
            } catch (Exception e) {
                log.error("主节点选举失败", e);
            }
        });
    }
    
    /**
     * 收集当前节点指标
     */
    private NodeMetrics collectCurrentMetrics() {
        // 这里应该收集真实的系统指标
        // 简化实现，返回模拟数据
        return NodeMetrics.current(
            getCurrentCpuUsage(),
            getCurrentMemoryUsage(),
            measureNetworkLatency(),
            getCurrentOrderTps(),
            getCurrentTradeTps(),
            getCurrentAverageLatency(),
            getCurrentActiveConnections()
        );
    }
    
    // 指标收集方法（简化实现）
    private double getCurrentCpuUsage() {
        // 实际实现应该使用 OperatingSystemMXBean
        return Math.random() * 0.8; // 模拟0-80%的CPU使用率
    }
    
    private double getCurrentMemoryUsage() {
        var runtime = Runtime.getRuntime();
        var totalMemory = runtime.totalMemory();
        var freeMemory = runtime.freeMemory();
        return (double) (totalMemory - freeMemory) / runtime.maxMemory();
    }
    
    private long measureNetworkLatency() {
        // 实际实现应该ping其他节点
        return (long) (Math.random() * 50); // 模拟0-50ms延迟
    }
    
    private long getCurrentOrderTps() {
        // 从撮合引擎获取TPS
        return (long) (Math.random() * 10000); // 模拟TPS
    }
    
    private long getCurrentTradeTps() {
        // 从撮合引擎获取交易TPS
        return (long) (Math.random() * 5000); // 模拟交易TPS
    }
    
    private double getCurrentAverageLatency() {
        // 从撮合引擎获取平均延迟
        return Math.random() * 100; // 模拟0-100微秒延迟
    }
    
    private long getCurrentActiveConnections() {
        // 从网络层获取活跃连接数
        return (long) (Math.random() * 1000); // 模拟连接数
    }
    
    // 网络通信方法（简化实现）
    private Map<String, ClusterNode> connectToSeedNode(String seedNodeAddress) {
        // 实际实现应该通过HTTP/gRPC连接种子节点
        return new HashMap<>();
    }
    
    private void sendHeartbeatToNode(ClusterNode targetNode, ClusterNode currentNode) {
        // 实际实现应该通过网络发送心跳
        log.debug("发送心跳到节点: {}", targetNode.nodeId());
    }
    
    private void broadcastNodeJoin() {
        // 实际实现应该向所有节点广播加入消息
        log.debug("广播节点加入消息");
    }
    
    // 辅助方法
    private List<ClusterNode> getOtherNodes() {
        return clusterNodes.values().stream()
            .filter(node -> !node.nodeId().equals(currentNode.nodeId()))
            .filter(ClusterNode::isOnline)
            .collect(Collectors.toList());
    }
    
    private String generateNodeId() {
        return "node-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    private String getLocalHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
    
    // 事件通知方法
    private void notifyNodeDown(ClusterNode node) {
        eventListeners.forEach(listener -> {
            try {
                listener.onNodeDown(node);
            } catch (Exception e) {
                log.error("通知节点下线事件失败", e);
            }
        });
    }
    
    private void notifyMasterElected(ClusterNode master) {
        eventListeners.forEach(listener -> {
            try {
                listener.onMasterElected(master);
            } catch (Exception e) {
                log.error("通知主节点选举事件失败", e);
            }
        });
    }
    
    // 公共API
    public void addEventListener(ClusterEventListener listener) {
        eventListeners.add(listener);
    }
    
    public Optional<ClusterNode> getMasterNode() {
        return Optional.ofNullable(masterNode.get());
    }
    
    public boolean isMasterNode() {
        var master = masterNode.get();
        return master != null && master.nodeId().equals(currentNode.nodeId());
    }
    
    public List<ClusterNode> getHealthyNodes() {
        return clusterNodes.values().stream()
            .filter(ClusterNode::isHealthy)
            .filter(ClusterNode::isOnline)
            .collect(Collectors.toList());
    }
    
    public int getClusterSize() {
        return clusterNodes.size();
    }
    
    public ClusterNode getCurrentNode() {
        return currentNode;
    }
    
    public ClusterStats getStats() {
        var totalNodes = clusterNodes.size();
        var healthyNodes = (int) clusterNodes.values().stream()
            .filter(ClusterNode::isHealthy)
            .count();
        var onlineNodes = (int) clusterNodes.values().stream()
            .filter(ClusterNode::isOnline)
            .count();
            
        return new ClusterStats(totalNodes, healthyNodes, onlineNodes, 
                              masterNode.get() != null);
    }
    
    /**
     * 集群统计信息
     */
    public record ClusterStats(
        int totalNodes,
        int healthyNodes,
        int onlineNodes,
        boolean hasMaster
    ) {}
}
