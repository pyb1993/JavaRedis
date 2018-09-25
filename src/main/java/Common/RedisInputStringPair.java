package Common;

import RedisDataBase.RedisString;

// 用来传递两个String的结构
public class RedisInputStringPair{
    String first;
    String second;
    public RedisInputStringPair(String first,String second){
        this.first = first;
        this.second = second;
    }
    public String getFirst(){
        return  first;
    }
    public String getSecond(){
        return second;
    }
    public void setFirst(String first){
        this.first = first;
    }
    public void setSecond(String second){
        this.second = second;
    }
}