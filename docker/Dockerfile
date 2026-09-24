# Disposable build container for the Nurgling2 (Haven & Hearth) client.
#
# Matches the toolchain the project's own CI builds with - see
# .github/workflows/build-pr.yml (Temurin JDK 17 + Apache Ant). The repo itself is
# never copied into this image; build.ps1 / build.sh bind-mount your checkout into
# the container at run time, so build outputs (build/, bin/, lib/ext/) land right
# back on your local disk and lib/ext (the downloaded jogl/lwjgl/steamworks jars)
# is cached across runs.
FROM eclipse-temurin:17-jdk

RUN apt-get update && \
    apt-get install -y --no-install-recommends ant && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
