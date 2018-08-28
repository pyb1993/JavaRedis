package RedisDataBase;

import Common.RedisUtil;

import java.util.HashMap;

/** 用来实现一个RedisObject 里面可封装各种数据结构
 *  1 Integer(String)
 *  2 List
 *  3 Hash
 *  4 Set
 *  5 Zset
 *
 * **/
public class RedisObject {
    static int REDIS_INTEGER = 1;
    static int REDIS_STRING = 2;
    static int REDIS_LIST = 3;
    static int REDIS_HASH = 4;
    static int REDIS_SET = 5;
    static int REDIS_ZSET = 6;
    static int REDIS_HYPERLOGLOG = 7;
    private int type;
    private Object data;


    // 返回一个RedisObject,参数是String,可能是Integer,或者是普通String
    static public RedisObject redisStringObject(String obj){
        if(RedisUtil.isInteger(obj)){
            Integer n = Integer.parseInt(obj);
            if(n >= 0 && n < 10000){
                return RedisDb.RedisIntegerPool[n];
            }else{
                return new RedisObject(REDIS_INTEGER,n);
            }
        }

        return new RedisObject(REDIS_STRING,obj);
    }


    // 返回一个RedisObject,参数是HashMap
    static public RedisObject redisHashObject(RedisDict<String,RedisObject> obj){
        return new RedisObject(REDIS_HASH,obj);
    }

    public RedisObject(int type,Object data){
        this.type = type;
        this.data = data;
    }


    public Object getData(){
        return data;
    }


    public boolean isInteger(){
        return type == REDIS_INTEGER;
    }

    public boolean isString(){
        return type == REDIS_STRING;
    }

    @Override
    public int hashCode() {
        return  (data.hashCode() | (type << 10)) ^ 0x14D54B57;
    }

    // if other is null, return false
    // if type != other.type || !data.equals(other.data), return
    public boolean equals(RedisObject other){
        if( !super.equals(other)){
            return false;
        }

        if(type != other.type || !data.equals(other.data)){
            return false;
        }

        return true;
    }
}


