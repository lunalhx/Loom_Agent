package cn.lunalhx.ai.infrastructure.gateway.resilience;

public record ModelCallKey(String provider, String model, String capability) {

    String circuitName() {
        return provider + ":" + model + ":" + capability;
    }

}
