package MessageOutput;

import RedisDataBase.RedisString;

public class MessageOutput {
    private RedisString requestId;
    private RedisString type;
    private Object payload;

    public MessageOutput(RedisString requestId, RedisString type, Object payload) {
        this.requestId = requestId;
        this.type = type;
        this.payload = payload;
    }

    public RedisString getType() {
        return this.type;
    }

    public RedisString getRequestId() {
        return requestId;
    }

    public Object getPayload() {
        return payload;
    }
}

