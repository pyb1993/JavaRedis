
### 这是一个用Netty实现的Redis服务器

### 目的有三:
1. 深入理解redis,将redis上的各种功能慢慢迁移到java上来
	用C语言来实现太麻烦了,而且一般工作中用不到C,正好练习一下Java
	同时降低开发难度,对于一些功能可以更轻松的实现
	未来可以用JNI将重要数据结构封装起来,提高性能(另开一个branch)
2. 理解现代服务器的网络/线程模型
	最近刚刚学习了Netty,顺便熟悉netty的使用
3. 可以用来学习分布式,实现简单的主从,集群功能
   学习分布式不可能只动眼不动手,所以以后可以这个项目为出发点开始魔改

---
### 目前实现的功能:

```
1. get set hget hset
```
---
### Version-1架构
因为使用了Netty,所以不需要过于操心网络,针对redis本身的特点(来往的数据量比较小,全是纯内存操作,其实cpu开销也不是很大),所以单次操作非常的快,这种情况下,cpu并不是瓶颈,往往网络先达到瓶颈(这里不是指网卡流量跑满,而是说处理各种链接的开销)
所以采取单线程(多线程带来的线程同步的开销相当大,暂时没有必要)
也就是 网络 + 业务 都在一个线程里面(一开始也尝试使用额外的线程池来执行,后面发现没有必要)

#### Handler
	用一个Map维护命令和Handler之间的关系,来一个命令就去查找对应的Handler,然后调用对应的handler
		
#### 编码/解码:
	先不考虑性能
	直接使用阿里的FastJson,用来序列化参数(command 对应的类型都利用一个hashMap提前注册好)
    
#### 协议格式	:      
	每个单元 长度 + bytes
	一共三个单元:
	requestId(用来校验)
	command(命令类型)
	content(结果,也是FastJson序列化的字节)
	

#### 数据库部分：
	在RedisDb.java文件里面,核心是一个 
	HashMap<String,RedisObject>
	
RedisObject定义如下:

```
class RedisObject{
	int type;
	Object data;
	.....
}	
```

####  实现了HyperLogLog数据结构：
##### 基本原理:
 
 *  令K 为n次投掷「硬币」面最大的投掷次数
 *  P(A) = 所有投掷次数<a href="https://www.codecogs.com/eqnedit.php?latex=$&space;P(X_i&space;<=&space;K)&space;=&space;(1&space;-&space;(1/2)^k)^n$" target="_blank"><img src="https://latex.codecogs.com/gif.latex?$&space;P(X_i&space;<=&space;K)&space;=&space;(1&space;-&space;(1/2)^k)^n$" title="$ P(X_i <= K) = (1 - (1/2)^k)^n$" /></a>
 *  P(B) = 不是所有投掷次数X_i 都小于K(因为至少有一个达到了k) = <a href="https://www.codecogs.com/eqnedit.php?latex=\bg_black&space;\large&space;$&space;1&space;-&space;(1&space;-&space;(1/2)^{k-1})^n&space;$" target="_blank"><img src="https://latex.codecogs.com/gif.latex?\bg_black&space;\large&space;$&space;1&space;-&space;(1&space;-&space;(1/2)^{k-1})^n&space;$" title="\large $ 1 - (1 - (1/2)^{k-1})^n $" /></a>
 *  注意 A,B 是同时发生的,因此P(A)和P(B)就不应该接近0,否则和「小概率事件不发生的假设」矛盾
 *  容易得到 n  约等于<a href="https://www.codecogs.com/eqnedit.php?latex=\large&space;$&space;2^K&space;$" target="_blank"><img src="https://latex.codecogs.com/gif.latex?\large&space;$&space;2^K&space;$" title="\large $ 2^K $" /></a>

 * 分桶进行统计:
 *      为了避免出现只投了一次就出现很多正面的情况,这会造成偶然误差很大,所以通过分桶来减小误差
 *      随着实验次数的增多,这里的误差就会减小,但是如果只实验几次,即便分桶也无济于事,很可能造成误差很大
 *      比如一共就只实验10次,完全有可能造成里面有一半的桶误差都很大的情况
 *     实际上Redis本身做了很多优化,特别是利用一个关于没有投中桶的次数$ez$的函数进行拟合

#####  实现步骤:
1. 对字符串进行hash,这里使用`MD5`进行hash,然后获取前64位作为一个long用来进行标志
2. 取后14位作为桶的索引,取前50位作为投掷的序列,这里曾经犯了一个错误:
		

