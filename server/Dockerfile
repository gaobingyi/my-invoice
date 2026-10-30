FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY server/pom.xml .
RUN mvn -B dependency:go-offline
COPY server/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# 通配 jar 名：升版本（pom <version>）时不必同步改这里；target 下唯一 jar
COPY --from=build /build/target/invoice-server-*.jar /app/app.jar
# SQLite 数据库文件目录：pre-create 并 chown 到 UID 1000（即便当前以 root 启动，
# 也能防止未来切非 root USER 时权限破裂）。WAL 模式下 SQLite 还会写
# /app/data/invoice.db-wal 与 /app/data/invoice.db-shm。
# 同时装 sqlite3 CLI：用于生产在线备份（`sqlite3 ... .backup`）和 .schema 检视。
RUN mkdir -p /app/data && chown -R 1000:1000 /app/data \
    && apk add --no-cache sqlite
EXPOSE 8080
ENV APP_UPLOAD_DIR=/app/uploads
# exec 让 java 接管 PID 1：否则 sh 是 PID 1 且不转发 SIGTERM，docker stop 等 10s
# 超时后 SIGKILL 硬杀，Spring 优雅停机（server.shutdown: graceful）与在途 SQLite
# 写事务从不执行。compose 侧配合 stop_grace_period: 35s 覆盖 30s 的优雅停机窗口。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
