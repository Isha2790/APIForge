FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY management/pom.xml management/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY gateway/src gateway/src
RUN mvn -B -pl gateway -am package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/gateway/target/gateway-1.0.0.jar app.jar
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
