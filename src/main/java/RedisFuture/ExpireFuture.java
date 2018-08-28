package RedisFuture;

import Common.Logger;
import RedisDataBase.ExpireObject;
import RedisDataBase.PriorityList;
import RedisDataBase.RedisDb;
import RedisDataBase.RedisTimerWheel;

import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.concurrent.Future;



public class ExpireFuture implements RedisFuture{
    public Future future;// 持有真正future的引用
    int curIndex; // 持有的index
    PriorityList[] expires;// 持有的hashMap

    public ExpireFuture(int curIndex,PriorityList[] expires,Future future){
        this.curIndex = curIndex;
        this.expires = expires;
        this.future = future;
    }

    // 注意到,toNormal必须要保证线程安全
    public void onComplete(){
        try{
            RedisDb.RedisMap.toNormal();
            RedisDb.ExpiresDict.toNormal();
            RedisTimerWheel.convertTimerWheelToNormal(curIndex);
            System.out.println("done ");
        } catch (Exception e) {
            Logger.debug(e.getStackTrace().toString());
        }
    }

    public boolean isDone(){
        return future.isDone();
    }
    public void get() throws Exception {future.get();}

}
