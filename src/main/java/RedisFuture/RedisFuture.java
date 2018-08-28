package RedisFuture;

import Common.Logger;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisTimerWheel;

// todo 实现一个接口
public interface RedisFuture{
    public void onComplete();
    public boolean isDone();
    public void get() throws Exception;

}
