package RedisFuture;

import Common.Logger;
import RedisDataBase.RedisDict;
import RedisServer.RedisServer;

import java.util.concurrent.Future;

/*用来执行RedisMap Rehash完成之后的回调*/
public class RehashFuture implements RedisFuture {
    private RedisDict dict;
    boolean holdIsDecremented = false;// 用来判断dict是不是已经执行了decrementHold()
    public Future future;
    public RehashFuture(RedisDict dict,Future future){
        this.dict = dict;
        this.future = future;
    }

    public void onComplete(){
        try{
            if(holdIsDecremented == false){
                holdIsDecremented = true;
                dict.decrementHold();
                assert (dict.inConcurrent());//这个时候可以确定是inConcurrent()的,如果resubmit了就不一定了
            }

            // 只有在主线程持有唯一引用的时候执行stoRehash才是线程安全的
            if(dict.holdByOther()){
                // 重新提交自己
                RedisServer.addFuture(this);
                System.out.println("resubmit itself when stopRehash");
            }else{
                dict.stopRehash();// rehash完成了
                System.out.println("结束啦,当前状态 len:" + dict.length() + "size:"+ dict.size() );
            }
        } catch (Exception e) {
            Logger.debug(e.getStackTrace().toString());
        }
    }

    public boolean isDone(){
        return future.isDone();
    }
    public void get() throws Exception {future.get();}

}
