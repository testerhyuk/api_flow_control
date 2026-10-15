FROM eclipse-temurin:21-jre
WORKDIR /opt/app
COPY build/libs/mock-target-system.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
