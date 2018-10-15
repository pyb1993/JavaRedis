package Common;

import RedisDataBase.AbstractPooledObject;
import RedisDataBase.RedisString;

/* 辅助类 */
public class RedisUtil {

    // 因为key不可能为null,所以不需要检查
    // 只检查10进制的情况
    public static boolean isInteger(String s) {
        int len = s.length();
        if(len == 0) return false;
        if( s.charAt(0) == '-' && len == 1) {
            return false;
        }

        for(int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if(c > '9' || c < '0'){
                return false;
            }
        }
        return true;
    }

    public static boolean isInteger(RedisString s) {
        int len = s.size();
        if(len == 0) return false;
        if( s.getByte(0) == '-' && len == 1) {
            return false;
        }

        for(int i = 0; i < len; i++) {
            byte c = s.getByte(i);
            if(c > '9' || c < '0'){
                return false;
            }
        }
        return true;
    }

    // 根据lazy是否延迟释放
    public static void doRelease(Object o,boolean lazy){

        if(o instanceof AbstractPooledObject) {
            AbstractPooledObject op = (AbstractPooledObject)o;
            if(lazy){
                op.enableLazyReleased();
            }else{
                op.release();
            }
        }
    }

    public static Integer parseInt(RedisString s){
        int len = s.size();
        int i = 0;
        int sign = 1;
        int sum = 0;
        if(s.getByte(0) == '-'){
            i++;
            sign = -1;
        }

        for(; i < len; i++) {
            byte c = s.getByte(i);
            if(c > '9' || c < '0') {
                return null;
            }else{
                sum = sum * 10 + c - '0';
            }
        }

        return sum * sign;
    }


    // 获取最接近二次幂
    public static int sizeForTable(int cap){
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        n = (n < 0) ? 1 : n + 1;
        return n;
    }




}
