# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
COPY Loom_Agent-api/pom.xml Loom_Agent-api/pom.xml
COPY Loom_Agent-app/pom.xml Loom_Agent-app/pom.xml
COPY Loom_Agent-domain/pom.xml Loom_Agent-domain/pom.xml
COPY Loom_Agent-infrastructure/pom.xml Loom_Agent-infrastructure/pom.xml
COPY Loom_Agent-trigger/pom.xml Loom_Agent-trigger/pom.xml
COPY Loom_Agent-types/pom.xml Loom_Agent-types/pom.xml

COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl Loom_Agent-app -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre

LABEL maintainer="lunalhx"

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS=""
ENV PARAMS=""

WORKDIR /app

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone

COPY --from=build /workspace/Loom_Agent-app/target/Loom_Agent-app.jar /app/Loom_Agent-app.jar

EXPOSE 8091

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/Loom_Agent-app.jar $PARAMS"]
