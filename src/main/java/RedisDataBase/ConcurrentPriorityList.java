package RedisDataBase;


public class ConcurrentPriorityList extends PriorityList{
    PriorityList list;
    EasyLock lock = new EasyLock();

    public ConcurrentPriorityList(PriorityList list){
        this.list = list;
    }

    public ExpireObject poll(){
        lock.lock();
        ExpireObject ret = list.poll();
        lock.unlock();
        return ret;
    }

    public boolean add(ExpireObject o){
        lock.lock();
        Boolean ret = list.add(o);
        lock.unlock();
        return ret;
    }

    public int size(){
        lock.lock();
        int n = list.size();
        lock.unlock();
        return  n;
    }

    public PriorityList getList() {
        return list;
    }

    // 用来确定slot是否被提交了
    public boolean isSubmitted(){
        return true;
    }
}

