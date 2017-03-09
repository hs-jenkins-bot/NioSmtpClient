package com.hubspot.smtp.client;

import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import org.immutables.value.Value.Check;
import org.immutables.value.Value.Default;
import org.immutables.value.Value.Immutable;

import com.google.common.base.Preconditions;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.ssl.SslContextBuilder;

@Immutable
public abstract class SmtpSessionConfig {
  public abstract InetSocketAddress getRemoteAddress();
  public abstract Optional<InetSocketAddress> getLocalAddress();
  public abstract Optional<Duration> getKeepAliveTimeout();

  @Default
  public Duration getReadTimeout() {
    return Duration.ofMinutes(2);
  }

  @Default
  public String getConnectionId() {
    return "unidentified-connection";
  }

  @Default
  public ByteBufAllocator getAllocator() {
    return PooledByteBufAllocator.DEFAULT;
  }

  @Default
  public TrustManagerFactory getTrustManagerFactory() {
    try {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore) null);
      return trustManagerFactory;
    } catch (Exception e) {
      throw new RuntimeException("Could not create TrustManagerFactory", e);
    }
  }

  @Check
  protected void check() {
    Preconditions.checkState(!getKeepAliveTimeout().orElse(Duration.ofSeconds(1)).isZero(),
        "keepAliveTimeout must not be zero; use Optional.empty() to disable keepalive");
  }

  public SSLEngine createSSLEngine() {
    try {
      return SslContextBuilder
          .forClient()
          .trustManager(getTrustManagerFactory())
          .build()
          .newEngine(getAllocator());
    } catch (SSLException e) {
      throw new RuntimeException("Could not create SSLEngine", e);
    }
  }

  public static ImmutableSmtpSessionConfig forRemoteAddress(String host, int port) {
    return forRemoteAddress(InetSocketAddress.createUnresolved(host, port));
  }

  public static ImmutableSmtpSessionConfig forRemoteAddress(InetSocketAddress remoteAddress) {
    return ImmutableSmtpSessionConfig.builder().remoteAddress(remoteAddress).build();
  }
}
