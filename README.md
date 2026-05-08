# miniterm

A minimal terminal library.

## Building

```bash
./mvnw clean install
```

## Running examples

```bash
./jbang --java 22+ -R--enable-native-access=ALL-UNNAMED --cp target/miniterm-*.jar src/test/java/examples/PrintKeys.java
./jbang --java 22+ -R--enable-native-access=ALL-UNNAMED --cp target/miniterm-*.jar src/test/java/examples/PrintSize.java
./jbang --java 22+ -R--enable-native-access=ALL-UNNAMED --cp target/miniterm-*.jar src/test/java/examples/WatchSize.java
```
