#!/bin/bash

# Loom Exchange 启动脚本
# 针对JDK 21 ZGC分代和虚拟线程优化的高频交易系统

echo "启动 Loom Exchange - 高频交易撮合系统"
echo "JDK 21 + ZGC分代 + 虚拟线程"
echo "================================="

# 检查JDK版本
java -version
echo ""

# JVM参数配置
JVM_OPTS=""

# === ZGC分代垃圾收集器配置 ===
JVM_OPTS="$JVM_OPTS -XX:+UnlockExperimentalVMOptions"
JVM_OPTS="$JVM_OPTS -XX:+UseZGC"                    # 启用ZGC
JVM_OPTS="$JVM_OPTS -XX:+UseGenerationalZGC"       # 启用分代ZGC (JDK 21)
JVM_OPTS="$JVM_OPTS -XX:+UnlockDiagnosticVMOptions"

# === 内存配置 ===
JVM_OPTS="$JVM_OPTS -Xmx16g"                       # 最大堆内存16GB
JVM_OPTS="$JVM_OPTS -Xms16g"                       # 初始堆内存16GB
JVM_OPTS="$JVM_OPTS -XX:SoftMaxHeapSize=14g"       # 软最大堆大小14GB

# === 大页内存优化 ===
JVM_OPTS="$JVM_OPTS -XX:+UseLargePages"            # 启用大页内存
JVM_OPTS="$JVM_OPTS -XX:+UseTransparentHugePages"  # 透明大页

# === ZGC调优参数 ===
JVM_OPTS="$JVM_OPTS -XX:ZCollectionInterval=1"     # ZGC收集间隔1ms
JVM_OPTS="$JVM_OPTS -XX:ZUncommitDelay=30"         # 内存释放延迟30秒
JVM_OPTS="$JVM_OPTS -XX:ZGenerational=true"        # 明确启用分代

# === 虚拟线程优化 ===
JVM_OPTS="$JVM_OPTS --enable-preview"              # 启用预览特性
JVM_OPTS="$JVM_OPTS -Djdk.virtualThreadScheduler.parallelism=8"  # 虚拟线程调度器并行度
JVM_OPTS="$JVM_OPTS -Djdk.virtualThreadScheduler.maxPoolSize=256" # 最大线程池大小

# === 性能监控和日志 ===
JVM_OPTS="$JVM_OPTS -XX:+LogVMOutput"
JVM_OPTS="$JVM_OPTS -XX:LogFile=logs/gc.log"
JVM_OPTS="$JVM_OPTS -Xlog:gc*:logs/gc-%t.log:time,tags"
JVM_OPTS="$JVM_OPTS -Xlog:safepoint:logs/safepoint.log:time,tags"

# === JIT编译器优化 ===
JVM_OPTS="$JVM_OPTS -XX:+UseStringDeduplication"   # 字符串去重
JVM_OPTS="$JVM_OPTS -XX:+OptimizeStringConcat"     # 字符串连接优化
JVM_OPTS="$JVM_OPTS -XX:CompileThreshold=1000"     # JIT编译阈值

# === 网络和IO优化 ===
JVM_OPTS="$JVM_OPTS -Djava.net.preferIPv4Stack=true"
JVM_OPTS="$JVM_OPTS -Djava.awt.headless=true"

# === 安全和调试 ===
JVM_OPTS="$JVM_OPTS -Dfile.encoding=UTF-8"
JVM_OPTS="$JVM_OPTS -Duser.timezone=UTC"
JVM_OPTS="$JVM_OPTS -Djava.security.egd=file:/dev/./urandom"

# === 应用特定配置 ===
APP_OPTS=""
APP_OPTS="$APP_OPTS -Dspring.profiles.active=production"
APP_OPTS="$APP_OPTS -Dlogging.level.com.loom.exchange=INFO"

# 创建日志目录
mkdir -p logs

echo "JVM参数:"
echo "$JVM_OPTS" | tr ' ' '\n'
echo ""

echo "应用参数:"
echo "$APP_OPTS" | tr ' ' '\n'
echo ""

# 启动应用
echo "启动交易所..."
java $JVM_OPTS $APP_OPTS -jar target/loom-exchange-1.0.0.jar

echo "交易所已停止"
