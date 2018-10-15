package RedisDataBase;

abstract public class AbstractPooledObject {
    boolean isReleased;//标志是否被释放过
    boolean lazyReleased;// 标志是否可以被释放,目的是延迟释放,最后统一执行,目的是避免这里释放结果后面又在使用(在RedisHashMap和RedisConcurrentHashMap里面很常见)
    abstract public int size();
    abstract public void release();
    abstract public int cap();//总的长度
    public void setReleasedMark(){isReleased = true;}
    public void cancelReleasedMark(){isReleased = false;lazyReleased = false;}
    public void enableLazyReleased(){lazyReleased = true;}


}
