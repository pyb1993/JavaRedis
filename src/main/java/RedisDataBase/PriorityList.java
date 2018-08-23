package RedisDataBase;

import java.util.PriorityQueue;

/* 普通容器和并发容器 */
public class PriorityList extends PriorityQueue<ExpireObject> {
    public PriorityList(){
        super();
    }

    // 用来确定slot是否被提交了
    public boolean isSubmitted(){
        return false;
    }
}

