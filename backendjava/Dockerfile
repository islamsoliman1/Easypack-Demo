FROM maven:3.9-eclipse-temurin-21 AS dependencies
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline

FROM dependencies AS build
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S easyback && adduser -S easyback -G easyback
WORKDIR /app
COPY --from=build /workspace/target/easyback-api-0.1.0.jar app.jar
USER easyback
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
