package com.hubspot.smtp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpResponse;

public class ResponseHandlerTest {
  private static final DefaultSmtpResponse SMTP_RESPONSE = new DefaultSmtpResponse(250);
  private static final Supplier<String> DEBUG_STRING = () -> "debug";
  private static final String CONNECTION_ID = "connection#1";
  private static final String CONNECTION_ID_PREFIX = "[" + CONNECTION_ID + "] ";

  private ResponseHandler responseHandler;
  private ChannelHandlerContext context;

  @Before
  public void setup() {
    responseHandler = new ResponseHandler(CONNECTION_ID, Duration.ofMinutes(2));
    context = mock(ChannelHandlerContext.class);
  }

  @Test
  public void itCompletesExceptionallyIfAnExceptionIsCaught() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(1, DEBUG_STRING);
    Exception testException = new Exception("test");

    responseHandler.exceptionCaught(context, testException);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class).hasCause(testException);

    verify(context).fireExceptionCaught(testException);
  }

  @Test
  public void itCompletesWithAResponseWhenHandled() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(1, DEBUG_STRING);

    responseHandler.channelRead(context, SMTP_RESPONSE);

    assertThat(f.isCompletedExceptionally()).isFalse();
    assertThat(f.get()).isEqualTo(Lists.newArrayList(SMTP_RESPONSE));
  }

  @Test
  public void itDoesNotCompleteWhenSomeOtherObjectIsRead() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(1, DEBUG_STRING);

    responseHandler.channelRead(context, "unexpected");

    assertThat(f.isDone()).isFalse();
  }

  @Test
  public void itOnlyCreatesOneResponseFutureAtATime() {
    assertThat(responseHandler.createResponseFuture(1, () -> "old")).isNotNull();

    assertThatThrownBy(() -> responseHandler.createResponseFuture(1, () -> "new"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(CONNECTION_ID_PREFIX + "Cannot wait for a response to [new] because we're still waiting for a response to [old]");
  }

  @Test
  public void itOnlyCreatesOneResponseFutureAtATimeForMultipleResponses() {
    assertThat(responseHandler.createResponseFuture(2, () -> "old")).isNotNull();

    assertThatThrownBy(() -> responseHandler.createResponseFuture(1, () -> "new"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(CONNECTION_ID_PREFIX + "Cannot wait for a response to [new] because we're still waiting for a response to [old]");
  }

  @Test
  public void itCanCreateAFutureThatWaitsForMultipleReponses() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(3, DEBUG_STRING);
    SmtpResponse response1 = new DefaultSmtpResponse(250, "1");
    SmtpResponse response2 = new DefaultSmtpResponse(250, "2");
    SmtpResponse response3 = new DefaultSmtpResponse(250, "3");

    responseHandler.channelRead(context, response1);

    assertThat(f.isDone()).isFalse();

    responseHandler.channelRead(context, response2);
    responseHandler.channelRead(context, response3);

    assertThat(f.isDone()).isTrue();

    assertThat(f.isCompletedExceptionally()).isFalse();
    assertThat(f.get().get(0)).isEqualTo(response1);
    assertThat(f.get().get(1)).isEqualTo(response2);
    assertThat(f.get().get(2)).isEqualTo(response3);
  }

  @Test
  public void itCanCreateAFutureInTheCallbackForAPreviousFuture() throws Exception {
    CompletableFuture<List<SmtpResponse>> future = responseHandler.createResponseFuture(1, DEBUG_STRING);

    CompletableFuture<Void> assertion = future.thenRun(() -> assertThat(responseHandler.createResponseFuture(1, DEBUG_STRING)).isNotNull());

    responseHandler.channelRead(context, SMTP_RESPONSE);

    assertion.get();
  }

  @Test
  public void itCanFailMultipleResponseFuturesAtAnyTime() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(3, DEBUG_STRING);
    Exception testException = new Exception("test");

    responseHandler.exceptionCaught(context, testException);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class).hasCause(testException);
  }

  @Test
  public void itCanCreateNewFuturesOnceAResponseHasArrived() throws Exception {
    responseHandler.createResponseFuture(1, DEBUG_STRING);
    responseHandler.channelRead(context, SMTP_RESPONSE);

    responseHandler.createResponseFuture(1, DEBUG_STRING);
  }

  @Test
  public void itCanCreateNewFuturesOnceATheExpectedResponsesHaveArrived() throws Exception {
    responseHandler.createResponseFuture(2, DEBUG_STRING);
    responseHandler.channelRead(context, SMTP_RESPONSE);
    responseHandler.channelRead(context, SMTP_RESPONSE);

    responseHandler.createResponseFuture(1, DEBUG_STRING);
  }

  @Test
  public void itCanCreateNewFuturesOnceAnExceptionIsHandled() throws Exception {
    responseHandler.createResponseFuture(1, DEBUG_STRING);
    responseHandler.exceptionCaught(context, new Exception("test"));

    responseHandler.createResponseFuture(1, DEBUG_STRING);
  }

  @Test
  public void itCanTellWhenAResponseIsPending() {
    assertThat(responseHandler.isResponsePending()).isFalse();

    responseHandler.createResponseFuture(1, DEBUG_STRING);

    assertThat(responseHandler.isResponsePending()).isTrue();
  }

  @Test
  public void itCompletesExceptionallyIfTheChannelIsClosed() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(1, DEBUG_STRING);

    responseHandler.channelInactive(context);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(f::get)
        .hasCauseInstanceOf(ChannelClosedException.class)
        .hasMessageEndingWith(CONNECTION_ID_PREFIX + "Handled channelInactive while waiting for a response to [" + DEBUG_STRING.get() + "]");
  }

  @Test
  public void itCompletesExceptionallyIfTheResonseTimeoutIsExceeded() throws Exception {
    ResponseHandler impatientHandler = new ResponseHandler(CONNECTION_ID, Duration.ofMillis(200));

    CompletableFuture<List<SmtpResponse>> responseFuture = impatientHandler.createResponseFuture(1, DEBUG_STRING);
    assertThat(responseFuture.isCompletedExceptionally()).isFalse();

    Thread.sleep(300);
    assertThat(responseFuture.isCompletedExceptionally()).isTrue();
  }
}