```
. 利用 H 的后14位作为index,然后计算整个H的zeroBits,注意这个时候没有使用Hash的后50位,从而导致bug:
  注意到: 由于bits大于14的概率很小,
  所以大多数时候实验的结果都是由后面14位决定的,
  这就导致同一个index上面的,bit几乎都是一样的
  完全失去了随机的意义	
```

3. 计算低位连续为0的个数(这里是对投掷次数进行建模),计算方式如下:
	* 获取一个整数末尾有多少连续的0
	*  1 计算 `x = (val ^ (val - 1)) & val`
	*  2 `x = 2 ^ i`, i 就是末尾连续是0的个数,i最多等于50
	*  3 查表得 `i = table.get(x)`(可能还有其它的方法,比如二分移位进行试探,由于这里x**绝大多数**情况下都是0或者1,所以可以从1开始试探)
	*  4 将 `i + 1`和原来桶里面的值取大者保留, 这是为了计算1出现的位置(实际上等于至少投掷一次)
	*  5 算count的时候计算`调和平均数`,这是为了避免单个离散值导致的异常

##### 实现:

        // 计算估计的基数,利用调和平均数进行计算
    public long estimateForHLL(){
        double sumbitsInverse = 0;
        int validNum = 0;// 统计不为0的桶
        for(int i = 0; i < Num; i++){
            int bucketEstimate = buckets[i];// 连续0的数量+1,第一次出现1的index
            if(bucketEstimate != 0){
                sumbitsInverse += reciprocal[bucketEstimate];// 2 ^ -i
                validNum++;
            }
        }
        if(validNum < Num * 0.05) {
            // 如果数量很少就直接计数
            return validNum;
        }
        
        int ez = Num - validNum;// 0的数量
        sumbitsInverse += ez;// 当ez很大的时候进行的补偿(具体推导不明)
        double zl = Math.log(ez + 1);
        double beta = -0.370393911 * ez +
                0.070471823 * zl +
                0.17393686 * Math.pow(zl,2) +
                0.16339839 * Math.pow(zl,3) +
                -0.09237745 * Math.pow(zl,4) +
                0.03738027 * Math.pow(zl,5) +
                -0.005384159 * Math.pow(zl,6) +
                0.00042419 * Math.pow(zl,7);


        double alpha =  0.7213 / (1 + 1.079 / Num );
        double ret = (alpha * Num * validNum * (1 / (sumbitsInverse + beta)));
        return (long)ret;
    }
##### 实际上这几个参数一个都不能少,否则误差就会变大(原理和实现还是差距很大的,具体推导感兴趣的自己取看paper,redis源码里面给了paper的名字)
---
### Version-2架构
	增加了几个重要功能:
	1 实现了异步线程辅助完成删除过期key
	2 删除过期key使用定时轮+最小堆+随机删除
	3 实现了普通字典和支持并发的字典的切换

#### <li> 同步删除的策略:
 我们对可能的使用模型分成两种:
  1 大量的短时间的过期(分布式锁)
  2 大量长时间的过期(普通的cache应用)
  3 1 2 的结合 
	<li>对于 1,我们可以使用一个定时轮+最小堆的结构来实现,首先核心数据结构是:
   

	 static PriorityList[] expires;
	 PriorityList则是对优先队列的封装
	这样每来一个过期事件,我们就判断过期时间是不是小于一个规定的时间,比如2个小时;
	如果小于就可以直接塞到对应的`PriorityList`里面,具体：
	
	            if(delay < slotNum * 4){
	            /* 如果是那种延迟很大的过期key,就不放在定时轮里面,而是采用原来redis的随机抽样做法
	            *  因为如果过期时间很长,就会导致定时轮里面存储太多数据,从而导致插入变慢,而且占用太多内存太久
	            */
	            int index = (int)((now + delay ) & (slotNum - 1));// 计算应该放到的index
	            PriorityList slot = expires[index];
	            slot.add(new ExpireObject(key,now + delay));
	            System.out.println("add index");
	        }
    
<li>对于2,我们可以使用原来Redis的随机删除策略来实现,也就是每次从过期字典里面「随机」选取16个key,然后计算这些可以是不是过期,用抽样来判断整体过期key的情况。一个难点是:
	Java的hashMap不支持随机抽取,为了性能不可能把map变成array来临时操作,所以采取一个相对hack的办法:
	

    利用反射+Unsafe,拿到HashMap里面对应的table(Node[]),然后还是利用unsafe计算出Node的next元素,这样就可以像Redis一样进行遍历了,当然这不是一个好的办法,和JDK具体实现耦合在了一起,只是不得已的办法,最好的办法还是自己重写一个HashMap,不用JDK的那个。

