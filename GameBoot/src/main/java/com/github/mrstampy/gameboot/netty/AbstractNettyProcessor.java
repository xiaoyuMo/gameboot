/*
 *              ______                        ____              __ 
 *             / ____/___ _____ ___  ___     / __ )____  ____  / /_
 *            / / __/ __ `/ __ `__ \/ _ \   / __  / __ \/ __ \/ __/
 *           / /_/ / /_/ / / / / / /  __/  / /_/ / /_/ / /_/ / /_  
 *           \____/\__,_/_/ /_/ /_/\___/  /_____/\____/\____/\__/  
 *                                                 
 *                                 .-'\
 *                              .-'  `/\
 *                           .-'      `/\
 *                           \         `/\
 *                            \         `/\
 *                             \    _-   `/\       _.--.
 *                              \    _-   `/`-..--\     )
 *                               \    _-   `,','  /    ,')
 *                                `-_   -   ` -- ~   ,','
 *                                 `-              ,','
 *                                  \,--.    ____==-~
 *                                   \   \_-~\
 *                                    `_-~_.-'
 *                                     \-~
 * 
 *                       http://mrstampy.github.io/gameboot/
 *
 * Copyright (C) 2015 Burton Alexander
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 * 
 */
package com.github.mrstampy.gameboot.netty;

import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.mrstampy.gameboot.SystemId;
import com.github.mrstampy.gameboot.concurrent.GameBootConcurrentConfiguration;
import com.github.mrstampy.gameboot.controller.GameBootMessageController;
import com.github.mrstampy.gameboot.exception.GameBootException;
import com.github.mrstampy.gameboot.exception.GameBootRuntimeException;
import com.github.mrstampy.gameboot.exception.GameBootThrowable;
import com.github.mrstampy.gameboot.messages.AbstractGameBootMessage;
import com.github.mrstampy.gameboot.messages.AbstractGameBootMessage.Transport;
import com.github.mrstampy.gameboot.messages.GameBootMessageConverter;
import com.github.mrstampy.gameboot.messages.Response;
import com.github.mrstampy.gameboot.metrics.MetricsHelper;
import com.github.mrstampy.gameboot.processor.connection.AbstractConnectionProcessor;
import com.github.mrstampy.gameboot.processor.connection.ConnectionProcessor;
import com.github.mrstampy.gameboot.util.GameBootUtils;
import com.github.mrstampy.gameboot.util.RegistryCleaner;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;

/**
 * Superclass for Netty {@link ConnectionProcessor}s.
 */
public abstract class AbstractNettyProcessor extends AbstractConnectionProcessor<ChannelHandlerContext> {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /** The Constant MESSAGE_COUNTER. */
  protected static final String MESSAGE_COUNTER = "Netty Message Counter";

  /** The Constant FAILED_MESSAGE_COUNTER. */
  protected static final String FAILED_MESSAGE_COUNTER = "Netty Failed Message Counter";

  @Autowired
  private MetricsHelper helper;

  @Autowired
  private NettyConnectionRegistry registry;

  @Autowired
  private SystemId generator;

  @Autowired
  private RegistryCleaner cleaner;

  @Autowired
  private GameBootMessageConverter converter;

  @Autowired
  private GameBootUtils utils;

