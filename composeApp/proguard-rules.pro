# ProGuard rules for KubeKubeDashDash desktop app.
#
# Compose Desktop's defaults (default-compose-desktop-rules.pro, auto-applied)
# already cover Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization,
# kotlinx.datetime, Compose runtime + Skia/Skiko, and disable obfuscation
# (-dontobfuscate is injected by the plugin). This file only adds what's
# specific to the dependencies pulled in via libs.versions.toml.
#
# Strategy:
#   1. -dontwarn for optional/native classes that are referenced by
#      transitive deps (Netty, Vert.x, fabric8, Logback) but never bundled
#      because we don't pull in the matching runtime (Conscrypt, Jetty ALPN,
#      OpenSSL native, brotli4j, zstd, lz4, lzma, jzlib, JBoss Marshalling,
#      protobuf, jakarta.servlet/mail, GraalVM SVM, junit-jupiter, Lombok,
#      log4j, OSGi annotations, BlockHound, Janino, Apache commons logging).
#      Without these, ProGuard aborts at "Please correct the above warnings
#      first" before it even starts shrinking.
#
#   2. -keep rules for reflection-heavy libraries: fabric8 model classes
#      (Jackson polymorphic deserialization), the kubernetes-client SPI
#      registrations, Jackson itself, Logback's XML-driven configuration,
#      SLF4J providers, Ktor server engines, and the MCP Kotlin SDK
#      transport.

# ---------------------------------------------------------------------------
# Optional / never-on-classpath dependencies — silence the warnings.
# ---------------------------------------------------------------------------

# Netty optional codecs and native transports (epoll/kqueue/io_uring,
# OpenSSL via tcnative, Conscrypt, Jetty ALPN, JBoss Marshalling, brotli,
# zstd, lz4, jzlib, lzma, protobuf, BlockHound integration). All optional;
# fabric8/ktor pull netty-all-in-one and we don't ship any of these.
-dontwarn io.netty.internal.tcnative.**
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.channel.kqueue.**
-dontwarn io.netty.channel.uring.**
-dontwarn io.netty.handler.codec.compression.**
-dontwarn io.netty.handler.codec.marshalling.**
-dontwarn io.netty.handler.ssl.ConscryptAlpnSslEngine
-dontwarn io.netty.handler.ssl.ConscryptAlpnSslEngine$**
-dontwarn io.netty.handler.ssl.JettyAlpnSslEngine
-dontwarn io.netty.handler.ssl.JettyAlpnSslEngine$**
-dontwarn io.netty.handler.ssl.JettyNpnSslEngine
-dontwarn io.netty.handler.ssl.JettyNpnSslEngine$**
-dontwarn io.netty.handler.ssl.OpenSslSessionCache
-dontwarn io.netty.handler.ssl.ReferenceCountedOpenSslContext$**
-dontwarn io.netty.handler.ssl.ReferenceCountedOpenSslClientContext$**
-dontwarn io.netty.handler.ssl.ReferenceCountedOpenSslServerContext$**
-dontwarn io.netty.util.internal.logging.Log4J2Logger
-dontwarn io.netty.util.internal.logging.Log4J2Logger$*
-dontwarn org.jboss.marshalling.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn net.jpountz.lz4.**
-dontwarn net.jpountz.xxhash.**
-dontwarn lzma.sdk.**
-dontwarn com.ning.compress.**
-dontwarn com.google.protobuf.**
-dontwarn reactor.blockhound.**

# GraalVM Substrate VM annotations — only relevant for native-image builds.
-dontwarn com.oracle.svm.core.annotate.**

# Vert.x — fabric8's actual HTTP client transport in 7.x.
# `kubernetes-httpclient-vertx` is the only HttpClient$Factory shipped with
# this app, and Vert.x is heavy on reflection: enum-driven SSL config, codec
# discovery via class scanning, future composition via MethodHandles. Without
# broad keeps, SSLHelper.<clinit> fails with `EnumMap … keyUniverse is null`
# (ProGuard strips an enum's $VALUES if no direct user-code reference exists),
# every HttpClient construction then throws ExceptionInInitializerError, and
# the kubernetes client connect hangs silently.
-keep class io.vertx.** { *; }
-keepclassmembers enum io.vertx.** { *; }
-dontwarn io.vertx.**

# Netty — Vert.x's underlying transport. Same reflection-heavy story
# (handlers, codecs, allocators, attribute keys looked up by name). Strip
# anything and TLS / HTTP/2 / DNS resolution silently breaks at runtime.
-keep class io.netty.** { *; }
-keepclassmembers enum io.netty.** { *; }

# Vert.x's HybridJacksonPool calls MethodHandle#invokeExact reflectively
# with a non-canonical signature; ProGuard can't model that.
-dontwarn java.lang.invoke.MethodHandle

# Logback servlet integration and SMTP appender. Pulled in by logback-core
# but we ship neither jakarta.servlet nor jakarta.mail.
-dontwarn ch.qos.logback.classic.helpers.MDCInsertingServletFilter
-dontwarn ch.qos.logback.classic.selector.servlet.**
-dontwarn ch.qos.logback.classic.servlet.**
-dontwarn ch.qos.logback.core.net.LoginAuthenticator
-dontwarn ch.qos.logback.core.net.SMTPAppenderBase
-dontwarn ch.qos.logback.core.status.ViewStatusMessagesServletBase
-dontwarn jakarta.servlet.**
-dontwarn jakarta.mail.**

