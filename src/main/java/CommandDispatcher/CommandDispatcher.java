package CommandDispatcher;
import Common.Logger;
import MessageRegister.MessageRegister;
import MessageInput.MessageInput;
import RedisCommand.RedisCommandHandler;
import RedisDataBase.RedisString;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.*;
import java.util.concurrent.*;

/*
* 这个类实际上是用来处理传入的Message的
* 根据type, 可以得到对应的输入类型和handler
* 这里可以写成单例模式,但是为了图简单就没有这样做
* */
public class CommandDispatcher extends ChannelInboundHandlerAdapter {
    static Set<RedisString> protocalSet = new HashSet<>() {
        {this.add(new RedisString("get"));
        this.add(new RedisString("set"));
        this.add(new RedisString("hget"));
        this.add(new RedisString("expire"));
        }
    };


    // 业务线程池,用来执行各种业务
    private static ThreadPoolExecutor executor;

    public static boolean newProtocal(RedisString key){
        return protocalSet.contains(key);
    }

    public static void closeGracefully() {
        // 优雅一点关闭，先通知，再等待，最后强制关闭
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        executor.shutdownNow();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 客户端走了一个
        //Logger.debug(ctx.channel() + "connection leaves");
        ctx.close();// 客户端已经主动关闭了
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof MessageInput) {
            // 用业务线程池处理消息 || 直接在IO线程里面进程处理
            this.handleCommand(ctx, (MessageInput) msg);
        }
    }


    /**  获取消息注册的信息,然后使用
         注意,这里将 handler的类型强制转换成了 IMessageHandler<Object>
         但是真正调用的时候由于还是虚函数调用,所以还是可以调用到真正的handler
         所以这里本质上应该只不过是让编译器的时候通过而已
         注意,这里要深刻的理解范型擦除的作用,其实handler所有的类都是一样的,所以调用没有问题

         -------
        这里使用将执行Reactor模式里面的Dispatcher的逻辑,把需要的东西传递到具体的handler里面
    **/
    private void handleCommand(ChannelHandlerContext ctx, MessageInput input) throws Exception {
        RedisString type = input.getType();
        RedisString requestId = input.getRequestId();
        Class<?> clazz = MessageRegister.getMessage(type);// 有可能为null,说明没有注册
        RedisCommandHandler<Object> handler = (RedisCommandHandler<Object>) MessageRegister.getHandler(type);
        if(clazz == null){
            throw new Exception("Unknonw type");
        }

        // todo 为了避免使用String,一个做法是: MessageInput就使用RedisString
        // todo getPayLoad的逻辑修改为对协议进行解析和fastJson一起使用
        // todo 首先解决RedisStringPair的问题,+代表单行字符串\r\n代表结尾,$代表多行字符,\r\n代表换行
        // todo 所以针对get/set/expire命令,就直接计算 RedisString然后传入
        // 达到的目的是 FastJson序列化和自定义二进制协议可以同时保证
        // 必须要释放type！！！！！！！！！！！！！
        if(newProtocal(type)){
            handler.handle(ctx, requestId, input.getContent());
        }else{
            Object o = input.getPayload(clazz);
            handler.handle(ctx, requestId, o);
        }

        type.release();// 注意,释放type很重要,否则会造成对象泄漏
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Logger.debug("connection error");
        cause.printStackTrace();
        ctx.close();
    }

}