#### <li> 异步删除的策略:
我们为了支持异步线程辅助一些工作,比如说帮忙删除过期key,帮忙进行扩容等操作,设计了这样的策略:
		
```
从RedisHashMap衍生两种类型,第一个是普通的HashMap,另外一个是支持并发的HashMap
如果需要异步线程的支持,我们就在主线程里面将普通的Map替换成ConcurrentRedisHashMap
像这样:
	    public static void converMapToConcurrent(){
        if(!RedisMap.holdByAnother()) {
            RedisConcurrentHashMap<String,RedisObject> cmap = new RedisConcurrentHashMap<>(RedisMap);
            cmap.incrRef();
            RedisMap = cmap;
            //RedisMap.get("1");
        }
    }

同时注意到异步线程对于PriorityList的add操作和主线程过期操作可能会有数据竞争,所以我们需要单独为一个PrioirtyList设置一个并发的版本,思路类似上面的Map,具体内容见:「ConcurrentPriorityList.class」
```
##### 上面这样做,第一是对原来的RedisMap进行了封装,但是并没有对原有的数据操作,所以无论数据有多少,都是O(1)的操作

##### 第二,所有转换都在主线程里面执行,所以不需要考虑转换过程带来的线程安全的问题

##### 第三,转换完成之后,就可以提交任务,让异步线程也对该RedisHashMap进行操作了,这个时候操作一定是线程安全的

##### 第四,一般这种操作竞争的程度并不高(一共就几个异步线程进行操作)所以性能损失不大,而且每个操作很迅速,所以自己写了一个简单的spin-lock用来进行保护即可,具体内容见`RedisConcurrentHashMap.class`

#### #最后,我们只需要在异步线程结束之后,在主线程将状态转换回来即可,这种情况下只能让主线程去轮训提交的异步任务的future,只要future完成,就执行对应的回调,具体内容见`「ExpireFuture.java」`部分  

此外,还有一些小的优化,这里就不一一赘述了。
----
#### Version-3
##### 改动1:  增加了对于渐进式rehash的支持,为了达到这个目的,我重写了RedisHashMap(其实就是把JDK源码抄了一部分过来)
##### 改动2:  增加了异步线程进行rehash的支持(重点是确保线程安全,使得删除过期,rehash,以及主线程rehash协作运行)
---
### 思路设计
#### <li> 渐进rehash的支持
		实际上,最开始我使用的是JDK的HashMap,但是存在各种问题,你要在主动rehash的过程中防止hashMap自动扩容,要处理size的线程不安全,还要面对无法高效率的获取随机的值等问题。
		要绕过JDK的限制非常麻烦,而且关于自动扩容的问题, 非常难以限制,会导致线程安全的问题
		比如异步线程往新的map添加元素,同时主线程也将新的元素添加到·新的map,如果并发够大,
		完全可能产生新的map在扩容过程中再次扩容,这个时候导致分段锁失效等情况。
		
##### <li> 主线程渐进式rehash的逻辑
	put: 只往新的map存,但是同时要删除老的map里面可能的key
	remove: 先删老的map,再删新的map(否则存在线程安全)
	get: 先查新的map,后查老的map  
	什么时候执行这个逻辑:
		分两种情况来讨论,首先考虑扩容,这里把负载因子1作为分界线
		然后考虑缩容,这里把负载因子0.5作为分界限(缩容的时候还要考虑频率,限定为至少间隔1s)
		另外对于size比较小的rehash,就直接在主线程执行

		put的代码:
		 if(RedisServer.isCurrentThread()){
		            safePut(key,val);
		        }else{
		            if(!inRehashProgress()){
		                // 不扩容状态
		                greadLock();
		                safePut(key, val);
		                greadUnLock();
		            }else{
		                // 这里处于扩容状态， rehashMap != null
		                // 但是由于没有锁的保护,可能突然变成非扩容状态,rehashMap == NULL
		                // 所以 stopRehash只能在「没有异步线程的」
		                safePut(key,val);
		            }
		        }
		
		        // 这里有多种case 1 主线程扩容,当前字典可能是「普通状态」／「并发状态」
        // 2 异步线程扩容,且当前字典已经是扩容的状态
        // 3 异步线程扩容,当前字典一定是并发状态
        // 我们只在主线程切换状态,所以其它两个线程我们不执行
        if(RedisServer.isCurrentThread() && needGrowth(true)){
            startRehash(); // case2会直接被startRehash过滤
        }