# Logback's optional Janino-based <if> conditionals.
-dontwarn org.codehaus.commons.compiler.**
-dontwarn org.codehaus.janino.**

# Logback-classic also references the legacy log4j 1.x and log4j2 APIs
# (LoggerFactory bridges). Optional unless those APIs are on the classpath.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.commons.logging.**

# OSGi metadata annotations on a few transitive jars.
-dontwarn org.osgi.annotation.**
-dontwarn org.osgi.service.**

# fabric8's kubernetes-server-mock declares a JUnit 5 extension; we don't
# ship junit-jupiter in the runtime distribution.
-dontwarn org.junit.jupiter.**

# Lombok-generated nullability annotations leak into a few jars.
-dontwarn lombok.Generated

# Netty HAProxy protocol decoder — never used; not on classpath.
-dontwarn io.netty.handler.codec.haproxy.**

# Apache Commons Compress — used by fabric8 only for `kubectl cp`-style
# tar streams between host and pod; we don't exercise that codepath, and
# the dependency is optional.
-dontwarn org.apache.commons.compress.**

# ---------------------------------------------------------------------------
# fabric8 kubernetes-client — heavy Jackson reflection.
# ---------------------------------------------------------------------------
# The client deserializes API responses into model classes via Jackson.
# Shrinking would strip every model that isn't directly referenced from
# user code (most of them, since dispatch happens by Kind/apiVersion at
# runtime). Keep the model packages and the client SPI.
-keep class io.fabric8.kubernetes.api.model.** { *; }
-keep class io.fabric8.openshift.api.model.** { *; }
-keep class io.fabric8.knative.** { *; }
-keep class io.fabric8.kubernetes.client.** { *; }
-keep class io.fabric8.kubernetes.client.utils.** { *; }
-keep class io.fabric8.kubernetes.client.dsl.** { *; }
-keep class io.fabric8.kubernetes.client.extension.** { *; }
-keep class io.fabric8.kubernetes.client.http.** { *; }
-keep class io.fabric8.kubernetes.client.impl.** { *; }
-keep class io.fabric8.zjsonpatch.** { *; }

# Annotations Jackson reads at runtime to drive (de)serialization.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep,allowobfuscation,allowshrinking class * extends io.fabric8.kubernetes.api.builder.Builder
-keep,allowobfuscation,allowshrinking class * extends io.fabric8.kubernetes.api.builder.Fluent

# fabric8 discovers KubernetesResource implementations and HasMetadata
# subtypes via @JsonTypeInfo + reflection. Keep the marker interfaces and
# every implementor.
-keep class * implements io.fabric8.kubernetes.api.model.KubernetesResource { *; }
-keep class * implements io.fabric8.kubernetes.api.model.HasMetadata { *; }

# ---------------------------------------------------------------------------
# Jackson — keep enough for fabric8 to function. Deserializer/serializer
# discovery runs by reflection over annotated members.
# ---------------------------------------------------------------------------
-keep class com.fasterxml.jackson.** { *; }
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**

# ---------------------------------------------------------------------------
# Logback — XML config loads classes by FQN.
# ---------------------------------------------------------------------------
-keep class ch.qos.logback.classic.** { *; }
-keep class ch.qos.logback.core.** { *; }
-keep class ch.qos.logback.classic.encoder.** { *; }
-keep class ch.qos.logback.core.encoder.** { *; }
-keep class ch.qos.logback.core.rolling.** { *; }

# ---------------------------------------------------------------------------
# SLF4J — provider lookup via ServiceLoader; keep impls and the API.
# ---------------------------------------------------------------------------
-keep class org.slf4j.** { *; }
-keep class org.slf4j.spi.** { *; }
-dontwarn org.slf4j.**

# ---------------------------------------------------------------------------
# Ktor server (CIO + SSE + content negotiation) — engines and plugins are
# loaded via ServiceLoader and reflection.
# ---------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-keep class io.ktor.server.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }
-keep class io.ktor.client.** { *; }
-dontwarn io.ktor.**

# ---------------------------------------------------------------------------
# MCP Kotlin SDK — the SDK uses ktor under the hood and registers
# transports reflectively.
# ---------------------------------------------------------------------------
-keep class io.modelcontextprotocol.** { *; }

# ---------------------------------------------------------------------------
# AndroidX DataStore (used for preferences persistence) + Okio.
# DataStore's JVM build is backed by `datastore-core-okio`, which is the
# only consumer of Okio in this app — keep both together.
# ---------------------------------------------------------------------------
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**
-keep class okio.** { *; }
-dontwarn okio.**

# ---------------------------------------------------------------------------
# BouncyCastle — fabric8 uses it for OIDC token signing on some auth flows.
# Reflection over provider classes.
# ---------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------------------
# SnakeYAML — fabric8 uses it to parse kubeconfig.
# ---------------------------------------------------------------------------
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# ---------------------------------------------------------------------------
# App entry point — already kept by Compose's auto-generated config, but
# being explicit doesn't hurt and survives any future plugin changes.
# ---------------------------------------------------------------------------
-keep class com.kubekubedashdash.MainKt {
    public static void main(java.lang.String[]);
}
-keep class com.kubekubedashdash.** { *; }
