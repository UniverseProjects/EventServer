FROM openjdk:8-jre-slim
RUN mkdir /usr/local/event-server
WORKDIR /usr/local/event-server
ENTRYPOINT ["java", "-server", "-XX:+UseG1GC", "-Xms128M", "-Xmx512M", "-Dfile.encoding=UTF8", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005", "-jar", "event-server.jar"]
ADD event-server.jar /usr/local/event-server/
