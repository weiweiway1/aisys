# syntax=docker/dockerfile:1
# 多阶段：容器内编译源码为 jar（DDD：后端微服务以编译后的 jar 构建运行容器）
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY . /build
COPY deploy/maven-settings.xml /root/.m2/settings.xml
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -pl aisys-model -am -DskipTests -B -q package

FROM eclipse-temurin:25-jre
LABEL service=aisys-model
COPY --from=build /build/aisys-model/target/aisys-model.jar /app/app.jar
ENV JAVA_XMX=256m TZ=Asia/Shanghai
ENTRYPOINT ["sh","-c","exec java -XX:MaxRAMPercentage=75 -Xmx${JAVA_XMX} -Duser.timezone=Asia/Shanghai -jar /app/app.jar"]
