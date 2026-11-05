FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY management/pom.xml management/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY management/src management/src
RUN mvn -B -pl management -am package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/management/target/management-1.0.0.jar app.jar
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
