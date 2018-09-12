package RedisDataBase;

abstract public class AbstractPooledObject {
    abstract public int size();
    abstract public void release();
}