  /**
   * Post construct, invoke from {@link PostConstruct}-annotated subclass
   * methods.
   *
   * @throws Exception
   *           the exception
   */
  protected void postConstruct() throws Exception {
    if (!helper.containsCounter(MESSAGE_COUNTER)) {
      helper.counter(MESSAGE_COUNTER, getClass(), "inbound", "messages");
    }

    if (!helper.containsCounter(FAILED_MESSAGE_COUNTER)) {
      helper.counter(FAILED_MESSAGE_COUNTER, getClass(), "failed", "messages");
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.github.mrstampy.gameboot.netty.ConnectionProcessor#onConnection(io.
   * netty.channel.ChannelHandlerContext)
   */
  @Override
  public void onConnection(ChannelHandlerContext ctx) throws Exception {
    setSystemId(ctx, generator.next());

    log.info("Connected to {}, adding to registry with key {}", ctx.channel(), getSystemId());

    registry.putInGroup(NettyConnectionRegistry.ALL, ctx.channel());
    registry.put(getSystemId(), ctx.channel());
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.github.mrstampy.gameboot.netty.ConnectionProcessor#onDisconnection(io.
   * netty.channel.ChannelHandlerContext)
   */
  @Override
  public void onDisconnection(ChannelHandlerContext ctx) throws Exception {
    log.info("Disconnected from {}", ctx.channel());

    cleaner.cleanup(getSystemId());
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.github.mrstampy.gameboot.netty.ConnectionProcessor#onMessage(io.netty.
   * channel.ChannelHandlerContext, java.lang.Object)
   */
  @Override
  public void onMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
    helper.incr(MESSAGE_COUNTER);

    log.debug("Received message {} on {}", msg, ctx.channel());

    if (msg instanceof String) {
      onMessageImpl(ctx, (String) msg);
    } else if (msg instanceof byte[]) {
      onMessageImpl(ctx, (byte[]) msg);
    } else {
      log.error("Only strings or byte arrays: {} from {}. Disconnecting", msg.getClass(), ctx.channel());
      ctx.close();
    }
  }

  /**
   * On message impl, implement processing the message using one of the
   * executors in {@link GameBootConcurrentConfiguration}.
   *
   * @param ctx
   *          the ctx
   * @param msg
   *          the msg
   * @throws Exception
   *           the exception
   */
  protected abstract void onMessageImpl(ChannelHandlerContext ctx, byte[] msg) throws Exception;

  /**
   * On message impl, implement processing the message using one of the
   * executors in {@link GameBootConcurrentConfiguration}.
   *
   * @param ctx
   *          the ctx
   * @param msg
   *          the msg
   * @throws Exception
   *           the exception
   */
  protected abstract void onMessageImpl(ChannelHandlerContext ctx, String msg) throws Exception;

