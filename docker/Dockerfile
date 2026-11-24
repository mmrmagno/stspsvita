FROM vitasdk/vitasdk:2026.08-20261123@sha256:579c6e00c5142fb59c980b6476f6120da54ee1ac832e973e60a43579153d4d4b AS sdk
ENV PATH=/usr/local/vitasdk/bin:$PATH
# The port was accepted on hardware with exactly these two revisions.
RUN git init -q /tmp/vitaShaRK && cd /tmp/vitaShaRK \
 && git fetch -q --depth 1 https://github.com/Rinnegatamante/vitaShaRK.git 5d99fe81a4a384c5e4e7a8cba2d851df8dbda5da \
 && git checkout -q FETCH_HEAD && make -j"$(nproc)" \
 && cp libvitashark.a "$VITASDK/arm-vita-eabi/lib/" && cp source/vitashark.h "$VITASDK/arm-vita-eabi/include/"
RUN git init -q /tmp/vitaGL && cd /tmp/vitaGL \
 && git fetch -q --depth 1 https://github.com/Rinnegatamante/vitaGL.git df342e89f33adaf5619c313628ada0d359524f30 \
 && git checkout -q FETCH_HEAD && make -j"$(nproc)" NO_DEBUG=1 \
 && cp libvitaGL.a "$VITASDK/arm-vita-eabi/lib/" && cp source/vitaGL.h "$VITASDK/arm-vita-eabi/include/"

FROM debian:trixie-slim@sha256:8aa5a7bf5a1955bddbe7d74fcdd0b91ab30e74362a8b73af672b0481b35b44a0
ARG GRADLE_VERSION=9.9.0
ARG GRADLE_SHA256=0b00b0ffe07c4d4789c12d4935a44a7057d55774b7540bd4a3f76f336deb177a
ARG REVISION=unknown
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential ca-certificates cmake curl ffmpeg imagemagick \
      openjdk-21-jdk-headless pngquant python3 unzip \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSLo /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
 && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - \
 && unzip -q /tmp/gradle.zip -d /opt && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle \
 && rm /tmp/gradle.zip
COPY --from=sdk /usr/local/vitasdk /usr/local/vitasdk
ENV VITASDK=/usr/local/vitasdk PATH=/usr/local/vitasdk/bin:/usr/local/bin:/usr/bin:/bin \
    STSV_REVISION=${REVISION}
COPY . /src
RUN cd /src/transpiler && GRADLE_USER_HOME=/opt/gradle-home gradle --no-daemon -q resolveDeps \
 && rm -rf /src/transpiler/.gradle /src/transpiler/build /opt/gradle-home/daemon \
 && chmod -R a+rX /src /opt/gradle-home \
 && install -d -m 1777 /build /out
COPY docker/entrypoint.sh /usr/local/bin/stspsvita-build
USER 1000:1000
WORKDIR /build
ENTRYPOINT ["/usr/local/bin/stspsvita-build"]
