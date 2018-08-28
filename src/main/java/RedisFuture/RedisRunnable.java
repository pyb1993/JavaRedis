package RedisFuture;

import java.io.IOException;
import java.io.UncheckedIOException;

/* 自己用来打log,否则会被Netty吞掉,并且取消任务  */
public class RedisRunnable implements Runnable {
    Runnable r;
    public RedisRunnable(Runnable r){
        this.r = r;
    }

    public void run(){
        try {
            r.run();
        }catch (Throwable e){
            e.printStackTrace();
        }
    }

}
