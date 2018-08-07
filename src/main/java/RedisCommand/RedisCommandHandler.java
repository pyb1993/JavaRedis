package RedisCommand;

import io.netty.channel.ChannelHandlerContext;

// 所有MessageHandler的接口
public interface RedisCommandHandler<T> {
    void handle(ChannelHandlerContext ctx, String requestId, T message);
}


