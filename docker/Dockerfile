FROM eclipse-temurin:25-jdk

WORKDIR /app

COPY fg.crt /usr/local/share/ca-certificates/
RUN update-ca-certificates

RUN keytool -importcert \
    -keystore $JAVA_HOME/lib/security/cacerts \
    -storepass changeit \
    -noprompt \
    -alias fortigate \
    -file /usr/local/share/ca-certificates/fg.crt

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml /app/pom.xml
COPY src /app/src
COPY cfg /app/cfg
COPY deploy /app/deploy

RUN mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=.cp.txt

CMD ["sh", "-lc", "java -cp \"target/classes:$(cat .cp.txt)\" org.jpos.q2.Q2 -d deploy -n Q2-DOCKER"]