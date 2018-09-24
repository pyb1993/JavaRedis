package RedisCommand;

import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;

// 所有MessageHandler的接口
public interface RedisCommandHandler<T> {
    void handle(ChannelHandlerContext ctx, RedisString requestId, T message);
}


