package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hubspot.smtp.messages.MessageContent;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.concurrent.ImmediateEventExecutor;

public class SmtpSessionTest {
  private static final SmtpRequest SMTP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final MessageContent SMTP_CONTENT = MessageContent.of(Unpooled.copiedBuffer(new byte[1]));
  private static final SmtpResponse SMTP_RESPONSE = new DefaultSmtpResponse(250, "OK");
  private static final SmtpRequest MAIL_REQUEST = new DefaultSmtpRequest(SmtpCommand.MAIL, "FROM:alice@example.com");
  private static final SmtpRequest RCPT_REQUEST = new DefaultSmtpRequest(SmtpCommand.RCPT, "FROM:bob@example.com");
  private static final SmtpRequest DATA_REQUEST = new DefaultSmtpRequest(SmtpCommand.DATA);
  private static final SmtpRequest EHLO_REQUEST = new DefaultSmtpRequest(SmtpCommand.EHLO);
  private static final SmtpRequest NOOP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final SmtpRequest HELO_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELO);
  private static final SmtpRequest HELP_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELP);

  private static final ExecutorService EXECUTOR_SERVICE = MoreExecutors.newDirectExecutorService();

  private ResponseHandler responseHandler;
  private CompletableFuture<SmtpResponse[]> responseFuture;
  private Channel channel;
  private ChannelPipeline pipeline;
  private SmtpSession session;

  @Before
  public void setup() {
    channel = mock(Channel.class);
    pipeline = mock(ChannelPipeline.class);
    responseHandler = mock(ResponseHandler.class);

    responseFuture = new CompletableFuture<>();
    when(responseHandler.createResponseFuture(anyInt(), any())).thenReturn(responseFuture);
    when(channel.pipeline()).thenReturn(pipeline);

    session = new SmtpSession(channel, responseHandler, EXECUTOR_SERVICE);
  }
  
  @Test
  public void itSendsRequests() {
    session.send(SMTP_REQUEST);

    verify(channel).writeAndFlush(SMTP_REQUEST);
  }

  @Test
  public void itSendsContents() {
    session.send(SMTP_CONTENT);

    verify(channel).write(SMTP_CONTENT.get7BitEncodedContent());
    verify(channel).write(EMPTY_LAST_CONTENT);
    verify(channel).flush();
  }

  @Test
  public void itWrapsTheResponse() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);

    responseFuture.complete(new SmtpResponse[] { SMTP_RESPONSE });

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getSession()).isEqualTo(session);
    assertThat(future.get().code()).isEqualTo(SMTP_RESPONSE.code());
    assertThat(future.get().details()).isEqualTo(SMTP_RESPONSE.details());
  }

  @Test
  public void itExecutesReturnedFuturesOnTheProvidedExecutor() {
    ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("SmtpSessionTestExecutor").build());
    SmtpSession session = new SmtpSession(channel, responseHandler, executorService);

    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);
    CompletableFuture<Void> assertionFuture = future.thenRun(() -> assertThat(Thread.currentThread().getName()).contains("SmtpSessionTestExecutor"));

    responseFuture.complete(new SmtpResponse[] { SMTP_RESPONSE });
    assertionFuture.join();
  }

  @Test
  public void itExecutesReturnedExceptionalFuturesOnTheProvidedExecutor() {
    ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("SmtpSessionTestExecutor").build());
    SmtpSession session = new SmtpSession(channel, responseHandler, executorService);

    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);
    CompletableFuture<Boolean> assertionFuture = future.handle((r, e) -> {
      assertThat(Thread.currentThread().getName()).contains("SmtpSessionTestExecutor");
      return true;
    });

    responseFuture.completeExceptionally(new RuntimeException());
    assertionFuture.join();
  }

  @Test
  public void itSendsPipelinedRequests() {
    session.sendPipelined(SMTP_CONTENT, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(SMTP_CONTENT.get7BitEncodedContent());
    order.verify(channel).write(EMPTY_LAST_CONTENT);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).write(RCPT_REQUEST);
    order.verify(channel).write(DATA_REQUEST);
    order.verify(channel).flush();
  }

  @Test
  public void itCanSendASingleCommandWithPipelined() {
    // this is the same as just calling send
    session.sendPipelined(MAIL_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).flush();
  }

  @Test
  public void itChecksPipelineArgumentsAreValid() {
    assertPipelineError("DATA must appear last in a pipelined request", DATA_REQUEST, MAIL_REQUEST);
    assertPipelineError("EHLO must appear last in a pipelined request", EHLO_REQUEST, MAIL_REQUEST);
    assertPipelineError("NOOP must appear last in a pipelined request", NOOP_REQUEST, MAIL_REQUEST);

    assertPipelineError("HELO cannot be used in a pipelined request", HELO_REQUEST);
    assertPipelineError("HELP cannot be used in a pipelined request", HELP_REQUEST);
  }

  private void assertPipelineError(String message, SmtpRequest... requests) {
    assertThatThrownBy(() -> session.sendPipelined(requests))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
  }

  @Test
  public void itWrapsTheResponsesWhenPipelining() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse[]> future = session.sendPipelined(SMTP_CONTENT, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    SmtpResponse[] responses = {SMTP_RESPONSE, SMTP_RESPONSE, SMTP_RESPONSE, SMTP_RESPONSE};
    responseFuture.complete(responses);

    // 4 responses expected: one for the content, 3 for the requests
    verify(responseHandler).createResponseFuture(eq(4), any());

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().length).isEqualTo(responses.length);
    assertThat(future.get()[0].getSession()).isEqualTo(session);
    assertThat(future.get()[0].code()).isEqualTo(SMTP_RESPONSE.code());
  }

  @Test
  public void itExpectsTheRightNumberOfResponsesWhenPipelining() {
    session.sendPipelined(RCPT_REQUEST, DATA_REQUEST);

    // 1 response expected for each request
    verify(responseHandler).createResponseFuture(eq(2), any());
  }

  @Test
  public void itRecordsSupportedExtensions() {
    session.setSupportedExtensions(EnumSet.of(SupportedExtensions.EIGHT_BIT_MIME));

    assertThat(session.isSupported(SupportedExtensions.EIGHT_BIT_MIME)).isTrue();
    assertThat(session.isSupported(SupportedExtensions.PIPELINING)).isFalse();
  }

  @Test
  public void itClosesTheUnderlyingChannel() {
    DefaultChannelPromise channelPromise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
    when(channel.close()).thenReturn(channelPromise);

    CompletableFuture<Void> f = session.close();
    channelPromise.setSuccess();

    assertThat(f.isDone());
  }

  @Test
  public void itCompletesCloseFutureWhenTheConnectionIsClosed() throws Exception {
    assertThat(session.getCloseFuture().isDone()).isFalse();

    getErrorHandler().channelInactive(mock(ChannelHandlerContext.class));

    assertThat(session.getCloseFuture().isDone()).isTrue();
  }

  @Test
  public void itCompletesCloseFutureExceptionallyWhenTheConnectionIsClosed() throws Exception {
    ChannelInboundHandler errorHandler = getErrorHandler();

    Exception testException = new Exception();
    ChannelHandlerContext context = mock(ChannelHandlerContext.class);

    errorHandler.exceptionCaught(context, testException);

    verify(context).close();

    errorHandler.channelInactive(context);

    assertThat(session.getCloseFuture().isCompletedExceptionally()).isTrue();
    assertThatThrownBy(() -> session.getCloseFuture().get()).hasCause(testException);
  }

  private ChannelInboundHandler getErrorHandler() {
    ArgumentCaptor<ChannelHandler> captor = ArgumentCaptor.forClass(ChannelHandler.class);
    verify(pipeline).addLast(captor.capture());
    return (ChannelInboundHandler) captor.getValue();
  }
}
