package RedisServer;

import CommandDispatcher.CommandDispatcher;
import MessageRegister.MessageRegister;
import RedisCommand.MessageEncoder;
import RedisFuture.ExpireFuture;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisTimerWheel;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import Common.*;
import RedisCommand.*;

import java.util.LinkedList;
import java.util.concurrent.*;


public class RedisServer {
    private String ip;
    private int port;
    private static EventLoopGroup acceptGroup = new NioEventLoopGroup(1);
    private static LinkedList<ExpireFuture> queue = new LinkedList<>(); // 用来处理定时任务结果的
    static public final ScheduledExecutorService ExpireHelper = Executors.newScheduledThreadPool(1);// 用来在处理大量过期事件时候进行帮助的线程

    /** 如果需要添加自己的命令,只需要继承原来的RedisServer
     * 然后在构造函数里面调用 MessageRegister.registerDefault().register(xxx).register(yyy) 就好**/
    public RedisServer(String ip, int port){
        this.ip = ip;
        this.port = port;

        //rehashThread = Executors.newCachedThreadPool();// 用来在rehash的时候提交的
        MessageRegister.registerDefault();// 注册默认的那些命令比如set get incr
    }

    public void start() throws Exception{
        try{
            ServerBootstrap b = new ServerBootstrap();// 接受链接一个group,IO一个group
            // 设置所有的属性, serverBootstrap实际上会调用group(group,group),因为需要两个group来分配EventLoop
            b.group(acceptGroup).
                    channel(NioServerSocketChannel.class).
                    childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(new IdleStateHandler(60,60,180))
                                    .addLast(new MessageEncoder());

                            ch.pipeline()
                                    .addLast(new MessageDecoder())
                                    .addLast(new CommandDispatcher());

                        }});

            b.option(ChannelOption.SO_BACKLOG, 8192)  // socket接受队列大小
                    .option(ChannelOption.SO_REUSEADDR, true) // 避免端口冲突
                    .option(ChannelOption.TCP_NODELAY, true) // 关闭小流合并，保证消息的及时性
                    .childOption(ChannelOption.SO_KEEPALIVE, true); // 长时间没动静的链接自动关闭

            ChannelFuture f = b.bind(this.ip,this.port).sync();
            Logger.log(RedisServer.class.getName() + "started and listen on " + f.channel().localAddress());
            // 10ms执行一次,用来更新系统时间
            acceptGroup.scheduleAtFixedRate(()->RedisTimerWheel.updateSystemTime(),0,10,TimeUnit.MILLISECONDS);
            // 每250ms执行一次对过期数据的删除
            acceptGroup.scheduleAtFixedRate(()->{
                    try{
                        RedisDb.processExpires();
                    }catch (Exception e){
                        e.printStackTrace();
                    }},1,250,TimeUnit.MILLISECONDS);
            // 每25ms执行一次,用来执行 移除过期key 任务完成的回调
            acceptGroup.scheduleAtFixedRate(()->RedisServer.onComplete(),2,25,TimeUnit.MILLISECONDS);

            f.channel().closeFuture().sync();
            Logger.log("close done");
        }finally {
            acceptGroup.shutdownGracefully().sync();
        }
    }


    public static void addFuture(ExpireFuture future){
        queue.add(future);
    }

    // 用来检查所有的回调有没有执行完全
    public static void onComplete(){
        int size = 50;// 每次处理最多50个任务的回调,目前任务的回调都很简单,所以应该瞬间执行完
        ExpireFuture ef;
        while (size-- > 0 && (ef = queue.poll())!= null){
            if(ef.isDone()){
                ef.onComplete();
            }else {
                queue.add(ef);
            }
        }

    }
}




