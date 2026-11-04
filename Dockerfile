# build with Maven, run on a plain JRE. Render's free instance has 512 MB, so the JVM
# gets a fixed heap that leaves room for the rest of the process
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/booking-system-*.jar app.jar
ENV JAVA_OPTS="-Xmx300m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
