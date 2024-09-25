package wsmc;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.buffer.ByteBuf;

public abstract class WebSocketHandler extends ChannelDuplexHandler {
	public final String outboundPrefix;
	public final String inboundPrefix;

	public WebSocketHandler(String inboundPrefix, String outboundPrefix) {
		this.inboundPrefix = inboundPrefix;
		this.outboundPrefix = outboundPrefix;
	}

	public static void dumpByteArray(ByteBuf byteArray) {
		if (!WSMC.dumpBytes)
			return;

		int maxBytesPerLine = 32;
		int totalBytes = byteArray.readableBytes();
		byteArray.markReaderIndex();

		for (int i = 0; i < totalBytes; i += maxBytesPerLine) {
			int remainingBytes = Math.min(maxBytesPerLine, totalBytes - i);
			StringBuilder line = new StringBuilder();

			for (int j = 0; j < remainingBytes; j++) {
				byte currentByte = byteArray.readByte();
				line.append(String.format("%02X ", currentByte));
			}

			System.out.println(line.toString().trim());
		}
		byteArray.resetReaderIndex();
	}

	protected abstract void sendWsFrame(ChannelHandlerContext ctx, WebSocketFrame frame, ChannelPromise promise) throws Exception;

	@Override
	public final void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (msg instanceof ByteBuf) {
			ByteBuf byteBuf = (ByteBuf) msg;

			if (WSMC.debug()) {
				WSMC.debug(this.outboundPrefix + " (" +byteBuf.readableBytes() + "):");
				dumpByteArray(byteBuf);
			}

			var promises = new PromiseCombiner(ctx.executor());
			var currentIndex = byteBuf.readerIndex();
			while (currentIndex < byteBuf.writerIndex()) {
				var maxFrameSize = 1024 * 4;
				var frameSize = Math.min(maxFrameSize, byteBuf.writerIndex() - currentIndex);
				var frameData = byteBuf.retainedSlice(currentIndex, frameSize);
				var framePromise = ctx.newPromise();
				sendWsFrame(ctx, new BinaryWebSocketFrame(frameData), framePromise);
				promises.add((Future<Void>) framePromise);
				currentIndex += frameSize;
			}
			promises.finish(promise);
			byteBuf.release();
		} else {
			// DefaultFullHttpResponse
			WSMC.debug(this.outboundPrefix + " Passthrough: " + msg.getClass().getName());
			ctx.write(msg, promise);
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof WebSocketFrame) {
			if (msg instanceof BinaryWebSocketFrame) {
				ByteBuf content = ((WebSocketFrame) msg).content();

				if (WSMC.debug()) {
					WSMC.debug(this.inboundPrefix + " (" + content.readableBytes() + "):");
					dumpByteArray(content);
				}

				ctx.fireChannelRead(content);
			} else if (msg instanceof CloseWebSocketFrame) {
				WSMC.debug("CloseWebSocketFrame (" + ((CloseWebSocketFrame) msg).statusCode()
							+ ") received : " + ((CloseWebSocketFrame) msg).reasonText());
			} else {
				WSMC.debug("Unsupported WebSocketFrame: " + msg.getClass().getName());
			}
		}
	}

	public static class WebSocketServerHandler extends WebSocketHandler {
		public WebSocketServerHandler() {
			super("C->S", "S->C");
		}

		@Override
		protected void sendWsFrame(ChannelHandlerContext ctx, WebSocketFrame frame, ChannelPromise promise) throws Exception {
			ctx.write(frame, promise);
		}
	}
}