##### <li> 异步线程的逻辑:
	遍历所有老的slot,将这些slot添加到新的map上面
	关键点: 每一个slot都需要加锁来保证正确性
	
	 EasyLock lock = lockArr[index & (lockNum - 1)];
        lock.lock(); // 需要lock住固定的index
        Node node = map.table[index];
        int nodeNum = 0;
        while (node != null){
            // 进行rehash
            ++nodeNum;
            rehashMap.put(node.getKey(),node.getValue());// 自动加锁
            node = node.next;
        }
        map.table[index] = null;//所有的都设置为null,这样就成功将map本身给解决了,map本身的size也应该修改
        tmpSize.addAndGet(-nodeNum);
        lock.unlock();
        
##### <li> 线程安全和性能的讨论
		  首先我们引入了一个新的中间层 `RedisDict`
		  这个东西负责 rehash操作和 普通RedisHashMap到RedisConcurrentHashMap的转换,这样对于使用RedisDict的操作就完全屏蔽了。
		  但是,对于rehash操作,由于存在突然变成rehash状态和突然从rehash状态结束两种变化,那么这个线程安全是必须要保证的。

---
如果直接加锁,那么就会导致性能很差,因为处于并发状态和rehash状态的时间很少,如果每次检查是否处于rehash状态的时候都要加锁, 那么性能未免也太低了。一个做法是「double check」的思路,回想单例模式里面的常见pattern实现,可以避免大部分情况下进行加锁. 但是这里存在一个问题,采取这种模式必须保证状态的变化是「**单向**」的,比如说单例里面状态不能「从有到无」。但是rehash的变化是双向的状态,那么怎么做?

----
	两种策略: 1 延迟stopRehash的操作,所有的stopRehash操作只在没有异步线程持有当前RedisDict的时候才执行,这样就避免了上面说的问题。
				2 先检查是不是在主线程执行的这个操作,如果在主线程就不需要对检查 rehash状态加锁,因为所有对状态变化只发生在主线程。
	再看一次put的代码,这里采取策略2:
	
	 void safePut(K key, T val){
	        if(inRehashProgress()){
	            // 扩容状态
	            rehashMap.put(key,val);
	            map.remove(key);// 这一步是必要的
	        }else {
	            // 非扩容状态
	            map.put(key,val);
	        }
	    }

	if(RedisServer.isCurrentThread()){
		            safePut(key,val);
		        }else{
		           greadLock();
		            if(!inRehashProgress()){
		                // 不扩容状态
		                safePut(key, val);
		             }else{
		                // 这里处于扩容状态， rehashMap != null
		                // 但是由于没有锁的保护,可能突然变成非扩容状态,rehashMap == NULL
		                // 所以 stopRehash只能在「没有异步线程的」
		                safePut(key,val);
		            }
		             greadUnLock();
		        }
分析:首先判断是不是在主线程,如果在主线程,那么就代表不用担心状态突然发生变化,因为这是因为所有的状态变化都在「主线程执行」,所以不存在其它线程的干扰。
	如果不是在主线程,那么就需要加一个读锁,这是为了防止状态改变.

----
来看看remove,这里采取来策略1来实现(其实可以把策略2也加入进来,但是没有必要搞那么麻烦,因为本来都是小概率情况)	

	 public void remove(K key) {
        map.remove(key);
        if(inRehashProgress()) {// 1
            greadLock();
            if(inRehashProgress()){
                rehashMap.remove(key);
            }
            greadUnLock();
        }

        if(RedisServer.isCurrentThread() && needtrim(true)){
            startRehash();
        }
    }    
 ----
#### <li>  锁的实现
这里有两种锁,一种是自旋锁,一种是「读写自旋锁」,后者是为了保护「rehash状态」的,在改变rehash状态的时候要加上「writeLock」,检查的时候加上「readLock」.
因为这里的临界操作都非常短,所以全部用自旋的形式实现,而且不考虑公平性(一般0.1ms都不要就执行完了,所以尽可能的高效率)

---

#### <li> 状态改变的条件
	由于map具有这样几个属性,「是否并发」,「是否正在扩容」,「被几个线程持有」,其中「被其它线程持有」一定意味这「处于并发状态」
	所以停止rehash的时候,需要考虑一些情况,比如
	如果被其它线程持有着(并发状态),那么不能立刻结束rehash状态,这本来是通过「读写自旋锁」来进行保证的, 但是我们可以再提高一点性能,如果该Dict被其它线程持有,那么会延后执行该Dict的stopRehash,这样就可以先执行其它任务而不是「自旋」
	
	在开始rehash的时候,也要根据「是否已经处于并发状态」,「是否可以直接执行rehash」
	
	死锁,注意到获取size的时候同样存在线程安全的问题,但是size加的是「readLock」,如果调用的上层恰好使用「writeLock」,就会导致死锁。所以还需要一个无锁版本的size
