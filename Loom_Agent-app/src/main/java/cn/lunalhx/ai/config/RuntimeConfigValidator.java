package cn.lunalhx.ai.config;

@FunctionalInterface
public interface RuntimeConfigValidator<T> {

    void validate(T value);
}
