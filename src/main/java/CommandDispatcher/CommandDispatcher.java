package CommandDispatcher;
import Common.Logger;
import MessageRegister.MessageRegister;
import MessageInput.MessageInput;
import RedisCommand.RedisCommandHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.serialization.ClassResolvers;

import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/*
* 这个类实际上是用来处理传入的Message的
* 根据type, 可以得到对应的输入类型和handler
* 这里可以写成单例模式,但是为了图简单就没有这样做
* */
public class CommandDispatcher extends ChannelInboundHandlerAdapter {
    // 业务线程池,用来执行各种业务
    private static ThreadPoolExecutor executor;
    static
    {
        /****** 初始化整个executor *******/
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1000);

        // 创造线程的工厂类,暂时没有进行任何调整
        ThreadFactory factory = new ThreadFactory() {
            AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Logger.debug("create thread");
                Thread t = new Thread(r);
                t.setName("rpc-" + seq.getAndIncrement());
                return t;
            }
        };

        // 闲置时间超过30秒的线程自动销毁
        executor = new ThreadPoolExecutor(1,
                1,
                30,
                TimeUnit.SECONDS,
                queue,
                factory,
                new CallerRunsPolicy());
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
        Logger.debug("connection leaves");
        ctx.close();// 客户端已经主动关闭了
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof MessageInput) {
            // 用业务线程池处理消息 || 直接在IO线程里面进程处理
            this.handleCommand(ctx, (MessageInput) msg);

       /*     executor.submit(() -> {
                this.handleCommand(ctx, (MessageInput) msg);
            });*/
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
    private void handleCommand(ChannelHandlerContext ctx, MessageInput input) {
        Class<?> clazz = MessageRegister.getMessage(input.getType());// 有可能为null,说明没有注册
        RedisCommandHandler<Object> handler = (RedisCommandHandler<Object>) MessageRegister.getHandler(input.getType());
        if(clazz == null){
            handler.handle(ctx, input.getRequestId(), input.getType());
            return;
        }

        // todo 实现成Redis自己的协议,这样就不需要使用fastJson来解析了,直接二进制传输,也就是不注册 get请求的Class,这样可以快一点
        // 达到的目的是 FastJson序列化和自定义二进制协议可以同时保证
        Object o = input.getPayload(clazz);
        handler.handle(ctx, input.getRequestId(), o);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Logger.debug("connection error");
        cause.printStackTrace();
        ctx.close();
    }

}

