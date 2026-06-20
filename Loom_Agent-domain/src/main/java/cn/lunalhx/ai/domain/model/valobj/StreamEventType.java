package cn.lunalhx.ai.domain.model.valobj;

public enum StreamEventType {

    META("meta"),
    TOKEN("token"),
    DONE("done"),
    ERROR("error");

    private final String eventName;

    StreamEventType(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }

}
