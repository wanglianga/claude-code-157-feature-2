# 多阶段构建: Maven 编译 -> JRE 运行
# 一键启动(含 PostgreSQL): docker compose up -d --build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷贝 pom 预热依赖层
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/escort-pass-service-1.0.0.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD ["sh","-c","curl -fsS http://localhost:8080/actuator/health || exit 1"]
ENTRYPOINT ["java","-jar","/app/app.jar"]
