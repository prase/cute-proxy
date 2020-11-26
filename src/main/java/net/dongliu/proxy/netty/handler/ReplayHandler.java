package net.dongliu.proxy.netty.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.ReferenceCountUtil;
import net.dongliu.proxy.netty.NettyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.dongliu.proxy.netty.NettyUtils.causedByClientClose;
import static net.dongliu.proxy.netty.handler.HttpProxyHandler.PLAYER_THREAD;
import static net.dongliu.proxy.netty.handler.HttpProxyHandler.startPlayerThread;

/**
 * Handler tunnel proxy traffic, for socks proxy or http connect proxy.
 */
public class ReplayHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ReplayHandler.class);

    private final Channel targetChannel;
    private String url;

    public ReplayHandler(Channel targetChannel) {
        this.targetChannel = targetChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        logger.debug("from {} to {}, replay message: {}", ctx.channel().remoteAddress(),
                targetChannel.remoteAddress(), msg.getClass());
        ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
        if (targetChannel.isActive()) {
            boolean isStream = false;
            if (msg instanceof DefaultHttpRequest) {
                DefaultHttpRequest request = (DefaultHttpRequest) msg;
                String uri = "https://" + request.headers().get("Host") + request.uri();
                if (request.uri().contains("m3u8") && !uri.equals(url) && request.method().equals(HttpMethod.GET)) {
                    this.url = uri;
                    if (PLAYER_THREAD != null && !PLAYER_THREAD.isInterrupted()) {
                        PLAYER_THREAD.interrupt();
                        startPlayerThread(this.url, "X-Playback-Session-Id:" + request.headers().get("X-Playback-Session-Id"));
                    } else {
                        startPlayerThread(this.url, "X-Playback-Session-Id:" + request.headers().get("X-Playback-Session-Id"));
                    }
                    isStream = true;
                }
            }
            if (!isStream) {
                targetChannel.writeAndFlush(msg);
            }

        } else {
            logger.debug("proxy target channel {} inactive", targetChannel.remoteAddress());
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (targetChannel.isActive()) {
            NettyUtils.closeOnFlush(targetChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        if (causedByClientClose(e)) {
            logger.debug("client closed connection: {}", e.getMessage());
        } else {
            logger.error("something error", e);
        }
        NettyUtils.closeOnFlush(ctx.channel());
    }
}
