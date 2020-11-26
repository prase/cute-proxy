package net.dongliu.proxy.netty.handler;

import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import net.dongliu.commons.net.HostPort;
import net.dongliu.proxy.MessageListener;
import net.dongliu.proxy.netty.NettySettings;
import net.dongliu.proxy.netty.NettyUtils;

/**
 * Handle http 1.x proxy request
 */
public class HttpProxyHandler extends ChannelInboundHandlerAdapter {
	private static final Logger logger = LoggerFactory.getLogger(HttpProxyHandler.class);
	private static Thread PLAYER_THREAD;

	private final Bootstrap bootstrap = new Bootstrap();
	private Channel clientOutChannel;
	private HostPort address;

	private final Queue<HttpContent> queue = new ArrayDeque<>();

	private final MessageListener messageListener;
	private final Supplier<ProxyHandler> proxyHandlerSupplier;
	private String url;

	public HttpProxyHandler(MessageListener messageListener, Supplier<ProxyHandler> proxyHandlerSupplier) {
		this.messageListener = messageListener;
		this.proxyHandlerSupplier = proxyHandlerSupplier;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof HttpObject)) {
			logger.error("got unknown message type: {}", msg.getClass());
			return;
		}

		if (msg instanceof HttpRequest) {
			HttpRequest request = (HttpRequest) msg;
			logger.debug("http proxy request received: {}", request.uri());
			URL url = new URL(request.uri());
			logger.debug("proxy http url: {}", url);
			String host = url.getHost();
			int port = url.getPort();
			String authority = url.getAuthority();
			request.setUri(url.getFile());
			if (!request.headers().contains("Host")) {
				request.headers().set("Host", host);
			}
			stripRequest(request);

			if (port == -1) {
				port = 80;
			}
			var address = HostPort.of(host, port);

			if (clientOutChannel != null && clientOutChannel.isActive() && address.equals(this.address)) {
				clientOutChannel.writeAndFlush(request);
				return;
			}

			ctx.channel().config().setAutoRead(false);

			if (clientOutChannel != null) {
				if (clientOutChannel.isActive()) {
					NettyUtils.closeOnFlush(clientOutChannel);
				}
				clientOutChannel = null;
			}

			logger.debug("begin creating new connection to {}", address);
			final String s = url.toExternalForm();
			if ((address.host().contains("mynbox.tv") || address.host().contains("s001.bgtv.stream")) && url != null && s != null && s.contains("m3u8") && !s.equals(this.url) && !s.contains("wness-hd")) {
				this.url = s;
				if (PLAYER_THREAD != null && !PLAYER_THREAD.isInterrupted()) {
					PLAYER_THREAD.interrupt();
					startPlayerThread(this.url, "X-Playback-Session-Id:" + request.headers().get("X-Playback-Session-Id"));
				} else {
					startPlayerThread(this.url, "X-Playback-Session-Id:" + request.headers().get("X-Playback-Session-Id"));
				}
			} else if (s.contains("wness-hd") && PLAYER_THREAD != null) {
				PLAYER_THREAD.interrupt();
			} else {
				Future<Channel> future = newChannel(ctx, address);
				future.addListener((FutureListener<Channel>) f -> {
					logger.debug("new connection to {} established", address);
					Channel channel = f.getNow();
					this.clientOutChannel = channel;
					this.address = address;
					channel.writeAndFlush(request);
					ctx.channel().config().setAutoRead(true);
					flushQueue();
				});
			}

			return;
		}

		if (msg instanceof HttpContent) {
			HttpContent httpContent = (HttpContent) msg;
			queue.add(httpContent);
			flushQueue();
		}
	}

	//    public void start(Stage primaryStage) {
	//        StackPane root = new StackPane();
	//        MediaPlayer player = new MediaPlayer(
	//          new Media(getClass().getResource(arg0).toExternalForm()));
	//        MediaView mediaView = new MediaView(player);
	//        root.getChildren().add( mediaView);
	//        Scene scene = new Scene(root, 1024, 768);
	//        primaryStage.setScene(scene);
	//        primaryStage.show();
	//        player.play();
	//    }

	private static void startPlayerThread(final String url, final String header) {
		PLAYER_THREAD = new Thread("player") {
			protected Process play;

			@Override
			public void run() {
				try {
					startPlayer();
				} catch (IOException | InterruptedException e) {
					interrupt();
				}
			}

			@Override
			public void interrupt() {
				play.destroyForcibly();
				super.interrupt();

			}

			private void startPlayer() throws IOException, InterruptedException {
				ProcessBuilder pb = new ProcessBuilder("mpv", "--fs",
				  "--user-agent='AppleCoreMedia/1.0.0.17A577 (iPhone; U; CPU OS 13_0 like Mac OS X; en_us)'",
				  "--http-header-fields='" + header + "' ", url);
				pb.inheritIO();
				pb.redirectError(new File("/tmp/mpv.log"));
				play = pb.start();
				play.waitFor();
			}
		};

		//PLAYER_THREAD.setDaemon(true);
		PLAYER_THREAD.start();
	}

	private void flushQueue() {
		if (clientOutChannel == null) {
			return;
		}
		boolean wrote = false;
		while (true) {
			HttpContent httpContent = queue.poll();
			if (httpContent == null) {
				break;
			}
			clientOutChannel.write(httpContent);
			wrote = true;
		}

		if (wrote) {
			clientOutChannel.flush();
		}
	}

	private Future<Channel> newChannel(ChannelHandlerContext ctx, HostPort address) {
		Promise<Channel> promise = ctx.executor().newPromise();
		bootstrap.group(ctx.channel().eventLoop())
		  .channel(NioSocketChannel.class)
		  .option(CONNECT_TIMEOUT_MILLIS, NettySettings.CONNECT_TIMEOUT)
		  .option(SO_KEEPALIVE, true)
		  .handler(new ChannelInitializer<SocketChannel>() {
			  @Override
			  protected void initChannel(SocketChannel ch) {
				  if (proxyHandlerSupplier != null) {
					  ProxyHandler proxyHandler = proxyHandlerSupplier.get();
					  ch.pipeline().addLast(proxyHandler);
				  }
				  ch.pipeline().addLast(new ChannelActiveAwareHandler(promise));
			  }
		  });

		bootstrap.connect(address.host(), address.ensurePort()).addListener((ChannelFutureListener) future -> {
			if (!future.isSuccess()) {
				ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY));
				NettyUtils.closeOnFlush(ctx.channel());
			}
		});

		promise.addListener((FutureListener<Channel>) f -> {
			if (!f.isSuccess()) {
				ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY));
				NettyUtils.closeOnFlush(ctx.channel());
				return;
			}
			Channel channel = f.getNow();
			channel.pipeline().addLast("http-client-codec", new HttpClientCodec());
			HttpInterceptor interceptor = new HttpInterceptor(false, address, messageListener);
			channel.pipeline().addLast(interceptor);
			channel.pipeline().addLast("http-tunnel-handler", new ReplayHandler(ctx.channel()));
		});
		return promise;
	}

	private void stripRequest(HttpRequest request) {
		request.headers().remove("Proxy-Authenticate");
		request.headers().remove("Proxy-Connection");
		request.headers().remove("Expect");
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		if (clientOutChannel != null) {
			NettyUtils.closeOnFlush(clientOutChannel);
		}
		releaseQueue();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.error("", cause);
		NettyUtils.closeOnFlush(ctx.channel());
		releaseQueue();
	}

	private void releaseQueue() {
		while (true) {
			HttpContent httpContent = queue.poll();
			if (httpContent == null) {
				break;
			}
			httpContent.release();
		}
	}
}
