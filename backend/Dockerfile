FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
# Tuned for a 512 MB host (Render free): small heap, serial GC, small thread stacks.
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
ENTRYPOINT ["java", "-jar", "app.jar"]