  /*
   * (non-Javadoc)
   * 
   * @see com.github.mrstampy.gameboot.processor.connection.ConnectionProcessor#
   * sendError(java.lang.Object,
   * com.github.mrstampy.gameboot.exception.GameBootThrowable)
   */
  public void sendError(ChannelHandlerContext ctx, GameBootThrowable e) {
    Response r = fail(null, e);

    try {
      sendMessage(ctx, converter.toJsonArray(r), r);
    } catch (Exception e1) {
      log.error("Unexpected exception", e1);
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.github.mrstampy.gameboot.processor.connection.ConnectionProcessor#
   * sendError(int, java.lang.Object, java.lang.String)
   */
  public void sendError(int code, ChannelHandlerContext ctx, String message) {
    Response r = fail(code, null, message);

    try {
      sendMessage(ctx, converter.toJsonArray(r), r);
    } catch (Exception e) {
      log.error("Unexpected exception", e);
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.github.mrstampy.gameboot.netty.ConnectionProcessor#process(io.netty.
   * channel.ChannelHandlerContext, java.lang.String)
   */
  @Override
  public <AGBM extends AbstractGameBootMessage> void process(ChannelHandlerContext ctx, String msg) throws Exception {
    GameBootMessageController controller = utils.getBean(GameBootMessageController.class);

    Response response = null;
    AGBM agbm = null;
    try {
      agbm = converter.fromJson(msg);

      if (!preProcess(ctx, agbm)) return;

      response = process(ctx, controller, agbm);
    } catch (GameBootException | GameBootRuntimeException e) {
      helper.incr(FAILED_MESSAGE_COUNTER);
      response = fail(agbm, e);
    } catch (Exception e) {
      helper.incr(FAILED_MESSAGE_COUNTER);
      log.error("Unexpected exception processing message {} on channel {}", msg, ctx.channel(), e);
      response = fail(UNEXPECTED_ERROR, agbm, "An unexpected error has occurred");
    }

    postProcess(ctx, agbm, response);

    if (response == null) return;

    String r = converter.toJson(response);

    sendMessage(ctx, r, response);
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.github.mrstampy.gameboot.netty.ConnectionProcessor#process(io.netty.
   * channel.ChannelHandlerContext, byte[])
   */
  @Override
  public <AGBM extends AbstractGameBootMessage> void process(ChannelHandlerContext ctx, byte[] msg) throws Exception {
    GameBootMessageController controller = utils.getBean(GameBootMessageController.class);

    Response response = null;
    AGBM agbm = null;
    try {
      agbm = converter.fromJson(msg);

      if (!preProcess(ctx, agbm)) return;

      response = process(ctx, controller, agbm);
    } catch (GameBootException | GameBootRuntimeException e) {
      helper.incr(FAILED_MESSAGE_COUNTER);
      response = fail(agbm, e);
    } catch (Exception e) {
      helper.incr(FAILED_MESSAGE_COUNTER);
      log.error("Unexpected exception processing message {} on channel {}", msg, ctx.channel(), e);
      response = fail(UNEXPECTED_ERROR, agbm, "An unexpected error has occurred");
    }

    postProcess(ctx, agbm, response);

    if (response == null) return;

    byte[] r = converter.toJsonArray(response);

    sendMessage(ctx, r, response);
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.github.mrstampy.gameboot.netty.ConnectionProcessor#process(io.netty.
   * channel.ChannelHandlerContext,
   * com.github.mrstampy.gameboot.controller.GameBootMessageController, AGBM)
   */
  @Override
  public <AGBM extends AbstractGameBootMessage> Response process(ChannelHandlerContext ctx,
      GameBootMessageController controller, AGBM agbm) throws Exception {
    if (agbm.getSystemId() == null) agbm.setSystemId(getSystemId());
    agbm.setTransport(Transport.NETTY);
    agbm.setLocal((InetSocketAddress) ctx.channel().localAddress());
    agbm.setRemote((InetSocketAddress) ctx.channel().remoteAddress());

    Response r = controller.process(agbm);
    processMappingKeys(r, ctx.channel());
    r.setSystemId(agbm.getSystemId());

    return r;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.github.mrstampy.gameboot.netty.ConnectionProcessor#sendMessage(io.netty
   * .channel.ChannelHandlerContext, java.lang.Object,
   * com.github.mrstampy.gameboot.messages.Response)
   */
  @Override
  public void sendMessage(ChannelHandlerContext ctx, Object msg, Response response) throws Exception {
    ChannelFuture f = sendMessage(ctx, msg);

    f.addListener(e -> log(e, response, ctx));
  }

  /**
   * Send message.
   *
   * @param ctx
   *          the ctx
   * @param msg
   *          the msg
   * @return the channel future
   */
  public ChannelFuture sendMessage(ChannelHandlerContext ctx, Object msg) {
    return ctx.channel().writeAndFlush(msg);
  }

  /**
   * Gets the system id.
   *
   * @return the system id
   */
  protected Long getSystemId() {
    return getSystemId(null);
  }

  private void processMappingKeys(Response r, Channel channel) {
    Comparable<?>[] keys = r.getMappingKeys();
    if (keys == null || keys.length == 0) return;

    for (int i = 0; i < keys.length; i++) {
      registry.put(keys[i], channel);
    }
  }

  private void log(Future<? super Void> f, Response response, ChannelHandlerContext ctx) {
    if (f.isSuccess()) {
      log.debug("Successfully sent {} to {}", response, ctx.channel());
    } else {
      log.error("Could not send {} for message {} to {}", response, ctx.channel(), f.cause());
    }
  }
}