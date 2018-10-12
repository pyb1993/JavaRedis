package RedisDataBase;

abstract public class AbstractPooledObject {
    abstract public int size();
    abstract public void release();
    abstract public int cap();//总的长度
}
