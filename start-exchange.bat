@echo off
REM Loom Exchange Windows启动脚本
REM 针对JDK 21 ZGC分代和虚拟线程优化的高频交易系统

echo 启动 Loom Exchange - 高频交易撮合系统
echo JDK 21 + ZGC分代 + 虚拟线程
echo =================================

REM 检查JDK版本
java -version
echo.

REM JVM参数配置
set JVM_OPTS=-XX:+UnlockExperimentalVMOptions
set JVM_OPTS=%JVM_OPTS% -XX:+UseZGC
set JVM_OPTS=%JVM_OPTS% -XX:+UseGenerationalZGC
set JVM_OPTS=%JVM_OPTS% -XX:+UnlockDiagnosticVMOptions

REM 内存配置
set JVM_OPTS=%JVM_OPTS% -Xmx8g
set JVM_OPTS=%JVM_OPTS% -Xms8g
set JVM_OPTS=%JVM_OPTS% -XX:SoftMaxHeapSize=7g

REM ZGC调优参数
set JVM_OPTS=%JVM_OPTS% -XX:ZCollectionInterval=1
set JVM_OPTS=%JVM_OPTS% -XX:ZUncommitDelay=30

REM 虚拟线程优化
set JVM_OPTS=%JVM_OPTS% --enable-preview
set JVM_OPTS=%JVM_OPTS% -Djdk.virtualThreadScheduler.parallelism=8
set JVM_OPTS=%JVM_OPTS% -Djdk.virtualThreadScheduler.maxPoolSize=256

REM 性能监控和日志
if not exist logs mkdir logs
set JVM_OPTS=%JVM_OPTS% -Xlog:gc*:logs/gc.log:time,tags
set JVM_OPTS=%JVM_OPTS% -Xlog:safepoint:logs/safepoint.log:time,tags

REM 网络和IO优化
set JVM_OPTS=%JVM_OPTS% -Djava.net.preferIPv4Stack=true
set JVM_OPTS=%JVM_OPTS% -Djava.awt.headless=true
set JVM_OPTS=%JVM_OPTS% -Dfile.encoding=UTF-8
set JVM_OPTS=%JVM_OPTS% -Duser.timezone=UTC

REM 应用特定配置
set APP_OPTS=-Dspring.profiles.active=production
set APP_OPTS=%APP_OPTS% -Dlogging.level.com.loom.exchange=INFO

echo JVM参数: %JVM_OPTS%
echo.
echo 应用参数: %APP_OPTS%
echo.

REM 检查是否存在jar文件
if not exist target\loom-exchange-1.0.0.jar (
    echo 错误: 找不到 target\loom-exchange-1.0.0.jar
    echo 请先运行: mvn clean package
    pause
    exit /b 1
)

echo 启动交易所...
java %JVM_OPTS% %APP_OPTS% -jar target\loom-exchange-1.0.0.jar

echo 交易所已停止
pause
