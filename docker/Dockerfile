FROM java:openjdk-8-jre
MAINTAINER Oskar Stangenberg "ostangenberg@universeprojects.com"
RUN mkdir /usr/local/event-server
WORKDIR /usr/local/event-server
ENTRYPOINT ["java", "-server", "-XX:+UseG1GC", "-Xms128M", "-Xmx512M", "-Dfile.encoding=UTF8", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005", "-jar", "eventServer.jar"]
ADD eventServer.jar /usr/local/event-server/
