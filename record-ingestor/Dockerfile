FROM eclipse-temurin:21-jre
WORKDIR /opt/app
COPY build/libs/record-ingestor.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
