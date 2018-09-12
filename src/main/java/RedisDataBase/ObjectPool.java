package RedisDataBase;

public interface ObjectPool<T> {

    public void initLengthTable();

    /*
    * 获取满足len大小的对象
    *
    */
    public T allocate(int len);

    /*
    * 返回Object
    *
    */
    public boolean releaseObject(T obj);

}
