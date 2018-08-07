package RedisServer;

import CommandDispatcher.CommandDispatcher;
import MessageRegister.MessageRegister;
import RedisCommand.MessageEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import Common.*;
import RedisCommand.*;

import java.util.Map;
import java.util.Properties;


public class RedisServer {
    private String ip;
    private int port;
    private int ioThreads; // 用来处理网络流的读写线程

    /** 如果需要添加自己的命令,只需要继承原来的RedisServer
     * 然后在构造函数里面调用 MessageRegister.registerDefault().register(xxx).register(yyy) 就好**/
    public RedisServer(String ip, int port){
        this.ip = ip;
        this.port = port;

        MessageRegister.registerDefault();// 注册默认的那些命令比如set get incr
    }


    public void start() throws Exception{
        EventLoopGroup acceptGroup = new NioEventLoopGroup(1);
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
            f.channel().closeFuture().sync();
            Logger.log("close done");
        }finally {
            acceptGroup.shutdownGracefully().sync();
        }
    }

}




