FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data /app/logs && chown -R 10001:10001 /app
COPY --from=build /build/target/cczx-RadioStation.jar /app/app.jar
USER 10001:10001
ENV DATA_DIR=/app/data
EXPOSE 80
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
