FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY server/pom.xml .
RUN mvn -B dependency:go-offline
COPY server/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /build/target/invoice-server-1.0.0.jar /app/app.jar
# SQLite 数据库文件目录：pre-create 并 chown 到 UID 1000（即便当前以 root 启动，
# 也能防止未来切非 root USER 时权限破裂）。WAL 模式下 SQLite 还会写
# /app/data/invoice.db-wal 与 /app/data/invoice.db-shm。
# 同时装 sqlite3 CLI：用于生产在线备份（`sqlite3 ... .backup`）和 .schema 检视。
RUN mkdir -p /app/data && chown -R 1000:1000 /app/data \
    && apk add --no-cache sqlite
EXPOSE 8080
ENV APP_UPLOAD_DIR=/app/uploads
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
