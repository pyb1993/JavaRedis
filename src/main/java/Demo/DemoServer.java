package Demo;

import Common.Logger;
import RedisServer.RedisServer;
import Common.RedisUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

// 使用两个线程来处理,一个处理网络IO,一个处理业务逻辑(因为Redis这种架构如果采取多线程,可能回导致数据竞争比较困难)
public class DemoServer {
    public static void main(String[] args) {
        //Logger.logLevel = 1;
        RedisServer server = new RedisServer("127.0.0.1",3333);
        int retryTimes = 1;
        while(retryTimes-- > 0) {
            try {
                server.start();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }
}