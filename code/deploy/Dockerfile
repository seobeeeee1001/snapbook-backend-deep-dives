FROM amazoncorretto:17
WORKDIR /app
RUN ln -snf /usr/share/zoneinfo/Asia/Seoul /etc/localtime
ARG JAR_FILE=build/libs/*[!-plain].jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java","-jar","app.jar"]
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD wget -qO- http://127.0.2.1:8080/actuator/health || exit 1