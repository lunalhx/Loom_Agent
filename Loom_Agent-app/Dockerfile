# 基础镜像，可以先执行 docker pull eclipse-temurin:21-jre
FROM eclipse-temurin:21-jre

# 作者
LABEL maintainer="xiaofuge"

# 配置
ENV PARAMS=""

# 时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 添加应用
ADD target/Loom_Agent-app.jar /Loom_Agent-app.jar

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /Loom_Agent-app.jar $PARAMS"]
