# Gradle Wrapper bootstrap

The ZIP intentionally contains no opaque binary JAR. On the first `gradlew` or
`gradlew.bat` invocation, `scripts/bootstrap-gradle-wrapper.*` downloads the
official Gradle 8.13 wrapper JAR and verifies this pinned SHA-256 checksum:

`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`

After verification, the JAR can be committed if the repository policy permits
checked-in wrapper binaries. CI performs the same checksum verification.
