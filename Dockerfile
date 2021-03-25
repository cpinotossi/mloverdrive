# https://docs.docker.com/engine/reference/builder/#env
# FROM java:8
# FROM openjdk:8-jre-alpine  
FROM gcr.io/distroless/java:8
# FROM gcr.io/distroless/java:latest
COPY ./target/sbapp-1.0-SNAPSHOT-jar-with-dependencies.jar /var/www/java/  
WORKDIR /var/www/java   
ENV _JAVA_OPTIONS="-Xmx2g -Xms2g" 
ENV VM_NAME=myDocker 
ENV PROBLEM_ROW=150
ENV PROBLEM_COL=5000
ENTRYPOINT ["java","-jar","sbapp-1.0-SNAPSHOT-jar-with-dependencies.jar"]
