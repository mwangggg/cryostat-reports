# cryostat-reports Project

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using: 
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/cryostat-reports-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

Native image mode requires registration of reflective classes and accesses. See this guide for detail:
https://www.graalvm.org/reference-manual/native-image/Agent/

In short, download the GraalVM CE distribution. Then do:
```bash
# replace with actual path to downloaded and extracted GraalVM
export JAVA_HOME=~/workspace/graalvm-ce-java11-19.3.6/
# run the application JAR with the tracing agent attached. This is a JVM-mode JAR!
$JAVA_HOME/bin/java -agentlib:native-image-agent=config-output-dir=graal-config -jar target/cryostat-reports-1.0.0-SNAPSHOT-runner.jar
# in another terminal, make a request to exercise the expected microservice code path. Some "fully-featured"
# JFR file should be used here - one that uses all possible event types used in rules analysis,
# so that all rule pathways execute
http -f :8080/report file@example.jfr
# ctrl-c to kill the running Quarkus application, then
# cp graal-config/* src/main/resources/
```

## Related Guides

- RESTEasy Reactive ([guide](https://quarkus.io/guides/resteasy-reactive)): Reactive implementation of JAX-RS with additional features. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it.

## Provided Code

### RESTEasy Reactive

Easily start your Reactive RESTful Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
