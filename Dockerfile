FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY gradlew.bat .
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY src src

RUN chmod +x gradlew
RUN ./gradlew buildFatJar --no-daemon

# Run stage
FROM eclipse-temurin:17-jre
EXPOSE 443
RUN mkdir /app
COPY --from=build /app/build/libs/*.jar /app/application.jar
ENTRYPOINT ["java", "-jar", "/app/application.jar"]