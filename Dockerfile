FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/planificador-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir -p /app/data

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Xms256m", "-Xmx1228m", \
  "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/data", \
  "-Xlog:gc*:file=/app/data/gc.log:time,uptime:filecount=5,filesize=10m", \
  "-jar", "app.jar"]