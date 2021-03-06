package com.swingfrog.summer.server;

import com.swingfrog.summer.concurrent.MatchGroupKey;
import com.swingfrog.summer.concurrent.SessionQueueMgr;
import com.swingfrog.summer.concurrent.SingleQueueMgr;
import com.swingfrog.summer.ioc.ContainerMgr;
import com.swingfrog.summer.protocol.SessionRequest;
import com.swingfrog.summer.server.rpc.RpcClientMgr;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;

public abstract class AbstractServerHandler<T> extends SimpleChannelInboundHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractServerHandler.class);
    protected final ServerContext serverContext;

    protected AbstractServerHandler(ServerContext serverContext) {
        this.serverContext = serverContext;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        if (serverContext.getConfig().isAllowAddressEnable()) {
            String address = ((InetSocketAddress)ctx.channel().remoteAddress()).getHostString();
            String[] addressList = serverContext.getConfig().getAllowAddressList();
            boolean allow = false;
            for (String s : addressList) {
                if (address.equals(s)) {
                    allow = true;
                    break;
                }
            }
            if (!allow) {
                log.warn("not allow {} connect", address);
                ctx.close();
                return;
            }
            log.info("allow {} connect", address);
        }
        serverContext.getSessionContextGroup().createSession(ctx);
        SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
        if (!serverContext.getSessionHandlerGroup().accept(sctx)) {
            log.warn("not accept client {}", sctx);
            ctx.close();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
        log.info("added client {}", sctx);
        serverContext.getSessionHandlerGroup().added(sctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
        if (sctx != null) {
            log.info("removed client {}", sctx);
            serverContext.getSessionHandlerGroup().removed(sctx);
            serverContext.getSessionContextGroup().destroySession(ctx);
            RpcClientMgr.get().remove(sctx);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
        if (cause instanceof TooLongFrameException) {
            serverContext.getSessionHandlerGroup().lengthTooLongMsg(sctx);
        } else {
            log.error(cause.getMessage(), cause);
        }
    }

    protected void submitRunnable(SessionContext sctx, SessionRequest request, Runnable runnable) {
        Method method = RemoteDispatchMgr.get().getMethod(request);
        if (method != null) {
            MatchGroupKey matchGroupKey = ContainerMgr.get().getSingleQueueKey(method);
            if (matchGroupKey != null) {
                if (matchGroupKey.hasKeys()) {
                    Object[] partKeys = new Object[matchGroupKey.getKeys().size()];
                    for (int i = 0; i < matchGroupKey.getKeys().size(); i++) {
                        String key = request.getData().getString(matchGroupKey.getKeys().get(i));
                        if (key == null) {
                            key = "";
                        }
                        partKeys[i] = key;
                    }
                    SingleQueueMgr.get().execute(matchGroupKey.getMainKey(partKeys).intern(), runnable);
                } else {
                    SingleQueueMgr.get().execute(matchGroupKey.getMainKey().intern(), runnable);
                }
                return;
            }
        }
        SessionQueueMgr.get().execute(sctx, runnable);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, T t) throws Exception {
        SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
        long now = System.currentTimeMillis();
        long last = sctx.getLastRecvTime();
        sctx.setLastRecvTime(now);
        if ((now - last) < serverContext.getConfig().getColdDownMs()) {
            serverContext.getSessionHandlerGroup().sendTooFastMsg(sctx);
        }
        recv(ctx, sctx, t);
    }

    protected abstract void recv(ChannelHandlerContext ctx, SessionContext sctx, T request) throws Exception;

}
