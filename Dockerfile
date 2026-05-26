FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/planificador-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir -p /app/data

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]