package Demo;

import RedisServer.RedisServer;

// 使用两个线程来处理,一个处理网络IO,一个处理业务逻辑(因为Redis这种架构如果采取多线程,可能回导致数据竞争比较困难)
public class DemoServer {
    public static void main(String[] args) throws Exception{
        //Logger.logLevel = 1;
        System.out.println(ProcessHandle.current().pid());
        RedisServer server = new RedisServer("127.0.0.1",12306);
        server.start();
    }
}