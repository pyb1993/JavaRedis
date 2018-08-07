package Common;

import java.util.ArrayList;

public class RedisStringList {
    public ArrayList<String> arr;
    public ArrayList<String> getArr(){return arr;}

    public RedisStringList(ArrayList<String> arr){
        this.arr = arr;
    }
}
