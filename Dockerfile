# 一键构建并运行医院陪护证服务
# 多阶段构建: Maven 编译打包 -> JRE 运行
# 单独运行(需自行提供 PostgreSQL):
#   docker build -t escort-pass-service .
#   docker run -p 8080:8080 -e DB_URL=jdbc:postgresql://host:5432/escort escort-pass-service
# 推荐直接用 docker compose up --build(含 PostgreSQL, 可选 Kafka)

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷贝 pom 预拉依赖, 利用构建缓存
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/escort-pass-service-1.0.0.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
