FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/planificador-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir -p /app/data

EXPOSE 8080

# Anti-OOM: heap acotado (el host de 3.8 GB lo comparten back + front + BD, así que -Xmx FIJO, no
# MaxRAMPercentage, que vería los 3.8 GB completos) + heap dump on OOM + GC log para diagnóstico.
# Ajustar -Xmx según lo que dejen libre la BD y el front en el host.
ENTRYPOINT ["java", \
  "-Xms256m", "-Xmx1g", \
  "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/data", \
  "-Xlog:gc*:file=/app/data/gc.log:time,uptime:filecount=5,filesize=10m", \
  "-jar", "app.jar"]